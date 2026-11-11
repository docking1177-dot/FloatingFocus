"""Two real overlays and two Launcher3 pins, under the existing data-preserving runner.

Never run on a phone. Native frames and stored records are assertions; screenshots
are review evidence. Does not assert physical audio, vibration or OEM pass-through.
"""
import json
import re
import struct
import time
from device_controls import fixture, wait_for
from device_interactions import tap_control, texts
from device_preferences import pref_values
from device_timer_lifecycle import screen_environment
from device_quota import seed_quota, ledger, APP

PACKAGE = 'app.floatingfocus'


def windows(d):
    raw=d.shell('dumpsys','window','windows')
    result=[]
    for block in re.split(r'\n\s*Window #\d+',raw):
        if not re.search(r'Window\{\S+ u0 app\.floatingfocus\}:',block): continue
        match=re.search(r'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]',block)
        if match:
            alpha=re.search(r'\balpha=([\d.]+)',block)
            result.append(dict(frame=list(map(int,match.groups())),alpha=float(alpha[1]) if alpha else 1.0))
    return result


def move_windows_down(d):
    # Native Z-order first. Move the upper window out of the shared home controls.
    for i,window in enumerate(windows(d)):
        x,y,r,b=window['frame']
        d.shell('input','swipe',(x+r)//2,(y+b)//2,
                int(d.width*(.25 if i else .75)),int(d.height*(.80 if i else .63)),500)
    time.sleep(.3)


def end_first(d):
    d.start();move_windows_down(d)
    tap_control(d,'结束浮窗 1')


def enter(d,id):
    d.shell('am','start','-n',PACKAGE+'/.PresetShortcutActivity','-a',PACKAGE+'.action.LAUNCH_PRESET','--es','preset_id',id)


def pin(d,name):
    d.start();d.home_tab('预设');d.tap('管理',scroll=True)
    d.tap('添加到桌面 '+name,scroll=True)
    wait_for(lambda:'.launcher3' in d.foreground(),'No system pin confirmation')
    for label in ('ADD TO HOME SCREEN','Add automatically','Add to Home screen','Add to home screen','ADD','Add','添加','添加到主屏幕'):
        try: tap_control(d,label);break
        except AssertionError: continue
    else: raise AssertionError('No launcher confirmation action')
    d.start()
    if '管理预设方案' in texts(d): d.tap('完成')


def click_icon(d,name):
    d.shell('input','keyevent','KEYCODE_HOME');time.sleep(.4)
    for direction in (None,True,True,False,False,False):
        if direction is not None:
            a,b=(.85,.15) if direction else (.15,.85)
            d.shell('input','swipe',int(d.width*a),int(d.height*.5),int(d.width*b),int(d.height*.5),350)
        nodes=[n for n in d.dump().iter('node') if n.get('text')==name and n.get('package')=='com.android.launcher3']
        if nodes:
            d.shot('dual-desktop-'+name);x,y,r,b=d.bounds(nodes[0]);d.tapxy((x+r)//2,(y+b)//2);return
    raise AssertionError('Actual desktop icon missing: '+name)


def dual_overlays(d):
    before=len(d.records());result={}
    try:
        with screen_environment(d):
            fixture(d,seconds=600,c4=False);d.stop();seed_quota(d,used=0)
            base=dict(shape='ROUNDED_RECTANGLE',color='BLUE',baseSize=1.,opacity=.3,foregroundOpacity=1.,
                      intensity=0.,colorEnabled=False,sizeEnabled=False)
            one=dict(id='dual-alpha',name='DualAlpha',minutes=10,durationSeconds=600,mode='COUNTDOWN',settings=base,
                     targetPackage=APP,targetLabel='Gallery',c4Enabled=False,positions=dict(portrait=dict(x=.7,y=.35)))
            two=dict(one,id='dual-beta',name='DualBeta',mode='STOPWATCH',settings=dict(base,shape='CIRCLE',color='GREEN',opacity=.9,foregroundOpacity=.4))
            c4=dict(one,id='dual-c4',name='DualC4',durationSeconds=35,c4Enabled=True)
            d.write_prefs('timer_presets',dict(items=json.dumps([one,two,c4])))
            d.write_prefs('reminder_settings',dict(base,opacity=.7,foregroundOpacity=.8))
            d.start();saved=pref_values(d,'reminder_settings')
            pin(d,'DualAlpha');pin(d,'DualBeta')
            click_icon(d,'DualAlpha')
            wait_for(lambda:len(windows(d))==1 and len(d.records())==before+1,'First desktop start failed')
            first=d.records()[-1]
            assert APP in d.foreground() and first['plannedMillis']==600000 and first['mode']=='COUNTDOWN'
            click_icon(d,'DualBeta')
            wait_for(lambda:len(windows(d))==2 and len(d.records())==before+2,'Second desktop start did not coexist')
            second=d.records()[-1]
            assert APP in d.foreground() and second['mode']=='STOPWATCH' and second['id']!=first['id']
            assert pref_values(d,'reminder_settings')==saved,'Preset changed global appearance'
            actual=windows(d)
            assert sorted(round(w['alpha'],2) for w in actual)==[.9,1.0],actual
            move_windows_down(d);d.shot('dual-independent-windows')
            # Both records must receive durable checkpoints, rather than one queue starving the second.
            checkpoint={r['id']:r['elapsedMillis'] for r in d.records()[-2:]}
            time.sleep(2.4)
            assert all(r['elapsedMillis']>checkpoint[r['id']] for r in d.records()[-2:])
            click_icon(d,'DualAlpha')
            wait_for(lambda:any('双浮窗上限' in t for t in texts(d)),'Third start did not report capacity')
            assert len(windows(d))==2 and len(d.records())==before+2
            d.tap('取消');d.start();move_windows_down(d)
            tap_control(d,'暂停浮窗 2');time.sleep(.5)
            paused=next(r for r in d.records() if r['id']==second['id'])['elapsedMillis']
            time.sleep(1.4)
            assert next(r for r in d.records() if r['id']==second['id'])['elapsedMillis']==paused
            assert next(r for r in d.records() if r['id']==first['id'])['elapsedMillis']>checkpoint[first['id']]
            tap_control(d,'继续浮窗 2');time.sleep(.5)
            end_first(d)
            wait_for(lambda:len(windows(d))==1,'Ending oldest closed both or retained both')
            assert next(r for r in d.records() if r['id']==first['id'])['status']=='MANUAL'
            assert next(r for r in d.records() if r['id']==second['id'])['status']=='RUNNING'
            d.shot('dual-survivor-after-first-ended');end_first(d);d.wait_stopped()
            final=[r for r in d.records() if r['id'] in (first['id'],second['id'])]
            total=sum(r['elapsedMillis'] for r in final);used=ledger(d)['day']['used'][APP]
            assert used < total-1000 and used>=max(r['elapsedMillis'] for r in final)-2000,(used,total,final)
            result.update(actualDesktopIcons=2,twoIndependentRecords=True,thirdRejected=True,
                          presetDoesNotChangeGlobalAppearance=True,independentNativeOpacity=True,
                          secondPauseDoesNotPauseFirst=True,oldestEndKeepsSecond=True,
                          quotaUnionObserved=dict(chargedMillis=used,sumSessionMillis=total))
            # Same stable preset ID can create a second independent session.
            saved_positions=json.loads(pref_values(d,'timer_presets')['items'])[0]['positions']
            enter(d,one['id']);wait_for(lambda:len(windows(d))==1,'First same-preset start missing')
            enter(d,one['id']);wait_for(lambda:len(windows(d))==2,'Second same-preset start rejected')
            frames=[w['frame'] for w in windows(d)]
            assert frames[0]!=frames[1],('Saved-position windows overlap',frames)
            time.sleep(1.2);assert [w['frame'] for w in windows(d)]==frames,'Temporary separation drifted each measurement'
            assert json.loads(pref_values(d,'timer_presets')['items'])[0]['positions']==saved_positions,'Initial separation overwrote saved anchor'
            d.start();move_windows_down(d);d.shot('dual-same-preset')
            end_first(d);wait_for(lambda:len(windows(d))==1,'Same preset end closed both')
            end_first(d);d.wait_stopped();result['samePresetTwoWindows']=True
            # One C4 with one ordinary; another C4 is rejected without replacing either.
            enter(d,c4['id']);wait_for(lambda:len(windows(d))==1,'C4 missing')
            enter(d,c4['id']);wait_for(lambda:any('最多一个 C4' in t for t in texts(d)),'Second C4 not rejected')
            assert len(windows(d))==1;d.tap('取消')
            enter(d,one['id']);wait_for(lambda:len(windows(d))==2,'Ordinary alongside C4 missing')
            d.shot('dual-c4-and-ordinary');result['oneC4AndOrdinary']=True
            # Force-stop is test cleanup; no claim of a successful defuse.
            d.stop();d.start();assert not windows(d)
            result['processStopNoAutomaticRestart']=True
        return result
    finally:
        d.stop()
        d.shell('cmd','shortcut','clear-shortcuts','--user','0',PACKAGE)


def landscape_bottom(d):
    rows=[]
    with screen_environment(d):
        for shape in ('ROUNDED_RECTANGLE','VERTICAL_CAPSULE','CIRCLE'):
            fixture(d,seconds=600,c4=False,shape=shape)
            d.home_tab('计时');d.tap('开始倒计时');wait_for(lambda:len(windows(d))==1,'Overlay missing')
            # Gallery is fullscreen; testing only FF leaves the visible-status-bar
            # parent in place and misses the historical extra bottom inset.
            d.shell('am','start','-n','com.android.gallery3d/.app.GalleryActivity')
            d.shell('wm','user-rotation','lock','1')
            def rotated():
                raw=d.adb('exec-out','screencap','-p');d.width,d.height=struct.unpack('>II',raw[16:24]);return d.width>d.height
            wait_for(rotated,'Landscape missing');time.sleep(.5)
            d.shell('input','swipe',*d.center(),d.width//2,d.height-1,700);time.sleep(.3)
            raw=d.shell('dumpsys','window','windows')
            block=next(b for b in re.split(r'\n\s*Window #\d+',raw) if re.search(r'Window\{\S+ u0 app\.floatingfocus\}:',b))
            parent=re.search(r'parent=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]',block)
            assert parent,'No native fitted parent'
            frame=d.frame();safe=list(map(int,parent.groups()))
            d.shot('landscape-bottom-'+shape)
            (d.output/('landscape-bottom-'+shape+'.txt')).write_text(block,encoding='utf-8')
            assert abs(frame[3]-safe[3])<=1,(shape,frame,safe)
            rows.append(dict(shape=shape,frame=frame,parent=safe))
            d.stop();d.shell('wm','user-rotation','lock','0');time.sleep(.6)
            raw=d.adb('exec-out','screencap','-p');d.width,d.height=struct.unpack('>II',raw[16:24])
    return dict(nativeSafeBottom=rows,physicalDevice='not tested')


def opacity_channels(d):
    before=len(d.records())
    fixture(d,seconds=600,c4=False)
    d.start();d.tap('设置');d.tap('悬浮窗')
    def slider(label,fraction):
        for _ in range(12):
            node=next((n for n in d.dump().iter('node') if n.get('content-desc')==label and d.bounds(n)[3]>d.bounds(n)[1]),None)
            if node is not None:
                x,y,r,b=d.bounds(node);d.tapxy(x+(r-x)*fraction,(y+b)//2);return
            d.shell('input','swipe',int(d.width*.9),int(d.height*.83),int(d.width*.9),int(d.height*.47),300)
        raise AssertionError('Settings slider missing: '+label)
    slider('背景不透明度',.2)
    bg=pref_values(d)
    assert bg['opacity']<.6 and abs(bg['foregroundOpacity']-.7)<.001,bg
    slider('数字与图标不透明度',.9)
    chosen=pref_values(d)
    assert chosen['opacity']==bg['opacity'] and chosen['foregroundOpacity']>.85,chosen
    assert len(d.records())==before and not windows(d),'Settings preview created a session'
    d.shot('opacity-independent-sliders')
    d.stop();d.start();assert pref_values(d)==chosen,'Opacity channels not persisted'
    d.home_tab('计时');d.tap('开始倒计时');wait_for(lambda:len(windows(d))==1,'Ordinary opacity window missing')
    assert abs(windows(d)[0]['alpha']-max(chosen['opacity'],chosen['foregroundOpacity']))<.002
    d.shot('opacity-independent-actual-window');end_first(d);d.wait_stopped()
    return dict(uiChannelsIndependent=True,restartKeptBoth=True,nativeUsesMaximum=True,noPreviewSession=True,
                background=chosen['opacity'],foreground=chosen['foregroundOpacity'])
