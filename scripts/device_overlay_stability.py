"""Temporal window and Compose bounds, not screenshot-only jitter acceptance."""
import json
import re
import subprocess
import time
from device_controls import fixture, key_point, wait_for
from device_preferences import set_wheel

PACKAGE='app.floatingfocus'


def overlay_stability(d):
    results=[]
    anchors=[]
    def time_rect():
        log=d.shell('logcat','-d','-s','FloatingFocus:*')
        hits=re.findall(r'role=time bounds=([-\d.,]+)',log)
        assert hits,'No native time-area geometry'
        a,b,c,e=map(float,hits[-1].split(','));x,y,_,_=d.frame()
        return x+a,y+b,x+c,y+e
    def anchor_check(edge):
        x,y,r,b=time_rect()
        d.shell('input','swipe',int((x+r)/2),int((y+b)/2),int(d.width*.48),int(d.height*edge),400)
        time.sleep(.3)
        compact=d.frame();old=time_rect();point=((old[0]+old[2])/2,old[1]+(old[3]-old[1])*.7)
        d.tapxy(*point)
        wait_for(lambda:d.frame()[3]-d.frame()[1]>compact[3]-compact[1],'Time tap did not expand')
        expanded=d.frame();current=time_rect()
        assert max(abs(a-b) for a,b in zip(old,current))<=2,('Time anchor moved',edge,old,current)
        d.tapxy(*point)
        wait_for(lambda:all(abs(a-b)<=2 for a,b in zip(d.frame(),compact)),'Original time did not collapse to its anchor')
        assert d.records()[-1]['status']=='RUNNING','Original time tap accidentally ended the timer'
        anchors.append(dict(edge=edge,compact=compact,expanded=expanded,timeBefore=old,timeExpanded=current))
    def start(seconds, c4=False):
        fixture(d, seconds=seconds, c4=c4)
        d.home_tab('计时')
        d.shell('logcat','-c')
        d.tap('开始倒计时')
        wait_for(lambda:d.overlay() and d.frame()[2]-d.frame()[0]>40 and d.frame()[3]-d.frame()[1]>40,
                 'Overlay has not received its first measured frame')
        time.sleep(.25)  # Native touch-region attachment follows the first measured frame.
    def sample(name, seconds=7, simulation=False):
        start_record=None if simulation else d.records()[-1]
        before_log=d.shell('logcat','-d','-s','FloatingFocus:*')
        (d.output/(name+'-before.log')).write_text(before_log,encoding='utf-8')
        d.shell('logcat','-c')
        remote='/sdcard/ff-overlay-stability.mp4'
        w,h=(d.width//4*2,d.height//4*2)
        movie=subprocess.Popen([d.exe,'-s',d.serial,'shell','screenrecord','--time-limit',str(seconds),
            '--size',f'{w}x{h}',remote],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
        samples=[]; until=time.monotonic()+seconds
        while time.monotonic()<until:
            frame=d.overlay()
            assert frame,'Window disappeared during '+name
            samples.append(dict(at=time.monotonic(),window=frame[0],frame=frame[1]))
            time.sleep(.12)
        _,err=movie.communicate(timeout=15)
        assert movie.returncode==0,err
        (d.output/(name+'.mp4')).write_bytes(d.adb('exec-out','cat',remote))
        d.shell('rm','-f',remote)
        log=d.shell('logcat','-d','-s','FloatingFocus:*')
        (d.output/(name+'-layout.log')).write_text(log,encoding='utf-8')
        bounds={}
        required={'c4-body','c4-display','c4-keypad'} if name=='c4-device-expanded' else {'time','controls'}
        for role,coords in re.findall(r'role=([a-z0-9-]+) bounds=([-\d.,]+)',before_log):
            if role not in required:continue
            bounds[role]={coords}  # Last settled coordinates before the sampling interval.
        for role,coords in re.findall(r'role=([a-z0-9-]+) bounds=([-\d.,]+)',log):
            if role not in required:continue
            bounds.setdefault(role,set()).add(coords)
        end_record=None if simulation else d.records()[-1]
        result=dict(name=name,record=end_record,simulation=simulation,samples=samples,frames=sorted({tuple(s['frame']) for s in samples}),
                    bounds={k:sorted(v) for k,v in bounds.items()},measurements=log.count('Overlay measure '),
                    windowUpdates=log.count('Overlay window '),movie=name+'.mp4')
        totals=[int(n) for n in re.findall(r'role=time bounds=[-\d.,]+ totalMillis=(\d+)',log)]
        overtime=[int(n) for n in re.findall(r'role=time bounds=[-\d.,]+ totalMillis=\d+ overtimeMillis=(\d+)',log)]
        result['renderedTotalMillis']=totals
        result['renderedOvertimeMillis']=overtime
        results.append(result)
        (d.output/'overlay-stability-samples.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
        print(name, 'frames=',result['frames'],'bounds=',result['bounds'],flush=True)
        assert required <= bounds.keys(), 'Requested expanded content was not measured: '+name
        if not simulation:
            assert end_record['id']==start_record['id'] and end_record['elapsedMillis']>start_record['elapsedMillis'], 'Clock did not continue during measurement'
        else:
            assert totals and min(totals)<3_600_000<=max(totals), 'Sampling did not actually cross total elapsed one hour'
            assert overtime and min(overtime)<3_600_000<=max(overtime), 'Sampling did not actually cross overtime one hour'
        raw=d.shell('dumpsys','window','windows')
        window=re.search(r'Window\{[^ ]+ u0 app\.floatingfocus\}:.*?(?=\n  Window|\Z)',raw,re.S).group()
        (d.output/(name+'-window.txt')).write_text(window,encoding='utf-8')
        assert 'wrapxwrap' not in window, 'Overlay still uses a wrap-content native window during repeated text relayout'
    def end():
        d.start()
        if d.overlay(): d.shell('input','swipe',*d.center(),int(d.width*.8),int(d.height*.8),350)
        d.finish()

    fixture(d,seconds=2,c4=False)
    d.stop();d.write_prefs('debug_overtime_style',{'style':'TOTAL_FIRST'});d.start();d.home_tab('计时')
    d.tap('开始倒计时')
    time.sleep(3.3)
    sample('ordinary-overtime-expanded')
    end()
    start(30,c4=True)
    d.tapxy(*d.center());time.sleep(.4)
    # Verify the requested state; a compact sample cannot establish full-panel stability.
    if d.frame()[3]-d.frame()[1] < 200*d.density:
        d.tapxy(*d.center());time.sleep(.4)
    assert d.frame()[3]-d.frame()[1] > 200*d.density,'C4 device did not expand'
    sample('c4-device-expanded')
    for key in '7355608': d.tapxy(*key_point(d,key))
    d.wait_stopped()
    start(120,c4=True)
    compact=d.frame();d.tapxy(*d.center());time.sleep(.3)
    expanded=d.frame()
    assert expanded[3]-expanded[1] > compact[3]-compact[1], 'Pre-stage timer did not expand'
    sample('c4-enabled-before-stage-expanded')
    # Collapse at the actual header, then hit the lower half of its original
    # area twice at both edges. Neither second tap may activate End.
    x,y,r,b=time_rect();d.tapxy((x+r)/2,(y+b)/2)
    anchor_check(.01);anchor_check(.99)
    auto=d.shell('settings','get','system','accelerometer_rotation').strip()
    rotation=d.shell('settings','get','system','user_rotation').strip()
    original_size=d.width,d.height
    try:
        d.shell('settings','put','system','accelerometer_rotation','0')
        d.shell('settings','put','system','user_rotation','1');time.sleep(.6)
        tree=d.dump();rects=[d.bounds(n) for n in tree.iter('node') if n.get('bounds')]
        d.width=max(a[2] for a in rects);d.height=max(a[3] for a in rects)
        assert d.width>d.height,'Screen did not rotate'
        anchor_check(.35)
    finally:
        for key,value in [('user_rotation',rotation),('accelerometer_rotation',auto)]:
            d.shell('settings','delete','system',key) if value=='null' else d.shell('settings','put','system',key,value)
        d.width,d.height=original_size;time.sleep(.5)
    end()
    # Existing isolated Lab clock lets real rendering cross an hour without
    # changing the system clock, a production session, records or C4's timeline.
    fixture(d,seconds=120,c4=False)
    before_lab=d.records()
    d.tap('设置');d.tap('开发者模式',scroll=True)
    d.tap('模拟总时长',scroll=True)
    set_wheel(d,'分',0);set_wheel(d,'秒',1);d.tap('确认')
    d.tap('超时',scroll=True)
    d.tap('模拟超时',scroll=True)
    set_wheel(d,'分',59);set_wheel(d,'秒',52);d.tap('确认')
    d.tap('应用模拟时间',scroll=True)
    d.tap('显示浮窗',scroll=True)
    wait_for(lambda:d.overlay() and d.frame()[2]-d.frame()[0]>40,'Lab overlay not ready')
    d.shell('input','swipe',*d.center(),int(d.width*.78),int(d.height*.13),350)
    d.tap('播放模拟',scroll=True)
    d.shell('input','keyevent','KEYCODE_HOME');time.sleep(.3)
    sample('ordinary-total-hour-rollover',seconds=12,simulation=True)
    d.stop()
    wait_for(lambda:d.overlay() is None,'Lab overlay did not close')
    assert d.records()==before_lab,'Isolated rollover simulation changed real records'
    assert all(len(r['frames'])==1 for r in results),'Window frame changes across ordinary ticks; see samples/log/video'
    assert all(len({s['window'] for s in r['samples']})==1 for r in results),'Window recreated during ordinary ticks'
    assert all(all(len({tuple(round(float(n)) for n in coords.split(',')) for coords in values})==1
                   for values in r['bounds'].values()) for r in results),'Compose content shifts across ticks; see samples/log/video'
    return dict(temporalWindowFramesStable=True,temporalContentBoundsStable=True,
        scenarios=[r['name'] for r in results],beforeStageFrames=dict(compact=compact,expanded=expanded),
        originalTimeTapAtBothEdges=True,preStageRotationAndCollapse=True,anchors=anchors,
        totalHourRollover=True,
        samples='overlay-stability-samples.json',note='Seven-second runtime traces per scenario, not static screenshot acceptance')
