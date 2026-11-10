"""Real stopwatch/rotation regression. Invoked only inside the isolated backup/restore runner."""
import csv
import io
import json
import re
import struct
import time
from contextlib import contextmanager
from device_controls import fixture, wait_for
from device_preferences import pref_values
from device_recovery import open_recovery, close_recovery, resume_recovery
from device_quota import seed_quota, ledger

PACKAGE='app.floatingfocus'
APP='com.android.gallery3d'

def home(d):
    d.start()
    for _ in range(5):
        if any(n.get('content-desc')=='设置' for n in d.dump().iter('node')): return
        d.shell('input','keyevent','KEYCODE_BACK')
    raise AssertionError('Existing FF home not reachable without clearing the task')

def end(d):
    home(d)
    if d.overlay(): d.shell('input','swipe',*d.center(),int(d.width*.84),int(d.height*.68),400)
    d.tap('结束计时');d.wait_stopped()

def activity(row):
    if 'stopwatch' in row: return row['stopwatch']['activity']
    return dict(spanMillis=row['screenSpanMillis'],intervals=row['activeIntervals'])

def effective_millis(row):
    assert row['mode']=='STOPWATCH'
    if 'stopwatch' not in row:
        assert row['source']=='SCREEN_ON' and row['packageName']==''
    else:
        assert row.get('source')=='STOPWATCH' and row['stopwatch']['behavior'] in ('CONTINUOUS','SCREEN_ON')
    a=activity(row);previous=total=0
    for start,finish in a['intervals']:
        assert 0<=previous<=start<finish<=a['spanMillis']
        total+=finish-start;previous=finish
    assert row['elapsedMillis']==total
    return total

def record(d,id_): return next(r for r in d.records() if r['id']==id_)

def prepare(d,behavior='CONTINUOUS',size=.75):
    fixture(d,seconds=600,c4=False)
    d.stop()
    d.write_prefs('stopwatch_behavior',dict(behavior=behavior))
    d.write_prefs('overlay_positions',{})
    d.write_prefs('c4_settings',dict(enabled=True,sound=False,noticeAcknowledged=True))
    d.write_prefs('reminder_settings',dict(shape='ROUNDED_RECTANGLE',color='BLUE',baseSize=size,
        opacity=.7,intensity=1.,colorEnabled=False,sizeEnabled=False))
    d.start();d.home_tab('计时');d.tap('正计时')

def start(d):
    home(d);d.home_tab('计时');d.tap('正计时');d.tap('开始正计时')
    wait_for(lambda:d.overlay() and d.records()[-1]['status']=='RUNNING','Stopwatch did not start')
    return d.records()[-1]['id']

def choose_behavior(d,label):
    home(d);d.tap('设置');d.tap('正计时方式',scroll=True);d.tap(label)

@contextmanager
def screen_environment(d):
    original={('system',k):d.shell('settings','get','system',k).strip() for k in
              ('screen_off_timeout','user_rotation','accelerometer_rotation','font_scale')}
    original[('secure','lock_screen_lock_after_timeout')]=d.shell('settings','get','secure','lock_screen_lock_after_timeout').strip()
    disabled=d.shell('locksettings','get-disabled').strip()
    night=d.shell('cmd','uimode','night').strip().split()[-1]
    dimensions=d.width,d.height
    try:
        d.shell('input','keyevent','KEYCODE_WAKEUP');d.shell('wm','dismiss-keyguard')
        d.shell('locksettings','set-disabled','false')
        d.shell('settings','put','secure','lock_screen_lock_after_timeout','0')
        d.shell('settings','put','system','screen_off_timeout','600000')
        d.shell('settings','put','system','accelerometer_rotation','0')
        d.shell('settings','put','system','user_rotation','0')
        yield
    finally:
        d.shell('input','keyevent','KEYCODE_WAKEUP');d.shell('wm','dismiss-keyguard')
        for (scope,key),value in original.items():
            d.shell('settings','delete',scope,key) if value=='null' else d.shell('settings','put',scope,key,value)
        d.shell('locksettings','set-disabled',disabled);d.shell('cmd','uimode','night',night)
        d.width,d.height=dimensions

def export_csv(d,name):
    home(d);d.tap('时长记录',scroll=True);d.tap('导出全部记录')
    field=next(n for n in d.dump().iter('node') if n.get('class')=='android.widget.EditText')
    x,y,r,b=d.bounds(field);d.tapxy((x+r)/2,(y+b)/2)
    filename=f'FF-{name}-{int(time.time())}.csv'
    d.shell('input','keyevent',123);d.shell('input','keyevent',*([67]*80));d.shell('input','text',filename)
    d.shell('input','keyevent','KEYCODE_BACK')
    button=next(n for n in d.dump().iter('node') if n.get('text') in ('SAVE','Save','保存') and n.get('enabled')=='true')
    x,y,r,b=d.bounds(button);d.tapxy((x+r)/2,(y+b)/2)
    path='/sdcard/Download/'+filename
    try:
        raw=d.adb('exec-out','cat',path);(d.output/(name+'-export.csv')).write_bytes(raw)
        return list(csv.DictReader(io.StringIO(raw.decode('utf-8-sig'))))
    finally: d.shell('rm','-f',path)

def screen_behavior(d):
    with screen_environment(d):
        prepare(d)
        ids={r['id'] for r in d.records()}
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','deny')
        choose_behavior(d,'随亮屏计时')
        assert d.overlay() is None and {r['id'] for r in d.records()}==ids
        assert pref_values(d,'stopwatch_behavior')['behavior']=='SCREEN_ON'
        d.shot('behavior-preference-no-start-without-overlay-permission')
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','allow')
        id_=start(d);time.sleep(2)
        assert record(d,id_)['stopwatch']['behavior']=='SCREEN_ON'
        home(d);d.shell('input','keyevent','KEYCODE_APP_SWITCH');time.sleep(.7)
        d.shell('input','touchscreen','swipe',d.width//2,int(d.height*.62),d.width//2,80,180);time.sleep(.8)
        assert 'mActivityComponent=app.floatingfocus/.MainActivity' not in d.shell('dumpsys','activity','recents')
        assert d.overlay() and record(d,id_)['status']=='RUNNING','Swiping main task stopped screen mode'
        choose_behavior(d,'息屏仍计时')
        assert record(d,id_)['stopwatch']['behavior']=='SCREEN_ON','Preference changed an active snapshot'
        d.shell('input','keyevent','KEYCODE_SLEEP');time.sleep(1)
        off=record(d,id_);time.sleep(3)
        assert record(d,id_)==off,'Screen-off checkpoint kept advancing'
        d.shell('input','keyevent','KEYCODE_WAKEUP');time.sleep(2)
        policy=d.shell('dumpsys','window','policy')
        (d.output/'screen-on-keyguard.txt').write_text(policy,encoding='utf-8')
        assert re.search(r'KeyguardServiceDelegate\s*\n\s*showing=true',policy),'No actual locked wake'
        current=record(d,id_)
        assert effective_millis(current)>off['elapsedMillis']+1000
        assert len(activity(current)['intervals'])>=2
        d.shell('input','keyevent','KEYCODE_WAKEUP')
        d.shell('wm','dismiss-keyguard')
        d.shell('am','start','-W','-n',APP+'/.app.GalleryActivity');time.sleep(2)
        assert effective_millis(record(d,id_))>current['elapsedMillis']
        choose_behavior(d,'息屏仍计时')
        d.shell('cmd','uimode','night','yes');d.shell('settings','put','system','font_scale','1.5');time.sleep(.7)
        d.shot('behavior-dark-large');d.shell('settings','put','system','user_rotation','1');time.sleep(.8)
        assert record(d,id_)['status']=='RUNNING' and d.overlay()
        assert {r['id'] for r in d.records()}-ids=={id_}
        d.shell('settings','put','system','user_rotation','0');time.sleep(.7)
        d.shell('settings','put','system','font_scale','1.0')
        end(d);wait_for(lambda:record(d,id_)['status']=='MANUAL','Manual end not saved')
        finished=record(d,id_)
        assert effective_millis(finished)<activity(finished)['spanMillis']-2000
        d.shell('input','keyevent','KEYCODE_SLEEP');time.sleep(.5);d.shell('input','keyevent','KEYCODE_WAKEUP')
        d.shell('wm','dismiss-keyguard');assert d.overlay() is None
        choose_behavior(d,'随亮屏计时');screen_source=start(d);time.sleep(2)
        choose_behavior(d,'息屏仍计时');d.stop();saved=record(d,screen_source)
        time.sleep(1);home(d);assert d.overlay() is None
        resume_recovery(d,screen_source)
        wait_for(lambda:any(r.get('stopwatch',{}).get('continuationOf')==screen_source for r in d.records()),'Screen continuation missing')
        resumed=next(r for r in d.records() if r.get('stopwatch',{}).get('continuationOf')==screen_source)
        assert resumed['stopwatch']['behavior']=='SCREEN_ON' and resumed['stopwatch']['carriedMillis']==saved['elapsedMillis']
        d.shell('input','keyevent','KEYCODE_SLEEP');time.sleep(1);paused=record(d,resumed['id']);time.sleep(2)
        assert record(d,resumed['id'])==paused,'Resumed screen behavior followed changed global setting'
        d.shell('input','keyevent','KEYCODE_WAKEUP');d.shell('wm','dismiss-keyguard');end(d)
        # The new preference applies only to the next explicit start.
        continuous=start(d);time.sleep(1)
        before=record(d,continuous)['elapsedMillis'];d.shell('input','keyevent','KEYCODE_SLEEP');time.sleep(3)
        assert record(d,continuous)['elapsedMillis']>before+1000
        d.shell('input','keyevent','KEYCODE_WAKEUP');d.shell('wm','dismiss-keyguard');end(d)
        rows=export_csv(d,'screen-behavior');row=next(r for r in rows if r['记录ID']==id_)
        assert int(row['累计秒数'])==finished['elapsedMillis']//1000
        assert json.loads(row['亮屏活动区间毫秒偏移'])==activity(finished)['intervals']
        return dict(preferenceDoesNotStart=True,nextSessionOnly=True,sameSessionAcrossScreens=True,
            offGapExcluded=True,lockedScreenCounted=True,backgroundContinues=True,manualEndDisarms=True,
            continuousCountsScreenOff=True,configurationRebuildKeepsSession=True,csvActiveAndSpanVerified=True,record=finished)

def timer_lifecycle(d):
    with screen_environment(d):
        prepare(d,behavior='SCREEN_ON')
        d.stop();seed_quota(d,limit=3600000,used=10000)
        appearance=dict(shape='ROUNDED_RECTANGLE',color='BLUE',baseSize=.75,opacity=.7,intensity=1.,colorEnabled=False,sizeEnabled=False)
        preset=dict(id='lifecycle',name='Lifecycle',minutes=15,durationSeconds=900,mode='STOPWATCH',
            targetPackage=APP,targetLabel='Gallery',settings=appearance,c4Enabled=True,stopwatchBehavior='CONTINUOUS')
        d.write_prefs('timer_presets',dict(items=json.dumps([preset])))
        d.start()
        for behavior,save in [('随亮屏计时',False),('随亮屏计时',True),('息屏仍计时',True)]:
            home(d);d.home_tab('预设');d.tap('管理',scroll=True);d.tap('编辑 Lifecycle',scroll=True)
            d.tap(behavior,scroll=True);d.tap('保存' if save else '取消')
            saved=json.loads(pref_values(d,'timer_presets')['items'])[0]
            expected='SCREEN_ON' if behavior=='随亮屏计时' and save else 'CONTINUOUS'
            assert saved['stopwatchBehavior']==expected
            assert pref_values(d,'stopwatch_behavior')['behavior']=='SCREEN_ON','Preset draft changed global behavior'
        d.start();d.home_tab('预设');d.tap('启动预设 Lifecycle',scroll=True)
        wait_for(lambda:d.overlay() and APP in d.foreground(),'Real App/preset did not start')
        parent=d.records()[-1]['id'];time.sleep(2)
        assert record(d,parent)['stopwatch']['behavior']=='CONTINUOUS'
        assert record(d,parent)['packageName']==APP
        home(d)
        before=d.shell('dumpsys','activity','recents')
        assert 'mActivityComponent=app.floatingfocus/.MainActivity' in before,'FF main task missing before swipe'
        d.shell('input','keyevent','KEYCODE_APP_SWITCH');time.sleep(.7);d.shot('recents-before-swipe')
        d.shell('input','touchscreen','swipe',d.width//2,int(d.height*.62),d.width//2,80,180);time.sleep(.8)
        after=d.shell('dumpsys','activity','recents')
        (d.output/'recents-before.txt').write_text(before,encoding='utf-8')
        (d.output/'recents-after.txt').write_text(after,encoding='utf-8')
        assert 'mActivityComponent=app.floatingfocus/.MainActivity' not in after,'Recents gesture did not remove FF task'
        elapsed=record(d,parent)['elapsedMillis'];time.sleep(2)
        assert d.overlay() and record(d,parent)['status']=='RUNNING'
        assert record(d,parent)['elapsedMillis']>elapsed and d.records()[-1]['id']==parent
        d.shot('recents-removed-overlay-kept')
        d.stop();checkpoint=record(d,parent);quota_before=ledger(d)['day']['used'][APP]
        time.sleep(3);home(d)
        wait_for(lambda:record(d,parent)['status']=='INTERRUPTED','Interrupted checkpoint not recovered')
        assert record(d,parent)['elapsedMillis']==checkpoint['elapsedMillis'] and d.overlay() is None
        assert ledger(d)['day']['used'][APP]==quota_before,'Downtime was billed'
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','deny');home(d)
        assert d.overlay() is None and not any(r.get('stopwatch',{}).get('continuationOf')==parent for r in d.records())
        d.shot('recovery-missing-overlay-permission')
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','allow');home(d)
        d.shot('stopwatch-recovery-offer')
        resume_recovery(d,parent,duplicate=True)
        wait_for(lambda:d.overlay() and any(r.get('stopwatch',{}).get('continuationOf')==parent for r in d.records()),'Continuation not persisted')
        children=[r for r in d.records() if r.get('stopwatch',{}).get('continuationOf')==parent]
        assert len(children)==1
        child=children[0]['id'];assert children[0]['stopwatch']['carriedMillis']==checkpoint['elapsedMillis']
        assert children[0]['stopwatch']['behavior']=='CONTINUOUS' and children[0]['packageName']==APP
        assert PACKAGE in d.foreground(),'Continuation unexpectedly opened target App'
        assert children[0]['stopwatch']['presetId']=='lifecycle'
        time.sleep(3);current=record(d,child);effective_millis(current)
        charged=ledger(d)['day']['used'][APP]-quota_before
        assert abs(charged-current['elapsedMillis'])<2000,(charged,current)
        d.shot('stopwatch-resumed-carried-clock')
        end(d);wait_for(lambda:record(d,child)['status']=='MANUAL','Continuation normal end not saved')
        final=record(d,child)
        assert record(d,parent)['elapsedMillis']==checkpoint['elapsedMillis'],'Parent record rewritten'
        rows=export_csv(d,'stopwatch-recovery');row=next(r for r in rows if r['记录ID']==child)
        assert row['续计来源ID']==parent and row['续计根ID']==parent
        assert int(row['累计秒数'])==final['elapsedMillis']//1000
        assert int(row['承接累计秒数'])==checkpoint['elapsedMillis']//1000
        assert json.loads(row['运行活动区间毫秒偏移'])==activity(final)['intervals']
        log=d.shell('logcat','-d','-s','FloatingFocus:*')
        assert 'C4 audio play=' not in log,'Independent stopwatch created C4 audio'
        (d.output/'lifecycle.log').write_text(log,encoding='utf-8')
        d.stop()
        bad=json.loads(json.dumps(checkpoint));bad['id']='lifecycle-malformed'
        bad['stopwatch']['activity']=dict(spanMillis=10000,intervals=[[0,6000],[5000,9000]])
        bad['elapsedMillis']=10000
        d.adb('shell','run-as',PACKAGE,'tee','files/timer_records/lifecycle-malformed.json',data=json.dumps(bad).encode())
        home(d);open_recovery(d)
        assert any('进度读取失败' in n.get('text','') for n in d.dump().iter('node'))
        d.shot('recovery-malformed-checkpoint-error')
        d.shell('run-as',PACKAGE,'rm','-f','files/timer_records/lifecycle-malformed.json')
        from device_interactions import tap_control
        tap_control(d,'重试读取')
        wait_for(lambda:not any('进度读取失败' in n.get('text','') for n in d.dump().iter('node')),'Retry did not clear read failure')
        close_recovery(d)
        # Recovery must remain reachable independently of today's/week/month chart filters.
        d.stop()
        for index in (1,2):
            old=json.loads(json.dumps(checkpoint));old['id']=f'lifecycle-old-{index}'
            old['appName']=f'OldRecover{index}';old['startedWallMillis']=checkpoint['startedWallMillis']-70*86400000
            old['status']='INTERRUPTED'
            d.adb('shell','run-as',PACKAGE,'tee',f'files/timer_records/lifecycle-old-{index}.json',data=json.dumps(old).encode())
        home(d);d.tap('时长记录',scroll=True)
        recovery_entry=next(n.get('text') for n in d.dump().iter('node') if n.get('text','').startswith('全部中断正计时（'))
        d.tap(recovery_entry)
        visible=[n.get('text') for n in d.dump().iter('node')]
        assert 'OldRecover1' in visible and 'OldRecover2' in visible,'Older independent candidates unreachable'
        d.shot('older-recovery-candidates-independent-of-chart-range')
        def origin_case(deleted):
            d.stop()
            source_preset=dict(preset,id='recovery-origin',name='RecoveryOrigin',c4Enabled=False,
                stopwatchBehavior='CONTINUOUS',positions=dict(portrait=dict(x=.3,y=.25),landscape=dict(x=.7,y=.25)))
            d.write_prefs('timer_presets',dict(items=json.dumps([source_preset])))
            normal_before=pref_values(d,'overlay_positions')
            d.start();d.home_tab('预设');d.tap('启动预设 RecoveryOrigin',scroll=True)
            wait_for(lambda:d.overlay() and APP in d.foreground(),'Recovery origin did not start')
            origin=d.records()[-1]['id'];time.sleep(1.3);d.stop()
            saved=record(d,origin)
            edited=dict(source_preset,targetPackage='com.android.settings',targetLabel='Settings')
            d.write_prefs('timer_presets',dict(items=json.dumps([] if deleted else [edited])))
            home(d);resume_recovery(d,origin)
            wait_for(lambda:d.overlay() and any(r.get('stopwatch',{}).get('continuationOf')==origin for r in d.records()),
                'Changed/deleted origin did not resume')
            resumed=next(r for r in d.records() if r.get('stopwatch',{}).get('continuationOf')==origin)
            assert resumed['packageName']==APP and PACKAGE in d.foreground(),'Continuation followed edited target or opened an App'
            assert resumed['stopwatch']['presetId']=='recovery-origin'
            assert resumed['stopwatch']['carriedMillis']==saved['elapsedMillis']
            d.shell('input','swipe',*d.center(),int(d.width*.68),int(d.height*.45),450);time.sleep(1.3)
            assert pref_values(d,'overlay_positions')==normal_before,'Recovery cross-wrote ordinary position table'
            presets=json.loads(pref_values(d,'timer_presets')['items'])
            if deleted:
                assert presets==[],'Continuation resurrected deleted preset'
                assert record(d,resumed['id'])['stopwatch']['positions']['portrait']!=source_preset['positions']['portrait']
            else:
                assert presets[0]['targetPackage']=='com.android.settings','Continuation rewrote edited binding'
                assert presets[0]['positions']['portrait']!=source_preset['positions']['portrait']
            d.shot('recovery-deleted-origin' if deleted else 'recovery-edited-origin')
            end(d)
        origin_case(False);origin_case(True)
        return dict(taskActuallyRemoved=True,overlayAndSameSessionSurvive=True,presetBehaviorSnapshot=True,
            noAutomaticRecovery=True,noDowntime=True,oneChildAfterDoubleTap=True,parentUnchanged=True,
            resumedBehaviorSnapshot=True,appBindingPreserved=True,quotaOnlyNewSegment=True,csvRelationVerified=True,
            presetDraftSaveCancel=True,missingPermissionNoConsumption=True,malformedCheckpointShowsError=True,
            olderCandidatesReachable=True,editedOriginKeepsOriginalApp=True,deletedOriginNoCrossWrite=True,
            parent=checkpoint,child=final,quotaBefore=quota_before)

def overlay_rotation(d):
    results=[]
    with screen_environment(d):
        def rotate(n):
            d.shell('logcat','-c');d.shell('wm','user-rotation','lock',str(n))
            expected_land=n==1
            def resized():
                raw=d.adb('exec-out','screencap','-p');w,h=struct.unpack('>II',raw[16:24])
                d.width,d.height=w,h
                return (w>h)==expected_land
            wait_for(resized,'Display did not rotate');time.sleep(.6)
        def sample(name,compact=False):
            time.sleep(.25)
            raw=d.adb('exec-out','screencap','-p');actual=struct.unpack('>II',raw[16:24])
            assert actual==(d.width,d.height),('Display changed after rotation precondition',name,actual,(d.width,d.height))
            log=d.shell('logcat','-d','-s','FloatingFocus:*')
            hits=re.findall(r'role=time bounds=([-\d.,]+)',log);assert hits,'No real time geometry'
            bounds=list(map(float,hits[-1].split(',')));frame=d.frame()
            if compact:
                assert abs((bounds[0]+bounds[2])/2-(frame[2]-frame[0])/2)<=2,(name,frame,bounds)
                assert abs((bounds[1]+bounds[3])/2-(frame[3]-frame[1])/2)<=2,(name,frame,bounds)
            d.shot(name);(d.output/(name+'.log')).write_text(log,encoding='utf-8')
            return dict(name=name,frame=frame,time=bounds)
        # A direction with no completed drag must retain its first-session default.
        rotate(0);fixture(d,seconds=600,c4=False);d.stop()
        d.write_prefs('overlay_positions',{})
        d.write_prefs('reminder_settings',dict(shape='ROUNDED_RECTANGLE',color='BLUE',baseSize=1.,opacity=.7,
            intensity=1.,colorEnabled=False,sizeEnabled=False))
        d.start();d.home_tab('计时');rotate(0)
        d.tap('开始倒计时');wait_for(lambda:d.overlay(),'No default overlay')
        default_before=sample('default-portrait-before',True)
        rotate(1);sample('default-landscape-before',True)
        d.tapxy(*d.center());expanded=sample('default-landscape-expanded')
        x,y,_,_=expanded['frame'];a,b,c,e=expanded['time']
        d.shell('input','swipe',int(x+(a+c)/2),int(y+(b+e)/2),int(d.width*.8),int(d.height*.3),450)
        rotate(0);expanded=sample('default-portrait-expanded-return')
        x,y,_,_=expanded['frame'];a,b,c,e=expanded['time'];d.tapxy(x+(a+c)/2,y+(b+e)/2)
        default_after=sample('default-portrait-after',True)
        assert max(abs(a-b) for a,b in zip(default_before['frame'],default_after['frame']))<=2,(default_before,default_after)
        saved=json.loads(pref_values(d,'overlay_positions')['positions'])
        assert 'portrait' not in saved,'Runtime default must not be persisted as a user drag'
        d.stop()
        for shape in ('ROUNDED_RECTANGLE','VERTICAL_CAPSULE','CIRCLE'):
            for size in (.75,1.25):
                rotate(0);fixture(d,seconds=600,c4=False,shape=shape);d.stop()
                d.write_prefs('overlay_positions',{})
                d.write_prefs('reminder_settings',dict(shape=shape,color='BLUE',baseSize=size,opacity=.7,intensity=1.,colorEnabled=False,sizeEnabled=False))
                # Launcher can force portrait while the resumed FF task still
                # restores its landscape configuration. Establish the test's
                # direction after FF is foreground, before any timer/drag.
                d.start();d.home_tab('计时');rotate(0)
                d.tap('开始倒计时');wait_for(lambda:d.overlay(),'No overlay')
                tag=f'{shape}-{size}'
                d.shell('input','swipe',*d.center(),int(d.width*.32),int(d.height*.28),400);time.sleep(.3)
                target=int(d.width*.4),int(d.height*.3)
                d.shell('input','swipe',*d.center(),*target,450);time.sleep(.3)
                assert max(abs(a-b) for a,b in zip(d.center(),target))<=3,'Remeasure snapped second drag to old slot'
                portrait=sample(tag+'-portrait-before',True)
                rotate(1)
                d.shell('input','swipe',*d.center(),int(d.width*.76),int(d.height*.32),400);time.sleep(.3)
                landscape=sample(tag+'-landscape-before',True)
                d.tapxy(*d.center());sample(tag+'-landscape-expanded')
                rotate(0);expanded=sample(tag+'-portrait-expanded-return')
                x,y,_,_=expanded['frame'];a,b,c,e=expanded['time'];d.tapxy(x+(a+c)/2,y+(b+e)/2)
                after=sample(tag+'-portrait-after',True)
                assert max(abs(a-b) for a,b in zip(portrait['frame'],after['frame']))<=2,(portrait,after)
                rotate(1);land_after=sample(tag+'-landscape-after',True)
                assert max(abs(a-b) for a,b in zip(landscape['frame'],land_after['frame']))<=2,(landscape,land_after)
                saved=json.loads(pref_values(d,'overlay_positions')['positions'])
                assert 'portrait' in saved and 'landscape' in saved
                results.append(dict(shape=shape,size=size,portrait=portrait,after=after,landscape=landscape,landscapeAfter=land_after,positions=saved))
                d.stop()
        rotate(0);fixture(d,seconds=600,c4=False);d.stop()
        normal_before=d.adb('shell','run-as',PACKAGE,'cat','shared_prefs/overlay_positions.xml')
        appearance=dict(shape='ROUNDED_RECTANGLE',color='BLUE',baseSize=.75,opacity=.7,intensity=1.,colorEnabled=False,sizeEnabled=False)
        first=dict(id='direction-one',name='DirectionOne',minutes=10,durationSeconds=600,mode='COUNTDOWN',targetPackage=APP,
            targetLabel='Gallery',settings=appearance,c4Enabled=False,positionX=.32,positionY=.25)
        second=dict(first,id='direction-two',name='DirectionTwo',positionX=.8,positionY=.25)
        d.write_prefs('timer_presets',dict(items=json.dumps([first,second])))
        d.start();d.home_tab('预设');rotate(0);d.tap('启动预设 DirectionOne',scroll=True)
        wait_for(lambda:d.overlay() and APP in d.foreground(),'Preset position fixture failed')
        sample('preset-initial-portrait',True)
        d.shell('input','swipe',*d.center(),int(d.width*.35),int(d.height*.28),450);time.sleep(.3)
        preset_portrait=d.frame();rotate(1)
        current=json.loads(pref_values(d,'timer_presets')['items'])
        assert 'landscape' not in current[0]['positions'],'Missing direction inherited legacy twice'
        d.shell('input','swipe',*d.center(),int(d.width*.75),int(d.height*.3),450);time.sleep(.3)
        rotate(0)
        assert max(abs(a-b) for a,b in zip(preset_portrait,d.frame()))<=2,'Preset portrait was overwritten'
        current=json.loads(pref_values(d,'timer_presets')['items']);assert current[1]['positions']=={}
        first_positions=current[0]['positions'];assert {'portrait','landscape'}<=first_positions.keys()
        d.stop();d.start();d.home_tab('预设');rotate(0);d.tap('启动预设 DirectionTwo',scroll=True)
        wait_for(lambda:d.overlay() and APP in d.foreground(),'Second preset did not start')
        assert abs(d.center()[0]-(preset_portrait[0]+preset_portrait[2])/2)>100,'Preset positions crossed IDs'
        assert json.loads(pref_values(d,'timer_presets')['items'])[0]['positions']==first_positions
        assert normal_before==d.adb('shell','run-as',PACKAGE,'cat','shared_prefs/overlay_positions.xml'),'Preset changed ordinary positions'
        d.stop()
        (d.output/'rotation-cases.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
        return dict(cases=results,compactCentered=True,expandedToCompactAfterRotation=True,independentPositions=True,
                    unsavedDefaultStable=True,defaultBefore=default_before,defaultAfter=default_after,
                    presetsIsolated=True,legacyMigrationOnce=True,ordinaryTableUnchanged=True,
                    note='Actual native frames and Compose bounds; not physical-device flicker/audio evidence')
