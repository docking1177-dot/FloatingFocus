"""Stopwatch cycles and HH rendering via real service/checkpoints/Launcher; isolated runner only."""
import json
import re
import struct
import subprocess
import time
from device_controls import fixture, wait_for
from device_preferences import pref_values
from device_recovery import resume_recovery
from device_timer_lifecycle import home, end, screen_environment, record, effective_millis
from device_widgets import add_test_widget, remove_test_widgets, find_desktop_widget, require_empty_widgets

PACKAGE='app.floatingfocus'
LEVELS=('primary','middle','high')

def rules(enabled=True):
    return {k:dict(enabled=enabled,intervalSeconds=60*(i+1),effects=e,
        color=dict(mode='NONE',a=0xff4285f4-2**32,b=0xffffc857-2**32)) for i,(k,e) in enumerate(zip(LEVELS,
        ['BRIGHTEN','OUTLINE','BRIGHTEN,STATUS,VIBRATION,SOUND']))}

def logs(d):return d.shell('logcat','-d','-s','FloatingFocus:*')

def setup(d, value=None):
    fixture(d,seconds=600,c4=False);d.stop()
    d.write_prefs('stopwatch_reminders',dict(rules=json.dumps(value if value is not None else rules()),allow_sound=False,allow_vibration=False))
    d.write_prefs('stopwatch_behavior',dict(behavior='CONTINUOUS'))
    d.write_prefs('overlay_positions',{})
    d.write_prefs('quick_durations',dict(seconds='300,600,900,1800',startup_mode='STOPWATCH',startup_seconds=600))
    d.write_prefs('reminder_settings',dict(shape='ROUNDED_RECTANGLE',color='BLUE',baseSize=.75,opacity=.7,
        intensity=1.,colorEnabled=False,sizeEnabled=False))
    d.start();d.home_tab('计时');d.tap('正计时')

def resumed(d, base, name, value=None, behavior='CONTINUOUS',shape='ROUNDED_RECTANGLE',size=.75):
    d.stop()
    at=int(d.shell('date','+%s').strip())*1000
    row=dict(id=name,appName=name,packageName='',mode='STOPWATCH',plannedMillis=0,startedWallMillis=at-base,
        elapsedMillis=base,status='INTERRUPTED',source='STOPWATCH',stopwatch=dict(version=1,behavior=behavior,
        carriedMillis=0,activity=dict(spanMillis=base,intervals=[[0,base]]),
        positions=dict(portrait=dict(x=.55,y=.28),landscape=dict(x=.65,y=.3)),
        stopwatchReminders=value if value is not None else rules()))
    d.adb('shell','run-as',PACKAGE,'tee',f'files/timer_records/{name}.json',data=json.dumps(row).encode())
    d.write_prefs('reminder_settings',dict(shape=shape,color='BLUE',baseSize=size,opacity=.7,intensity=1.,colorEnabled=False,sizeEnabled=False))
    home(d);d.shell('logcat','-c');resume_recovery(d,name)
    wait_for(lambda:d.overlay() and any(r.get('stopwatch',{}).get('continuationOf')==name for r in d.records()),'No unique linked resume')
    child=next(r for r in d.records() if r.get('stopwatch',{}).get('continuationOf')==name)
    assert child['stopwatch']['carriedMillis']==base
    return child['id']

def reminders(d):
    return re.findall(r'Stopwatch reminder level=(\w+) elapsed=(\d+) effects=\[([^\]]*)\]',logs(d))

def wait_level(d,level,timeout=80):
    wait_for(lambda:any(x[0]==level for x in reminders(d)),f'No {level} cycle',timeout=timeout)
    found=reminders(d);print('Actual cycles:',found,flush=True);return found

def park(d):
    if d.overlay():d.shell('input','swipe',*d.center(),int(d.width*.86),int(d.height*.82),450)

def tap_setting(d,text):
    # The pinned preview leaves a shorter scrolling viewport than the default
    # full-page runner. Use overlapping short swipes so a row is never skipped.
    for _ in range(18):
        for node in d.dump().iter('node'):
            if text in (node.get('text'),node.get('content-desc')):
                x,y,r,b=d.bounds(node)
                if r>x and b>y:
                    d.tapxy((x+r)//2,(y+b)//2);return
        d.shell('input','swipe',d.width//2,int(d.height*.85),d.width//2,int(d.height*.65),300)
    raise AssertionError('Settings control not found in scrolling viewport: '+text)

def stopwatch_reminders(d):
    with screen_environment(d):
        setup(d)
        before_ids={r['id'] for r in d.records()}
        d.tap('设置');d.tap('正计时提醒',scroll=True)
        d.shot('stopwatch-reminders-settings-light')
        for name in ('初级提醒','中级提醒','高级提醒'):
            d.tap(name);d.tap('预览视觉',scroll=True)
            assert d.overlay() is None and {r['id'] for r in d.records()}==before_ids,'Preview started a real timer'
            d.tap('返回');d.tap('正计时提醒',scroll=True)
        d.tap('初级提醒');d.tap('累计运行间隔',scroll=True)
        from device_preferences import set_wheel
        set_wheel(d,'分',2);set_wheel(d,'秒',5);d.tap('确认')
        saved=json.loads(pref_values(d,'stopwatch_reminders')['rules'])
        assert saved['primary']['intervalSeconds']==125
        d.tap('返回');d.tap('返回');d.home_tab('预设');d.tap('保存当前');d.tap('Gallery');d.tap('保存')
        items=json.loads(pref_values(d,'timer_presets')['items']);p=items[-1]
        assert p['mode']=='STOPWATCH' and p['stopwatchReminders']==saved
        for save in (False,True):
            d.tap('管理',scroll=True);d.tap('编辑 '+p['name'],scroll=True)
            tap_setting(d,'启用初级提醒');d.tap('保存' if save else '取消')
            current=json.loads(pref_values(d,'timer_presets')['items'])[-1]
            assert current['stopwatchReminders']['primary']['enabled']==(not saved['primary']['enabled'] if save else saved['primary']['enabled'])
            assert json.loads(pref_values(d,'stopwatch_reminders')['rules'])==saved,'Preset draft changed current rules'
        # Launch the real saved preset with different current rules, then inspect
        # the service checkpoint. Saving a snapshot alone does not prove launch.
        p=json.loads(pref_values(d,'timer_presets')['items'])[-1]
        home(d);d.home_tab('预设');d.tap(p['name'])
        wait_for(lambda:d.overlay() and d.records()[-1]['packageName']=='com.android.gallery3d','Preset did not launch Gallery')
        assert 'com.android.gallery3d' in d.foreground()
        assert d.records()[-1]['stopwatch']['stopwatchReminders']==p['stopwatchReminders']
        assert json.loads(pref_values(d,'stopwatch_reminders')['rules'])==saved
        end(d)
        # A genuine regular start snapshots current rules, not future changes.
        home(d);d.home_tab('计时');d.tap('正计时');d.tap('开始正计时')
        wait_for(lambda:d.overlay(),'Regular stopwatch did not start');time.sleep(.5)
        current_id=d.records()[-1]['id'];snapshot=record(d,current_id)['stopwatch']['stopwatchReminders']
        assert snapshot==saved
        park(d);home(d);d.tap('设置');d.tap('正计时提醒',scroll=True)
        d.tap('初级提醒');d.tap('启用初级提醒')
        assert record(d,current_id)['stopwatch']['stopwatchReminders']==snapshot,'Active session followed global edit'
        end(d)
        # One actual native widget owns manual pause/resume. Empty-host guard precedes any addition.
        require_empty_widgets(d.shell('dumpsys','appwidget'))
        try:
            add_test_widget(d,1,1)
            root=find_desktop_widget(d)
            control=next(n for n in root.iter('node') if n.get('resource-id')==PACKAGE+':id/widget_primary')
            x,y,r,b=d.bounds(control);point=((x+r)//2,(y+b)//2)
            # Recovery explicitly retains source rules despite a different current configuration.
            d.stop();d.write_prefs('stopwatch_reminders',dict(rules=json.dumps(rules(False)),allow_sound=False,allow_vibration=False))
            id_=resumed(d,58000,'sw-cycle-real')
            assert record(d,id_)['stopwatch']['stopwatchReminders']==rules()
            wait_level(d,'PRIMARY')
            park(d);d.shell('input','keyevent','KEYCODE_HOME');find_desktop_widget(d)
            d.tapxy(*point);time.sleep(.5)
            paused=record(d,id_);time.sleep(4)
            assert record(d,id_)['elapsedMillis']==paused['elapsedMillis'],'Manual pause still accrued time'
            count=len(reminders(d));time.sleep(2);assert len(reminders(d))==count
            d.shot('stopwatch-native-widget-paused')
            d.tapxy(*point);wait_level(d,'MIDDLE')
            wait_level(d,'HIGH')
            hits=reminders(d);assert [v[0] for v in hits]==['PRIMARY','MIDDLE','HIGH'],hits
            assert all('SOUND' not in v[2] and 'VIBRATION' not in v[2] for v in hits),'Disabled audio leaked'
            assert all(abs(int(v[1])-boundary)<2500 for v,boundary in zip(hits,[60000,120000,180000])),hits
            (d.output/'stopwatch-cycle.log').write_text(logs(d),encoding='utf-8')
            end(d)
        finally:remove_test_widgets(d)
        # Screen-driven pauses freeze cumulative thresholds as well.
        id_=resumed(d,56000,'sw-cycle-screen',behavior='SCREEN_ON')
        d.shell('input','keyevent','KEYCODE_SLEEP');time.sleep(.7);paused=record(d,id_);time.sleep(5)
        assert record(d,id_)['elapsedMillis']==paused['elapsedMillis'] and not reminders(d)
        d.shell('input','keyevent','KEYCODE_WAKEUP');d.shell('wm','dismiss-keyguard');wait_level(d,'PRIMARY',timeout=10)
        assert len(reminders(d))==1
        end(d)
        # At an already passed exact threshold there is no replay, including after restart.
        id_=resumed(d,180000,'sw-cycle-past');time.sleep(2)
        assert reminders(d)==[],'Restored exact deadline was replayed'
        end(d)
        # Explicit audition is an API request only; do not claim heard/felt output.
        home(d);d.tap('设置');d.tap('正计时提醒',scroll=True)
        d.tap('允许短提示音',scroll=True);d.tap('允许周期震动',scroll=True)
        d.shell('logcat','-c');d.tap('试听提示音',scroll=True);d.tap('测试震动',scroll=True)
        (d.output/'explicit-audition-request.log').write_text(logs(d),encoding='utf-8')
        d.shot('stopwatch-audition-permissions')
        d.tap('允许短提示音',scroll=True);d.tap('允许周期震动',scroll=True)
        d.shell('cmd','uimode','night','yes');d.shell('settings','put','system','font_scale','1.5')
        home(d);d.tap('设置');d.tap('正计时提醒',scroll=True)
        d.shot('stopwatch-reminders-settings-dark-large-font')
        return dict(previewNoSession=True,wheelPersisted=True,saveCurrentSnapshot=True,presetSaveCancelIndependent=True,
            realPresetUsesSavedRules=True,
            activeRulesStable=True,actualIndependentCycles=True,simultaneousHighestOnly=True,manualPauseFreezes=True,
            sourceRulesOnRecovery=True,screenPauseFreezes=True,pastThresholdNotReplayed=True,audioOffRespected=True,
            explicitAuditionRequested=True,note='Native API requests are not actual listening/vibration proof')

def hour_window_sample(d):
    # Bracket the latest Compose time bounds with two native frame reads. A
    # boundary update between them is kept as incoherent evidence, never used
    # to assert a global anchor from mismatched coordinate generations.
    # A cleared log has no sample until the next genuine running tick.
    wait_for(lambda: 'role=time bounds=' in logs(d), 'No actual time-area geometry during hour sampling', timeout=4)
    before=d.overlay();assert before,'Hour sample lost its native window'
    log=logs(d)
    hits=re.findall(r'role=time bounds=([-\d.,]+) stopwatchElapsedMillis=(\d+)',log)
    assert hits,'No actual time-area geometry during hour sampling'
    bounds,elapsed=hits[-1]
    after=d.overlay();assert after,'Hour sample lost its native window'
    return dict(at=time.monotonic(),window=after[0],frame=after[1],
        beforeFrame=before[1],coherent=before==after,
        bounds=tuple(map(float,bounds.split(','))),elapsed=int(elapsed))


def assert_hour_transition(samples,boundary,tag):
    assert samples and len({s['window'] for s in samples})==1,('Native window replaced',tag)
    coherent=[s for s in samples if s['coherent']]
    pre=[s for s in coherent if s['elapsed']<boundary]
    post=[s for s in coherent if s['elapsed']>=boundary]
    assert pre and post,('Sampling did not cross the actual format boundary',tag,samples)
    for label,group in [('before',pre),('after',post)]:
        assert len({s['elapsed']//1000 for s in group})>=2,('Not enough actual running ticks within format',tag,label)
        assert len({tuple(s['frame']) for s in group})==1,('Window unstable within time format',tag,label,group)
        assert len({tuple(s['bounds']) for s in group})==1,('Time slot unstable within time format',tag,label,group)
    old,new=pre[0],post[0]
    assert new['bounds'][2]-new['bounds'][0] > old['bounds'][2]-old['bounds'][0],('Format did not grow its time slot',tag)
    # Expanded controls or minimum-shape dimensions can already dominate the
    # width. Native frame may remain the same, or grow exactly once; it may not
    # bounce, shrink or repeatedly remeasure during ordinary ticks.
    unique=[]
    for sample in samples:
        frame=tuple(sample['frame'])
        if not unique or frame!=unique[-1]:unique.append(frame)
    assert len(unique)<=2,('Native frame bounced/repeatedly changed at format boundary',tag,unique)
    for axis in (0,1):
        assert new['frame'][axis+2]-new['frame'][axis] >= old['frame'][axis+2]-old['frame'][axis],('Format growth shrank native frame',tag)
    def center(s):
        x,y,_,_=s['frame'];l,t,r,b=s['bounds']
        return x+(l+r)/2,y+(t+b)/2
    anchor=center(old)
    assert all(max(abs(a-b) for a,b in zip(center(s),anchor))<=2 for s in coherent),('Global time anchor moved at format growth',tag,anchor,[center(s) for s in coherent])
    return dict(stableWithinFormat=True,singleTimeSlotGrowth=True,nativeFrameChanges=len(unique)-1,
        globalTimeAnchor=anchor,window=old['window'],before=old,after=new,samples=samples)


def stopwatch_hours(d):
    results=[]
    with screen_environment(d):
        setup(d,rules(False))
        def rotate(n):
            d.shell('wm','user-rotation','lock',str(n))
            def ready():
                raw=d.adb('exec-out','screencap','-p');d.width,d.height=struct.unpack('>II',raw[16:24])
                return (d.width>d.height)==(n==1)
            wait_for(ready,'Hours display did not rotate');time.sleep(.5)
        for index,(shape,expanded,boundary) in enumerate([
            ('ROUNDED_RECTANGLE',False,3600000),('ROUNDED_RECTANGLE',True,3600000),
            ('CIRCLE',False,3600000),('VERTICAL_CAPSULE',True,3600000),('ROUNDED_RECTANGLE',True,360000000)]):
            home(d);rotate(0)
            id_=resumed(d,boundary-15000,f'sw-hours-{index}',rules(False),shape=shape)
            # Center before expansion/sampling so safe-area clamping cannot masquerade as an anchor regression.
            d.shell('input','swipe',*d.center(),d.width//2,int(d.height*.45),450)
            if expanded:d.tapxy(*d.center());time.sleep(.4)
            tag=f'hours-{index}-{shape}-{expanded}'
            d.shell('logcat','-c')
            d.shot(tag+'-before')
            movie=subprocess.Popen([d.exe,'-s',d.serial,'shell','screenrecord','--time-limit','22','--size','540x1170','/sdcard/ff-stopwatch-hours.mp4'],
                stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
            samples=[];until=time.monotonic()+22
            while time.monotonic()<until:
                samples.append(hour_window_sample(d));time.sleep(.15)
            _,err=movie.communicate(timeout=15);assert movie.returncode==0,err
            (d.output/(tag+'.mp4')).write_bytes(d.adb('exec-out','cat','/sdcard/ff-stopwatch-hours.mp4'))
            d.shell('rm','-f','/sdcard/ff-stopwatch-hours.mp4')
            log=logs(d)
            values=[int(n) for n in re.findall(r'role=time bounds=[-\d.,]+ stopwatchElapsedMillis=(\d+)',log)]
            assert values and min(values)<boundary<=max(values),(tag,values)
            assert 'Stopwatch text=' in log and 'overflow=true' not in log,('Hour text overflow or missing real text probe',tag)
            transition=assert_hour_transition(samples,boundary,tag)
            d.shot(tag+'-after');(d.output/(tag+'.log')).write_text(log,encoding='utf-8')
            notice=d.shell('dumpsys','notification','--noredact')
            entry=next(p for p in notice.split('NotificationRecord(') if 'pkg=app.floatingfocus' in p and 'id=1 ' in p)
            expected='01:00:' if boundary==3600000 else '100:00:'
            assert expected in entry and 'android.showChronometer=true' not in entry,'Notification did not use the unified reading'
            (d.output/(tag+'-notification.txt')).write_text(entry,encoding='utf-8')
            rotate(1);time.sleep(.4);d.shot(tag+'-landscape');rotate(0)
            assert effective_millis(record(d,id_))>0
            results.append(dict(shape=shape,expanded=expanded,boundary=boundary,transition=transition,
                minimumElapsed=min(values),maximumElapsed=max(values),notificationReading=expected))
            end(d)
        id_=resumed(d,3600000000,'sw-hours-1000',rules(False),shape='CIRCLE')
        d.shot('hours-1000-circle');park(d)
        require_empty_widgets(d.shell('dumpsys','appwidget'))
        try:
            add_test_widget(d,2,1);root=find_desktop_widget(d)
            clocks=[n.get('text','') for n in root.iter('node') if n.get('resource-id')==PACKAGE+':id/widget_clock']
            assert clocks and all(t.startswith('1000:00:') for t in clocks),clocks
            d.shot('hours-1000-native-widget')
        finally:remove_test_widgets(d)
        # The 01 hour padding must also be checked on the real native clock.
        id_=resumed(d,3600000,'sw-hours-widget-one',rules(False));park(d)
        try:
            add_test_widget(d,1,1);root=find_desktop_widget(d)
            clocks=[n.get('text','') for n in root.iter('node') if n.get('resource-id')==PACKAGE+':id/widget_clock']
            assert clocks and all(t.startswith('01:00:') for t in clocks),clocks
            d.shot('hours-one-native-widget')
        finally:remove_test_widgets(d)
        # A real Text layout assertion, not an assumption based on the Kotlin
        # formatter. Keep a very long-hour/double-font stress case for the former
        # 240dp cap, and restore environment through screen_environment.
        d.shell('settings','put','system','font_scale','2.0')
        resumed(d,360000000000,'sw-hours-font-stress',rules(False),shape='ROUNDED_RECTANGLE')
        time.sleep(.7);text_log=logs(d)
        assert 'Stopwatch text=' in text_log and 'overflow=true' not in text_log,'Long stopwatch time clipped'
        d.shot('hours-large-font-full-reading');(d.output/'hours-large-font-text.log').write_text(text_log,encoding='utf-8')
        end(d)
        (d.output/'stopwatch-hour-cases.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
        return dict(actualHourRollover=True,actualHundredHourRollover=True,stableWithinFormat=True,singleTimeSlotGrowth=True,preservedGlobalTimeAnchor=True,
            notificationPadded=True,nativeWidgetPadded=True,thousandHoursNoDayWrap=True,largeFontNoTextOverflow=True,rotations=True,cases=results)
