"""Real Launcher3 widget regression; owns only widgets added to an initially empty FF host."""
import json
import re
import time

PACKAGE = 'app.floatingfocus'


def widget_ids(raw):
    # Providers/Hosts can mention FF even when no instance exists.
    section = raw.partition('Widgets:')[2].partition('Hosts:')[0]
    return {int(m[1]) for m in re.finditer(
        r'\[\d+\] id=(\d+)(.*?)(?=\n\s*\[\d+\] id=|\Z)', section, re.S)
        if 'cmp:ComponentInfo{app.floatingfocus/' in m[2]}


def require_empty_widgets(raw):
    assert not widget_ids(raw), 'Use an isolated AVD without existing FF widgets; launcher state is outside the app-data backup.'


def find_desktop_widget(d):
    # HOME may retain the current workspace. Inspect a bounded set of pages;
    # do not assume a newly placed widget is on the default page.
    for direction in (None,'right','right','right','left','left','left','left','left','left'):
        if direction:
            a,b=(.2,.8) if direction=='right' else (.8,.2)
            d.shell('input','swipe',int(d.width*a),int(d.height*.62),int(d.width*b),int(d.height*.62),350)
            time.sleep(.3)
        roots=[n for n in d.dump().iter('node') if n.get('resource-id')==PACKAGE+':id/widget_root']
        if roots:return roots[0]
    raise AssertionError('FF widget exists globally but was not found on inspected launcher pages; inspect remaining IDs before retrying.')


def widgets(d):
    def wait(check, message, seconds=10):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if check():
                return
            time.sleep(.3)
        raise AssertionError(message)

    def home():
        d.shell('input', 'keyevent', 'KEYCODE_HOME')
        time.sleep(.7)

    def find(id, tree=None):
        return [n for n in (tree if tree is not None else d.dump()).iter('node')
                if n.get('resource-id') == PACKAGE + ':id/' + id]

    def point(node):
        x,y,r,b = d.bounds(node)
        return (x+r)//2, (y+b)//2

    def add(size):
        home()
        d.shell('input','swipe',d.width//2,int(d.height*.55),d.width//2,int(d.height*.55),1000)
        d.tap('Widgets')
        d.tap('Floating Focus',scroll=True)
        for _ in range(4):
            candidates=[n for n in d.dump().iter('node') if f'2 wide by {size} high' in n.get('content-desc','')]
            if candidates and d.bounds(candidates[0])[3] < d.height*.9:
                break
            d.shell('input','swipe',d.width//2,int(d.height*.83),d.width//2,int(d.height*.45),450)
        node = next(n for n in d.dump().iter('node') if f'2 wide by {size} high' in n.get('content-desc',''))
        d.tapxy(*point(node)); d.tap('ADD',scroll=True); home()
        wait(lambda: len(widget_ids(d.shell('dumpsys','appwidget'))) == size, 'Widget not placed in real launcher')

    def capture_controls():
        # Capture fresh ID geometry while idle. Running Chronometers produce
        # accessibility changes every second, so UIAutomator cannot always idle.
        tree=d.dump()
        result={id:[point(n) for n in find(id,tree)] for id in
                ('widget_primary','widget_end','widget_preset_one','widget_preset_two')}
        assert len(find('widget_root',tree))==2
        assert len(result['widget_primary'])==2 and len(result['widget_end'])==1
        return result

    def press(id,index=0):
        d.tapxy(*controls[id][index])

    def started(before,mode='COUNTDOWN',planned=65000,target=None):
        wait(lambda: d.overlay() and len(d.records())==before+1, 'No unique widget session')
        time.sleep(1)
        assert d.overlay(), 'Self-closing entry task ended the session'
        row=d.records()[-1]
        assert row['mode']==mode and row['plannedMillis']==planned,row
        if target: assert target in d.foreground() and row['packageName']==target,row
        # Keep the floating timer away from the widget controls.
        x,y=d.center(); d.shell('input','swipe',x,y,int(d.width*.88),int(d.height*.78),450)
        home()
        return row

    def end():
        press('widget_end');d.wait_stopped();home()
        assert d.records()[-1]['status']=='MANUAL',d.records()[-1]

    def seed(duration=65,mode='COUNTDOWN',c4=False):
        d.stop()
        d.write_prefs('onboarding',dict(completed=True))
        d.write_prefs('c4_settings',dict(enabled=c4,sound=False,noticeAcknowledged=True))
        d.write_prefs('quick_durations',dict(seconds='300,600,900,1800',startup_mode=mode,startup_seconds=duration))
        d.write_prefs('quick_launch',{'targets':json.dumps([dict(package='com.android.gallery3d',label='Gallery')])})
        d.write_prefs('reminder_settings',appearance)
        d.write_prefs('timer_presets',{'items':json.dumps(items)})
        d.start();home()

    def remove_all():
        # No pre-existing FF instance is permitted. Never delete other providers.
        d.stop();d.start();home()
        for _ in range(3):
            if not widget_ids(d.shell('dumpsys','appwidget')):return
            root=find_desktop_widget(d)
            x,y,r,b=d.bounds(root)
            # A label/non-action region starts the launcher's drag, not a button.
            sx,sy=(x+r)//2,y+max(8,round(8*d.density))
            d.shell('input','motionevent','DOWN',sx,sy);time.sleep(.8)
            d.shell('input','motionevent','MOVE',sx,sy+round(40*d.density));time.sleep(.4)
            try:
                remove=next(n for n in d.dump().iter('node') if n.get('text')=='Remove')
                tx,ty=point(remove)
                d.shell('input','motionevent','MOVE',tx,ty);time.sleep(.4)
                d.shell('input','motionevent','UP',tx,ty);time.sleep(.6)
            finally:
                d.shell('input','keyevent','KEYCODE_BACK')
            home()
        assert not widget_ids(d.shell('dumpsys','appwidget')), 'Test widgets not removed'

    require_empty_widgets(d.shell('dumpsys','appwidget'))
    appearance=dict(shape='ROUNDED_RECTANGLE',color='BLUE',baseSize=.75,opacity=.7,intensity=0.,colorEnabled=False,sizeEnabled=False)
    one=dict(id='widget-test-one',name='短片65秒',minutes=1,durationSeconds=65,mode='COUNTDOWN',settings=appearance,
             targetPackage='com.android.gallery3d',targetLabel='Gallery',c4Enabled=False)
    two=dict(one,id='widget-test-two',name='阅读正计时',mode='STOPWATCH')
    items=[one,two]
    old_font=d.shell('settings','get','system','font_scale').strip()
    old_night=d.shell('cmd','uimode','night').strip()
    results={}
    try:
        seed();add(1);add(2)
        controls=capture_controls();d.shot('widgets-idle-light')
        print('Widgets: actual default start and synchronized controls...',flush=True)
        count=len(d.records());press('widget_primary');started(count)
        d.shot('widgets-countdown')
        press('widget_primary');time.sleep(.4)
        paused=find('widget_time');assert len(paused)==2
        values=[n.get('text') for n in paused];time.sleep(2)
        assert values==[n.get('text') for n in find('widget_time')]
        assert all(n.get('text')=='继续' for n in find('widget_primary'))
        d.shot('widgets-paused');press('widget_primary')
        time.sleep(1);assert d.overlay();end()
        results['defaultPauseResumeEnd']=True

        print('Widgets: latest presets, countdown/stopwatch and conflict...',flush=True)
        count=len(d.records());press('widget_preset_one');first=started(count,target='com.android.gallery3d')
        press('widget_preset_two');assert len(d.records())==count+1 and d.records()[-1]['id']==first['id']
        end()
        count=len(d.records());press('widget_preset_two');started(count,'STOPWATCH',0,'com.android.gallery3d')
        press('widget_primary');time.sleep(.4);assert len(find('widget_time'))==2
        press('widget_primary');end()
        items[0]=dict(one,name='最新70秒',durationSeconds=70)
        seed();controls=capture_controls()
        assert find('widget_preset_name_one')[0].get('text')=='最新70秒'
        count=len(d.records());press('widget_preset_one');started(count,planned=70000,target='com.android.gallery3d');end()
        results['latestPresetsAndConflict']=True

        print('Widgets: missing overlay permission and explicit continuation...',flush=True)
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','deny')
        count=len(d.records());press('widget_primary')
        assert '授予悬浮窗权限' in [n.get('text') for n in d.dump().iter('node')]
        assert d.overlay() is None and len(d.records())==count
        d.tap('授予悬浮窗权限');d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','allow')
        d.shell('input','keyevent','KEYCODE_BACK');time.sleep(.6)
        assert d.overlay() is None and len(d.records())==count
        d.tap('继续开始');started(count);end()
        results['permissionExplicitContinue']=True

        print('Widgets: overtime and C4 controls...',flush=True)
        seed(duration=2);controls=capture_controls();count=len(d.records());press('widget_primary');started(count,planned=2000)
        wait(lambda:d.records()[-1].get('overtimeMillis',0)>0,'No overtime')
        d.shot('widgets-overtime');end()
        seed(duration=20,c4=True);controls=capture_controls();count=len(d.records())
        pauses=d.shell('logcat','-d','-s','FloatingFocus:*').count('C4 clock paused=true')
        press('widget_primary');started(count,planned=20000)
        before=d.records()[-1]['id'];press('widget_primary');home();press('widget_end')
        assert d.overlay() and d.records()[-1]['id']==before and d.records()[-1]['status']=='RUNNING'
        log=d.shell('logcat','-d','-s','FloatingFocus:*')
        assert log.count('C4 clock paused=true')==pauses
        d.shot('widgets-c4-locked');d.stop()
        results['overtimeAndC4Guards']=True

        seed(mode='STOPWATCH');controls=capture_controls();count=len(d.records());press('widget_primary');started(count,'STOPWATCH',0);end()
        # Cached process death, without force-stop disabling launcher PendingIntents.
        d.shell('am','kill',PACKAGE);time.sleep(.5)
        count=len(d.records());press('widget_primary');started(count,'STOPWATCH',0);end()
        results['savedStopwatchAndColdStart']=True

        seed();d.shell('cmd','uimode','night','yes');time.sleep(.6);home();d.shot('widgets-idle-dark')
        d.shell('settings','put','system','font_scale','1.5');time.sleep(.6);home();d.shot('widgets-large-font')
        assert len(find('widget_preset_one'))==1
        results['themeAndFontScreenshots']=True
        d.shell('settings','put','system','font_scale','1.0');time.sleep(.5)
        home()
        root=find('widget_root')[0];x,y,r,b=d.bounds(root)
        sx,sy=(x+r)//2,y+round(8*d.density)
        d.shell('input','swipe',sx,sy,sx,sy,1000)
        handle=next(n for n in d.dump().iter('node') if n.get('resource-id')=='com.android.launcher3:id/widget_resize_bottom_handle')
        hx,hy=point(handle)
        d.shell('input','swipe',hx,hy,hx,hy+round(112*d.density),700)
        d.tapxy(int(d.width*.88),int(d.height*.7));home()
        assert len(find('widget_preset_one'))==2,'Resized 2x1 did not expose presets'
        d.shot('widgets-resized');results['realLauncherResize']=True
        return results
    finally:
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','allow')
        d.shell('settings','put','system','font_scale',old_font if old_font!='null' else '1.0')
        d.shell('cmd','uimode','night',old_night.partition(':')[2].strip() or 'no')
        remove_all()
