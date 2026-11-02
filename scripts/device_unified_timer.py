"""Two real home pages and one configuration feeding manual and App launches.

Uses the existing dedicated emulator runner and its exact data backup/restore.
No App monitoring, fake launch fallback or separate timer implementation.
"""
import json
import time

from device_preferences import pref_values, set_wheel, wheel

PACKAGE = 'app.floatingfocus'


def home_unified(d):
    def texts(): return [n.get('text', '') for n in d.dump().iter('node')]
    def top():
        for _ in range(4): d.shell('input','swipe',int(d.width*.03),int(d.height*.25),int(d.width*.03),int(d.height*.85),180)
    def wait(check, message):
        end=time.monotonic()+8
        while time.monotonic()<end:
            if check(): return
            time.sleep(.15)
        raise AssertionError(message)
    def point(label):
        node=next(n for n in d.dump().iter('node') if label in (n.get('text'),n.get('content-desc')) and d.bounds(n)[3]>d.bounds(n)[1])
        x,y,r,b=d.bounds(node)
        return (x+r)//2,(y+b)//2
    def park():
        if d.overlay(): d.shell('input','swipe',*d.center(),int(d.width*.8),int(d.height*.8),350)
    def end():
        d.start(); park(); d.finish(); top()
    def assert_record(before, mode, bound, seconds):
        wait(lambda:d.overlay() is not None and len(d.records())==before+1,'Timer/record did not start exactly once')
        row=d.records()[-1]
        assert row['mode']==mode and row['plannedMillis']==(seconds*1000 if mode=='COUNTDOWN' else 0),row
        assert row['packageName']==bound,row
        assert ('com.android.gallery3d' if bound else PACKAGE) in d.foreground(), 'Wrong actual foreground'
        return row

    d.seed(); d.finish(); d.stop()
    targets=[dict(package='com.android.gallery3d',label='Gallery'),dict(package='com.android.documentsui',label='Files'),
             dict(package='app.unavailable.fixture',label='Unavailable')]
    d.write_prefs('quick_launch',{'targets':json.dumps(targets)})
    d.write_prefs('quick_durations',{'seconds':'300,600,900,1800','startup_mode':'COUNTDOWN','startup_seconds':900})
    d.start()
    before=len(d.records())
    print('Unified: six stored legacy orders normalize without starting anything...',flush=True)
    migrations={
        'timer,presets,apps':'timer,presets','timer,apps,presets':'timer,presets',
        'presets,timer,apps':'presets,timer','presets,apps,timer':'presets,timer',
        'apps,timer,presets':'timer,presets','apps,presets,timer':'timer,presets'}
    for raw,expected in migrations.items():
        d.stop(); d.write_prefs('home_navigation',{'order':raw}); d.start()
        tree=d.dump()
        tabs=[n for n in tree.iter('node') if n.get('content-desc','').startswith('首页入口 ')]
        assert {n.get('content-desc') for n in tabs}=={'首页入口 计时','首页入口 预设'}
        actual=','.join('timer' if n.get('content-desc').endswith('计时') else 'presets' for n in sorted(tabs,key=lambda n:d.bounds(n)[0]))
        assert actual==expected and pref_values(d,'home_navigation')['order']==expected,(raw,actual)
        first='计时' if expected.startswith('timer') else '预设'
        assert d.home_tab_node(first).get('selected')=='true'
    assert len(d.records())==before and d.overlay() is None

    print('Unified: actual custom wheels and both launch paths in both modes...',flush=True)
    d.home_tab('计时'); top()
    d.tap('自定义时长')
    assert wheel(d,'分')[1]==1 and wheel(d,'秒')[1]==0
    set_wheel(d,'秒',35); d.tap('确认')
    d.home_tab('预设'); d.home_tab('计时')
    d.tap('自定义时长'); assert wheel(d,'秒')[1]==35; d.tap('取消')
    assert not any(t.startswith('当前配置：') for t in texts()), 'Duplicate App config summary remains'
    d.shot('unified-timer-countdown')
    outcomes=[]
    for mode,label in [('COUNTDOWN','倒计时'),('STOPWATCH','正计时')]:
        top(); d.tap(label)
        d.home_tab('预设'); d.home_tab('计时')
        button='开始'+label
        assert button in texts()
        if mode=='STOPWATCH': assert '自定义时长' not in texts()
        for app in (False,True):
            count=len(d.records()); top()
            d.tap('打开 Gallery 并计时' if app else button,scroll=True)
            row=assert_record(count,mode,'com.android.gallery3d' if app else '',95)
            outcomes.append(dict(mode=mode,entry='app' if app else 'button',id=row['id'],plannedMillis=row['plannedMillis']))
            end()
        if mode=='STOPWATCH': d.shot('unified-timer-stopwatch')

    top(); d.tap('倒计时')
    d.tap('自定义时长'); assert wheel(d,'分')[1]==1 and wheel(d,'秒')[1]==35; d.tap('取消')
    for first in ('button','app'):
        top(); a=point('开始倒计时'); b=point('打开 Gallery 并计时')
        if first=='app': a,b=b,a
        count=len(d.records())
        d.shell('sh','-c',f"'input tap {a[0]} {a[1]}; input tap {b[0]} {b[1]}'")
        row=assert_record(count,'COUNTDOWN','com.android.gallery3d' if first=='app' else '',95)
        time.sleep(.3); assert len(d.records())==count+1
        d.start(); park(); d.home_tab('预设'); d.home_tab('计时')
        d.tap('打开 Gallery 并计时',scroll=True)
        assert len(d.records())==count+1 and d.records()[-1]['id']==row['id']
        end()

    print('Unified: unavailable App, permissions and quota management remain reachable...',flush=True)
    count=len(d.records()); appearance=pref_values(d)
    d.tap('打开 Unavailable 并计时',scroll=True)
    assert '该 App 已卸载、停用或无法打开，请重新选择。' in texts()
    assert len(d.records())==count and d.overlay() is None and pref_values(d)==appearance
    top(); d.tap('开始倒计时'); assert_record(count,'COUNTDOWN','',95); end()
    try:
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','deny')
        time.sleep(.3)
        for action in ('开始倒计时','打开 Gallery 并计时'):
            top(); count=len(d.records()); d.tap(action,scroll=True)
            assert '前往系统设置' in texts() and d.overlay() is None and len(d.records())==count
            d.tap('稍后')
        d.shot('unified-timer-permission')
    finally:
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','allow')
    top(); d.tap('管理（3）',scroll=True); d.tap('每日额度',scroll=True)
    assert any('每日额度' in t for t in texts())
    d.shell('input','keyevent','KEYCODE_BACK'); d.shell('input','keyevent','KEYCODE_BACK')
    assert d.overlay() is None
    return dict(legacyOrdersMigrated=migrations,legacyMigrationVerified=True,twoPagesOnly=True,
                currentConfigShared=outcomes,sharedCurrentConfiguration=True,
                customValueRetained=True,rapidCrossEntrySingleSession=True,activeSessionNotReplaced=True,
                missingAppNoRecordAndCanRetry=True,permissionsGuardBothEntries=True,quotaManagementReachable=True,
                screenshots=['unified-timer-countdown.png','unified-timer-stopwatch.png','unified-timer-permission.png'])
