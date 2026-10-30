"""Organize existing FF test evidence. Never runs Gradle, ADB or changes a verdict.

cases.json and manual_runs.jsonl are maintenance sources. runs.jsonl is an
append-only import ledger. CSV/XLSX/Markdown are disposable generated views.
"""
import argparse
import collections
import csv
import datetime as dt
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'docs/testing'
STATUSES={'未测','通过','失败','阻塞','不适用'}


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def read_lines(path):
    return [json.loads(line) for line in Path(path).read_text(encoding='utf-8').splitlines() if line.strip()] if Path(path).exists() else []


def append_unique(path,rows):
    path=Path(path);existing={r['id'] for r in read_lines(path)};new=[]
    for row in rows:
        if row['id'] not in existing:new.append(row);existing.add(row['id'])
    if new:
        path.parent.mkdir(parents=True,exist_ok=True)
        with path.open('a',encoding='utf-8',newline='\n') as file:
            for row in new:file.write(json.dumps(row,ensure_ascii=False,separators=(',',':'))+'\n')
    return len(new)


def digest(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def relative(path):return str(Path(path).resolve().relative_to(ROOT)).replace('\\','/')
def execution(id,status,actual,scope='汇总'):
    assert status in STATUSES
    return dict(case_id=id,status=status,actual=actual,scope=scope)


def classify(batch,targets):
    if batch.get('identity_problem'):return '身份未核验',batch['identity_problem']
    source=batch.get('source') or {};fp=source.get('fingerprint');apk=batch.get('apk_sha256')
    target=targets.get(batch.get('variant'))
    if not fp or target is None:return '身份未核验','缺少可核对的源码指纹或构建类型；不从同名版本号推测'
    if not apk:
        if batch.get('environment')=='JVM' and batch.get('source_type')=='check':
            return ('当前源码（JVM）','JVM不要求APK身份') if fp==target['fingerprint'] else ('历史构建','源码不同，待按改动影响评估')
        return '身份未核验','缺少APK身份；不能补造'
    if fp==target['fingerprint'] and apk==target['sha256']:return '当前构建','源码内容与APK均匹配目标'
    return '历史构建','源码或APK不同；原结果保留，需按改动影响评估'


def check_executions(raw):
    rows=[];tasks=[t for t in raw.get('tasks',[]) if isinstance(t,str)]
    passed=raw.get('status')=='passed';full=raw.get('mode') in ('Full','Emulator')
    if passed and any(t.startswith('assemble') for t in tasks):
        rows.append(execution('FF-GATE-BUILD','通过','assemble任务所在检查通过；仅构建产物'))
    if passed and any(t.startswith('test') and t.endswith('UnitTest') for t in tasks) and isinstance(raw.get('tests'),int):
        bad=raw.get('failures') or raw.get('errors');skip=raw.get('skipped')
        missing=not all(isinstance(raw.get(k),int) for k in ('failures','errors','skipped'))
        rows.append(execution('FF-GATE-UNIT','失败' if bad else '阻塞' if skip or missing else '通过',
            f"JUnit汇总 tests={raw['tests']}, failures={raw.get('failures')}, errors={raw.get('errors')}, skipped={skip}；不拆成逐功能通过"))
    if passed and any(t.startswith('lint') for t in tasks) and isinstance(raw.get('lintErrors'),int):
        rows.append(execution('FF-GATE-LINT','通过' if raw['lintErrors']==0 else '失败',
            f"Lint errors={raw['lintErrors']}, warnings={raw.get('lintWarnings','未记录')}；不证明设备功能"))
    if not passed:
        # A Gradle compilation failure may leave previous JUnit XML/counts.
        # Keep the original run-level failure, do not guess which task ran.
        rows.append(execution('FF-GATE-CHECK','失败' if raw.get('status')=='failed' else '阻塞',
            '原检查未通过；失败任务未细分，不沿用残留JUnit计数。'+str(raw.get('error','未记录原因'))[:1500]))
    return rows


def device_executions(raw,mapping=None):
    rows=[];mapping=mapping or {}
    for suite in raw.get('passedSuites',[]):
        rows.append(execution('FF-SUITE-'+suite.upper(),'通过',
            'passedSuites明确列出 '+suite+'；仅覆盖该版本脚本断言，非真机听感/触感/视觉结论','套件汇总'))
        for field,id in mapping.get(suite,{}).items():
            # Exact detail assertions, not inferred success from the whole suite.
            if raw.get('details',{}).get(suite,{}).get(field) is True:
                rows.append(execution(id,'通过',f'details.{suite}.{field}=true；范围仅为该字段对应的脚本断言','结构化细项'))
    state='通过' if raw.get('status')=='passed' else '失败' if raw.get('status')=='failed' else '阻塞'
    rows.append(execution('FF-GATE-DEVICE',state,
        f"批次原状态={raw.get('status')}；数据还原={raw.get('appDataRestored','未记录')}；"+
        str(raw.get('error','未记录失败原因') if state!='通过' else '源码/APK一致性由原runner检查')[:1800]))
    return rows


def junit_execution(cls,attrs):
    counts={k:int(attrs.get(k,0)) for k in ['tests','failures','errors','skipped']}
    state='失败' if counts['failures'] or counts['errors'] else '阻塞' if counts['skipped'] or not counts['tests'] else '通过'
    return execution('FF-JVM-'+cls.removesuffix('Test').upper(),state,
        f'{cls} 原XML计数 '+json.dumps(counts,ensure_ascii=False)+'；独立XML无源码/APK绑定，不借用旁边Full结果补身份','类汇总')


def time_from_folder(name):
    if re.fullmatch(r'\d{8}-\d{6}',name):
        return dt.datetime.strptime(name,'%Y%m%d-%H%M%S').isoformat(), '来自目录名；原runner未记录时区'
    return None,'时间未记录'


def import_evidence(catalog):
    targets=read_json(DATA/'target.json')['builds'];batches=[];inventory=[];by_apk={}
    candidates=sorted((ROOT/'.tools/checks').glob('*.json'))
    # Resolve build type only from an exact APK hash in a real check summary.
    for path in candidates:
        if path.name=='latest.json':continue
        try:r=read_json(path)
        except (ValueError,UnicodeError):continue
        sha=(r.get('apk') or {}).get('sha256')
        if sha and r.get('variant') in ('Debug','Release'):by_apk.setdefault(sha,set()).add(r['variant'])
    for path in candidates+sorted((ROOT/'.tools/device-checks').glob('*/results.json'))+sorted((ROOT/'docs/validation').rglob('*.json')):
        sha=digest(path);loc=relative(path);row=dict(id=loc+'@'+sha,location=loc,sha256=sha)
        try:r=read_json(path)
        except (ValueError,UnicodeError) as error:
            row['note']='无法解析：'+str(error);inventory.append(row);continue
        if not isinstance(r,dict):row['note']='旧结构（非对象），仅索引';inventory.append(row);continue
        if path.name=='latest.json':row['note']='latest指针，不重复导入';inventory.append(row);continue
        device='passedSuites' in r and 'apkSha256' in r
        check=r.get('mode') in ('Full','Unit','Emulator') and 'tasks' in r and 'status' in r
        if not device and not check:
            row.update(note='旧schema/交付汇总，仅索引原声明；不把引用结果重复计为新执行',date=r.get('date') or r.get('createdAt'),
                source_fields={k:r[k] for k in ['commit','base_commit','apk','apk_sha256','debugApkSha256','source'] if k in r},
                declared_scope=(r.get('passed') or r.get('ui_passed') or r.get('assertions') or r.get('notes') or []) )
            inventory.append(row);continue
        variant=r.get('variant');apk=r.get('apkSha256') if device else (r.get('apk') or {}).get('sha256')
        identity_note='原摘要'
        if not variant and apk:
            matches=by_apk.get(apk,set()) or {k for k,v in targets.items() if v['sha256']==apk}
            if len(matches)==1:variant=next(iter(matches));identity_note='构建类型按完全相同APK SHA256关联检查摘要'
        when,timenote=time_from_folder(path.parent.name) if device else (r.get('startedUtc'), '原startedUtc；UTC' if r.get('startedUtc') else '时间未记录')
        batch=dict(id=('DEVICE-' if device else 'CHECK-')+path.parent.name+'-'+path.stem+'-'+sha[:12],
            time=when,time_note=timenote,source_type='device' if device else 'check',source=r.get('source') or {},
            variant=variant or '未记录',apk_sha256=apk,environment='模拟器' if device else 'JVM',method='自动化',
            device=r.get('device') if device else '不适用（主机JVM/Gradle）',android='未记录（专用API36脚本约束，不当作当次设备指纹）' if device else '不适用',
            launcher='未记录（Launcher3脚本入口；当次版本未保存）' if device else '不适用',
            evidence=loc,evidence_sha256=sha,identity_note=identity_note,original_status=r.get('status'),
            executions=device_executions(r,catalog.get('device_details')) if device else check_executions(r))
        if (r.get('sourceAfter') and r.get('sourceAfter')!=r.get('source')) or 'Source changed' in str(r.get('error','')):
            batch['identity_problem']='原检查报告执行期间源码改变；不得套用开始时身份'
        row.update(note='已导入，保持原证据粒度',batch_id=batch['id']);inventory.append(row);batches.append(batch)
    for path in sorted((ROOT/'app/build/test-results').rglob('TEST-*.xml')):
        raw=ET.parse(path).getroot();cls=raw.get('name','').split('.')[-1]
        if cls not in catalog['junit_classes']:continue
        sha=digest(path);loc=relative(path)
        b=dict(id='JUNIT-'+path.parent.name+'-'+cls+'-'+sha[:12],time=raw.get('timestamp'),time_note='原XML timestamp；未记录时区',
            source_type='junit',source={},variant='Debug' if path.parent.name=='testDebugUnitTest' else 'Release' if path.parent.name=='testReleaseUnitTest' else '未记录',
            apk_sha256=None,environment='JVM',method='自动化',device='不适用（主机JVM）',android='不适用',launcher='不适用',
            evidence=loc,evidence_sha256=sha,identity_note='JUnit没有源码/APK哈希；不按邻近时间推测绑定',
            executions=[junit_execution(cls,raw.attrib)])
        # Retain actual method identities/counters for inspection without creating
        # new semantic functional passes from class totals.
        b['junit_methods']=[dict(name=c.get('name'),classname=c.get('classname'),status='失败' if c.find('failure') is not None or c.find('error') is not None else '阻塞' if c.find('skipped') is not None else '通过') for c in raw.findall('testcase')]
        batches.append(b);inventory.append(dict(id=loc+'@'+sha,location=loc,sha256=sha,note='JUnit原始类/方法计数已导入，身份未核验',batch_id=b['id']))
    added=append_unique(DATA/'runs.jsonl',batches)
    # Preserve indices for reports no longer present locally; availability is a view.
    old=read_json(DATA/'evidence-index.json') if (DATA/'evidence-index.json').exists() else []
    merged={r['id']:r for r in old};merged.update({r['id']:r for r in inventory})
    (DATA/'evidence-index.json').write_text(json.dumps(list(merged.values()),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return added


def flatten(cases,batches,targets,issues):
    rows=[];case_ids={c['id'] for c in cases}
    by_record={}
    for b in batches:
        for i,r in enumerate(b['executions']):by_record[r.get('id') or b['id']+':'+r['case_id']+':'+str(i)]=(b,r)
    def resolves(prior_id,next_id):
        if prior_id not in by_record or next_id not in by_record:return False
        old,prior=by_record[prior_id];new,next=by_record[next_id]
        if next['status']!='通过' or classify(new,targets)[0] not in ('当前构建','当前源码（JVM）'):return False
        if any(old.get(k)!=new.get(k) for k in ('environment','variant','device')):return False
        if prior['case_id']!=next['case_id'] or prior['scope']!=next['scope']:return False
        if old.get('environment')=='真机':
            for k in ('device','android'):
                if not old.get(k) or '未记录' in str(old[k]) or old[k]!=new.get(k):return False
        return True
    issue_by_record=collections.defaultdict(list);retest_by_record=collections.defaultdict(list);retest_from=collections.defaultdict(list)
    for batch in batches:
        for index,run in enumerate(batch['executions']):
            record=run.get('id') or batch['id']+':'+run['case_id']+':'+str(index)
            for prior in run.get('retest_of',[]):
                retest_by_record[prior].append(record);retest_from[record].append(prior)
            issue_by_record[record].extend(run.get('issues',[]))
    for issue in issues:
        for id in issue.get('failed_records',[]):issue_by_record[id].append(issue['id'])
        for link in issue.get('retests',[]):
            retest_by_record[link['of']].append(link['record']);retest_from[link['record']].append(link['of']);issue_by_record[link['record']].append(issue['id'])
    for batch in batches:
        applicability,why=classify(batch,targets)
        for index,run in enumerate(batch['executions']):
            if run['case_id'] not in case_ids:raise ValueError('未知用例 '+run['case_id'])
            record=run.get('id') or batch['id']+':'+run['case_id']+':'+str(index)
            state=run['status']
            resolved=[id for id in retest_by_record[record] if resolves(record,id)]
            if resolved:retest='通过复测';reason='同例/环境/构建类型的当前身份通过复测：'+', '.join(resolved)
            elif record in retest_by_record and retest_by_record[record]:retest='已有复测未解决';reason='关联结果未通过、身份或范围不匹配；不能据此解除原失败'
            elif applicability.startswith('当前') and state=='通过':retest='不需要';reason='仅在本记录的环境与证据粒度内成立'
            elif state in ('失败','阻塞'):retest='需要';reason='原失败/阻塞尚无明确关联复测'
            elif applicability=='历史构建':retest='待评估';reason='按改动影响选范围；不要求用户全量重测'
            else:retest='待核验';reason=why
            rows.append(dict(record_id=record,case_id=run['case_id'],batch_id=batch['id'],time=batch.get('time'),time_note=batch.get('time_note'),
                environment=batch['environment'],method=batch['method'],actor=batch.get('actor','原自动化runner'),variant=batch.get('variant','未记录'),
                status=state,applicability=applicability,retest=retest,reason=reason,actual=run['actual'],source=batch.get('source') or {},apk_sha256=batch.get('apk_sha256'),
                device=batch.get('device'),android=batch.get('android'),launcher=batch.get('launcher'),evidence=batch['evidence'],evidence_sha256=batch.get('evidence_sha256'),
                scope=run['scope'],issues=issue_by_record[record],retest_of=retest_from[record],resolved_by=resolved))
    return rows


def case_view(case,records,target):
    own=[r for r in records if r['case_id']==case['id']]
    def latest(env):
        matches=[r for r in own if r['applicability'].startswith('当前') and ((r['environment']=='真机') if env=='phone' else r['environment']!='真机')]
        if not matches:return '未测'
        # A later PASS is not an implicit retest of an unresolved failure.
        if any(r['status']=='失败' and not r.get('resolved_by') for r in matches):return '失败'
        return max(matches,key=lambda r:r.get('time') or '')['status']
    auto,phone=latest('auto'),latest('phone')
    release='不适用（Release无本地音频）' if case['builds']==['Debug本地音频'] and not target['builds']['Release']['audio_local'] else '适用'
    reason=case.get('gap') or ('有历史/身份未核验记录；未转为当前通过' if own and auto==phone=='未测' else '本轮未取得对应逐例执行证据' if not own else '按本例证据粒度查看执行表')
    return dict(case,auto_current=auto,phone_current=phone,release_applicability=release,
        retest='需要真机验收' if case['manual_tier']=='必须真机' and phone!='通过' else '待影响评估' if auto==phone=='未测' else '按改动影响',reason=reason)


CASE_COLUMNS=[('id','用例ID'),('module','模块'),('scenario','场景'),('kind','用例类型'),('priority','优先级'),('manual_tier','人工分类'),('auto_current','当前自动结果'),('phone_current','当前真机结果'),('retest','复测建议'),('reason','证据缺口/原因'),('preconditions','前置条件'),('steps','操作步骤'),('expected','明确预期'),('builds','构建类型'),('environments','环境'),('recommended_path','推荐途径'),('entry_points','代码/操作入口'),('scope','证据粒度'),('release_applicability','当前Release适用性')]
RUN_COLUMNS=[('record_id','记录ID'),('case_id','用例ID'),('batch_id','批次'),('time','执行时间原值'),('time_note','时间来源/时区'),('environment','环境'),('method','方式'),('variant','构建'),('status','结果'),('applicability','当前适用性'),('retest','是否需复测'),('reason','原因'),('actual','实际观察'),('commit','报告源码提交'),('fingerprint','内容指纹'),('apk_sha256','APK SHA256'),('device','设备'),('android','Android'),('launcher','启动器'),('evidence','原证据位置'),('issues','关联问题'),('retest_of','复测自'),('scope','证据粒度')]


def cell(value):
    if value is None:return '未记录'
    if isinstance(value,list):return '\n'.join(str(v) for v in value)
    return value


def report(catalog,out):
    out.mkdir(parents=True,exist_ok=True);target=read_json(DATA/'target.json')
    issues=read_json(DATA/'issues.json') if (DATA/'issues.json').exists() else []
    batches=read_lines(DATA/'runs.jsonl')+read_lines(DATA/'manual_runs.jsonl')
    rows=flatten(catalog['cases'],batches,target['builds'],issues)
    views=[case_view(c,rows,target) for c in catalog['cases']]
    views.sort(key=lambda c:(0 if c['manual_tier']=='必须真机' else 1 if c['kind']=='功能场景' else 2 if c['manual_tier']=='建议真机' else 3,
        c.get('manual_group') or 0,c['priority'],c['id'].replace('01A','00A')))
    for r in rows:r['commit']=r['source'].get('commit');r['fingerprint']=r['source'].get('fingerprint')
    rows.sort(key=lambda r:(r.get('time') or '',r['record_id']),reverse=True)
    matrices={}
    for name,columns,items in [('测试用例库',CASE_COLUMNS,views),('测试执行记录',RUN_COLUMNS,rows)]:
        matrix=[[label for key,label in columns]]+[[cell(r.get(key)) for key,label in columns] for r in items]
        matrices[name]=matrix
        with (out/(name+'.csv')).open('w',encoding='utf-8-sig',newline='') as file:csv.writer(file).writerows(matrix)
    stats=dict(cases=len(views),case_kinds=dict(collections.Counter(c['kind'] for c in views)),priorities=dict(collections.Counter(c['priority'] for c in views)),
        execution_records=len(rows),batches=len(batches),outcomes=dict(collections.Counter(r['status'] for r in rows)),
        applicability=dict(collections.Counter(r['applicability'] for r in rows)),
        current_evidence=[dict(case_id=r['case_id'],environment=r['environment'],variant=r['variant'],status=r['status'],scope=r['scope']) for r in rows if r['applicability'].startswith('当前')],
        current_phone_passes=sum(r['environment']=='真机' and r['status']=='通过' and r['applicability']=='当前构建' for r in rows),
        required_phone_cases=[c['id'] for c in views if c['manual_tier']=='必须真机' and c['phone_current']!='通过'])
    payload=dict(target=target,statistics=stats,sheets=matrices)
    (out/'report-data.json').write_text(json.dumps(payload,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    lines=['# 测试覆盖与证据摘要','',f"目标：{target['id']}。Excel是导出报告；维护源见README。",'',
        f"用例 {len(views)}；执行记录 {len(rows)}；批次 {len(batches)}。这些是条目数量，不是功能覆盖率。",'',
        f"用例粒度：{stats['case_kinds']}。",f"执行原结果：{stats['outcomes']}（跨版本/环境，不相加当成当前通过）。",'',
        f"本包真机通过记录：{stats['current_phone_passes']}；最小真机待验用例：{len(stats['required_phone_cases'])}。",'',
        '## 当前身份可核对的执行范围','', '| 用例 | 环境 | 构建 | 原结果 | 证据粒度 |','|---|---|---|---|---|']
    for r in stats['current_evidence']:lines.append('| '+' | '.join(str(r[k]) for k in ['case_id','environment','variant','status','scope'])+' |')
    lines+=['','## 各模块尚未取得逐例当前结果的条目','', '| 模块 | 用例数 | 当前自动通过 | 当前真机通过 | 必须真机待验 |','|---|---:|---:|---:|---:|']
    for module in dict.fromkeys(c['module'] for c in views):
        group=[c for c in views if c['module']==module]
        lines.append(f"| {module} | {len(group)} | {sum(c['auto_current']=='通过' for c in group)} | {sum(c['phone_current']=='通过' for c in group)} | {sum(c['manual_tier']=='必须真机' and c['phone_current']!='通过' for c in group)} |")
    lines+=['','## 自动化与证据缺口','',
        '- 独立JUnit XML没有源码/APK绑定，类/方法原结果保留，但身份未核验。Full汇总的通过不能替代逐类绑定。',
        '- 现有设备报告没有当次Android build fingerprint及启动器版本；保留模拟器序列号和脚本约束说明，不冒充实测型号/版本。',
        '- 除组件结构化细项白名单外，历史套件只记汇总，不拆成全部功能通过；截图生成不等于视觉通过。',
        '- 读旧schema的验收JSON保留索引和原声明；缺统一结果/身份的不自动判整批通过。',
        '- 音频焦点、连续听感、真实震动与HyperOS桌面/后台策略仍需真机；不向用户转交可由单测/ADB完成的矩阵。',
        '- 本轮未执行应用测试；只整理既有记录。后续可在原check/runner旁保存JUnit文件哈希、设备build和launcher版本，另立改进任务，不更改本轮判定。','']
    (DATA/'STATUS.md').write_text('\n'.join(lines),encoding='utf-8')
    generate_phone(views,target)
    print(json.dumps(stats,ensure_ascii=False,default=str))
    return payload


def generate_phone(views,target):
    lines=['# 用户真机测试清单','',f"目标：{target['id']}，0.3.0 / code 4，本地Debug。APK SHA256：`{target['builds']['Debug']['sha256']}`。",'',
        '这是用例库派生视图，只在cases.json维护步骤；执行结果另记manual_runs.jsonl。已有同SHA/同机/同系统通过的项可跳过，不凭同名版本号要求重测。',
        '本地Debug已含前段/爆炸/成功三录音；Release无本地录音且当前为未签名检查包，不要求安装或把无声当故障。不卸载清数据。',
        '沿用悬浮/通知及Redmi桌面快捷方式权限，保持日常省电设置，不新增无障碍、使用情况或保活权限。用普通60秒(C4关)和C4 60秒预设；组件显示原顺序前两个，不要求重排。',
        f"先做分组1–6的{sum(c['manual_tier']=='必须真机' for c in views)}个可独立判定项，可连续操作；分组7为建议补充。普通计时不要求结束铃声。C4口令7355608，下包4.92秒后可输入；归零尾音后恢复超时。",'']
    for c in sorted([c for c in views if c['manual_tier']!='自动化优先'],key=lambda c:(c['manual_group'],c['id'])):
        lines.extend([f"## {c['id']} {c['scenario']}（{c['priority']}，{c['manual_tier']}，分组{c['manual_group']}）",'',
            '- 目的：'+c['scenario'],'- 前置：'+c['preconditions'],'- 操作：'+c['steps'],'- 预期：'+c['expected'],
            '- 现有验证不足：'+c['gap'],'- 当前真机结果：'+c['phone_current'],''])
    lines+=['## 异常反馈','',
        '反馈用例ID、入口、操作及发生时剩余秒数、实际现象、约发生时间和复现频率；桌面/触摸附截图或短录屏。音频说明媒体音量、静音/勿扰、扬声器/耳机，尽量保留实际声音；振动用文字确认触感。',
        '已知REDMI K80/Android16；系统更新才补新版本。授权连接ADB后由AI读取可用日志、窗口/服务、焦点与Debug私有记录，不要求用户筛Logcat。原日志可能滚动丢失，尽早反馈时间。',
        '正常划掉与无回调强杀分别记：强杀只能约计最后检查点，不能保证1秒内或补停机时间。勿扰振动受系统闹钟政策，不能一概判失败。','']
    (DATA/'REAL_DEVICE.md').write_text('\n'.join(lines),encoding='utf-8')


def validate_batches(catalog,batches,issues=()):
    cases=catalog['cases'];ids=[c['id'] for c in cases]
    def require(condition,message):
        if not condition:raise ValueError(message)
    require(len(ids)==len(set(ids)),'重复用例ID')
    for c in cases:
        for key in ['id','module','scenario','preconditions','steps','expected','priority','builds','environments','recommended_path','entry_points']:
            require(bool(c.get(key)),f"{c['id']}缺{key}")
        require(c['priority'] in ('P0','P1','P2'),'非法优先级')
    require(len({b['id'] for b in batches})==len(batches),'批次ID重复')
    for b in batches:
        for key in ['id','time','time_note','source','variant','apk_sha256','environment','method','device','android','launcher','evidence','executions']:
            require(key in b,f"{b.get('id')}缺批次字段{key}")
        require(b['environment'] in ('JVM','模拟器','真机') and b['method'] in ('人工','自动化'),'非法环境或方式')
        require(isinstance(b['source'],dict) and bool(b['evidence']),'缺源码对象或证据位置')
        require(bool(b['executions']),'空执行批次')
        for r in b['executions']:
            require(r['case_id'] in ids,f"{b['id']}未知用例")
            require(r['status'] in STATUSES and bool(r.get('actual')),f"{b['id']}缺状态或实际观察")
            require(bool(r.get('scope')),f"{b['id']}缺scope")
    records={r.get('id') or b['id']+':'+r['case_id']+':'+str(i) for b in batches for i,r in enumerate(b['executions'])}
    require(len(records)==sum(len(b['executions']) for b in batches),'执行记录ID重复')
    for b in batches:
        for r in b['executions']:
            require(all(id in records for id in r.get('retest_of',[])),'复测引用不存在的原记录')
    for issue in issues:
        require(all(id in records for id in issue.get('failed_records',[])),issue['id']+'失败记录不存在')
        require(all(l['of'] in records and l['record'] in records for l in issue.get('retests',[])),issue['id']+'复测记录不存在')


def validate(catalog):
    batches=read_lines(DATA/'runs.jsonl')+read_lines(DATA/'manual_runs.jsonl')
    validate_batches(catalog,batches,read_json(DATA/'issues.json') if (DATA/'issues.json').exists() else [])
    cases=catalog['cases']
    print(f'VALID: {len(cases)} cases, {len(batches)} immutable batches')


def add_manual_batch(path,batch,catalog,other_batches=(),issues=()):
    existing=read_lines(path)
    # Validate everything before the append. A rejected candidate must leave
    # the immutable ledger unchanged, including cross-ledger IDs and references.
    validate_batches(catalog,list(other_batches)+existing+[batch],issues)
    return append_unique(path,[batch])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['refresh','validate','add-run'])
    parser.add_argument('--output',type=Path,default=ROOT/'dist/testing')
    parser.add_argument('--file',type=Path)
    args=parser.parse_args();catalog=read_json(DATA/'cases.json')
    if args.command=='add-run':
        if not args.file:parser.error('add-run requires --file')
        batch=read_json(args.file)
        add_manual_batch(DATA/'manual_runs.jsonl',batch,catalog,read_lines(DATA/'runs.jsonl'),read_json(DATA/'issues.json') if (DATA/'issues.json').exists() else [])
    if args.command=='refresh':print('Imported new batches:',import_evidence(catalog))
    validate(catalog)
    if args.command!='validate':report(catalog,args.output)


if __name__=='__main__':main()
