"""Same-window C4 device, real keys, supplied local tracks and single natural completion."""
import json
import re
import time

PACKAGE = 'app.floatingfocus'


def c4(d):
    def logs():
        return d.shell('logcat', '-d', '-s', 'FloatingFocus:*')

    def top():
        for _ in range(4):
            d.shell('input', 'swipe', int(d.width*.03), int(d.height*.25), int(d.width*.03), int(d.height*.85), 200)

    def texts():
        return [n.get('text', '') for n in d.dump().iter('node')]

    def right_entry(label):
        node = next(n for n in d.dump().iter('node') if n.get('text') == label)
        _,y,_,b = d.bounds(node)
        # The draggable device is now on the left; use the same row's exposed right end.
        d.tapxy(d.width-30*d.density, (y+b)/2)

    def viewport():
        bounds = [d.bounds(n) for n in d.dump().iter('node') if n.get('bounds')]
        d.width, d.height = max(r for _,_,r,_ in bounds), max(b for _,_,_,b in bounds)

    def wait_for(predicate, timeout=10):
        until = time.monotonic()+timeout
        while time.monotonic() < until:
            if predicate():
                return
            time.sleep(.08)
        raise AssertionError('C4 wait timed out; '+logs()[-1400:])

    def start(seconds, enabled=True, sound=True, mode='COUNTDOWN', shape='ROUNDED_RECTANGLE'):
        d.stop()
        d.write_prefs('onboarding', dict(completed=True))
        appearance = dict(shape=shape, color='BLUE', style='COLOR', baseSize=1.0, opacity=.7,
                          intensity=1.0, colorEnabled=True, sizeEnabled=False)
        preset = dict(id='c4-test', name='C4 '+str(seconds), minutes=max(1, seconds//60), durationSeconds=seconds,
                      settings=appearance, mode=mode, targetPackage='com.android.gallery3d', targetLabel='Gallery')
        d.write_prefs('c4_settings', dict(enabled=enabled, sound=sound, noticeAcknowledged=True))
        d.write_prefs('reminder_settings', appearance)
        d.write_prefs('timer_presets', {'items': json.dumps([preset])})
        d.write_prefs('quick_launch', {'targets': json.dumps([dict(package='com.android.gallery3d', label='Gallery')])})
        d.start(); d.shell('logcat', '-c')
        d.tap('启动预设 C4 '+str(seconds), scroll=True)
        wait_for(lambda: d.overlay() and 'com.android.gallery3d' in d.foreground() and d.frame()[2]-d.frame()[0] < d.width, 8)
        time.sleep(.3)
        return d.overlay()[0], d.frame()

    def key_point(char):
        x,y,r,b = d.frame()
        w,h = (r-x)/d.density-4, (b-y)/d.density-4
        gap = 2 if h < 240 else 4
        kw = max(44, (w*.64-gap*2)/3)
        kh = 44 if h < 300 else 46
        dy = 4 if h < 300 else 48
        dh = 24 if h < 240 else 40 if h < 300 else 58
        left = max(0, (w-kw*3-gap*2)/2+6)
        top_y = max(h-kh*4-gap*3-12, dy+dh+6)
        i = '123456789*0#'.index(char)
        px,py = x+(2+left+(i%3)*(kw+gap)+kw/2)*d.density, y+(2+top_y+(i//3)*(kh+gap)+kh/2)*d.density
        assert x <= px < r and y <= py < b, ('Key outside frame', char, (px,py), (x,y,r,b))
        return px,py

    def key(char):
        px,py = key_point(char)
        d.shell('input', 'tap', int(px), int(py))

    def code(success_shot=False):
        for char in '7355608':
            key(char)
        if success_shot:
            d.shot('c4-defused-feedback')
        wait_for(lambda: d.overlay() is None, 5)
        assert logs().count('C4 key result=DEFUSED') == 1
        assert 'C4 explosion consumed' not in logs()

    rotation = d.shell('settings', 'get', 'system', 'user_rotation').strip()
    auto_rotate = d.shell('settings', 'get', 'system', 'accelerometer_rotation').strip()
    try:
        print('C4: first-enable disclosure and defaults...', flush=True)
        d.stop(); d.write_prefs('onboarding', dict(completed=True)); d.write_prefs('c4_settings', {})
        d.start(); d.tap('设置'); d.tap('提醒')
        assert '音效' not in texts()
        d.tap('C4 倒计时'); assert '开启 C4 倒计时' in texts()
        d.tap('取消'); assert '音效' not in texts()
        d.tap('C4 倒计时'); d.tap('开启'); assert '音效' in texts()
        d.tap('音效'); d.stop(); d.start(); d.tap('设置'); d.tap('提醒')
        d.shot('c4-settings-disclosure')

        print('C4: 60s boundary, planting input drop, real keyboard and drag...', flush=True)
        wid, folded = start(60)
        d.shot('c4-folded')
        wait_for(lambda: 'C4 phase=PLANTING' in logs(), 22)
        key('7')  # Probe immediately; screenshot/dumpsys latency must not move a later tap past 4.92s.
        assert 'C4 key result=CHANGED' not in logs(), 'A planting key was accepted'
        device = d.frame()
        d.shot('c4-planting')
        wait_for(lambda: 'C4 phase=ARMED' in logs(), 8)
        assert d.overlay()[0] == wid and device[3]-device[1] > folded[3]-folded[1]
        d.shot('c4-input-open')
        for char in '1111111': key(char)
        assert 'C4 key result=WRONG' in logs()
        d.shot('c4-input-error')
        key('7'); key('3'); key('*'); key('#')
        assert 'C4 key result=CHANGED length=0' in logs()
        old = d.frame(); before = logs().count('C4 key result=')
        kx,ky = key_point('7')
        d.shell('input', 'swipe', int(kx), int(ky), int(kx+35*d.density), int(ky), 400)
        assert d.frame() == old and logs().count('C4 key result=') == before, 'Key drag moved window or typed'
        x,y,r,b = old
        d.shell('input', 'swipe', int(x+(r-x)*.5), int(y+(b-y)*.34), int(d.width*.38), int(d.height*.52), 400)
        assert d.overlay()[0] == wid and d.frame() != old
        audio_starts = logs().count('C4 media start=pre_explosion')
        d.start(); top()
        assert '结束计时' not in texts() and '加时' not in texts()
        notifications = d.shell('dumpsys', 'notification', '--noredact')
        records = re.split(r'(?=NotificationRecord\()', notifications)
        current = next(p for p in records if 'pkg=app.floatingfocus' in p and 'id=1 ' in p)
        assert '结束计时' not in current, 'C4 notification retained a normal stop action'
        (d.output/'c4-active-notification.txt').write_text(current, encoding='utf-8')
        d.tap('设置'); right_entry('提醒'); right_entry('C4 倒计时')
        assert '本次 C4 仍在继续；开关修改对下一次会话生效。' in texts()
        assert d.overlay()[0] == wid, 'Global switch ended the current session'
        assert logs().count('C4 media start=pre_explosion') == audio_starts, 'Navigation replayed audio'
        code(success_shot=True)
        assert d.records()[-1]['status'] == 'MANUAL' and d.records()[-1]['plannedMillis'] == 60_000
        (d.output/'c4-defuse.log').write_text(logs(), encoding='utf-8')

        def expiry(seconds, sound):
            print(f'C4: real {seconds}s expiry, sound={sound}...', flush=True)
            wid,_ = start(seconds, sound=sound)
            assert 'C4 phase=ARMED' in logs()
            d.shot(f'c4-armed-{seconds}-{sound}')
            if sound:
                before_lock = re.findall(r'C4 media start=pre_explosion offset=(\d+)', logs())
                d.shell('input', 'keyevent', 'KEYCODE_SLEEP'); time.sleep(1)
                d.shell('input', 'keyevent', 'KEYCODE_WAKEUP'); d.shell('wm', 'dismiss-keyguard')
                after_lock = re.findall(r'C4 media start=pre_explosion offset=(\d+)', logs())
                assert all(int(offset) >= 44_439-seconds*1000-100 for offset in after_lock[len(before_lock):]), 'Lock/unlock replayed an earlier section'
            wait_for(lambda: 'Timer completed:' in logs(), seconds+8)
            d.shot(f'c4-explosion-{seconds}-{sound}')
            assert logs().count('Timer completed:') == 1 and logs().count('C4 explosion consumed') == 1
            assert d.overlay()[0] == wid
            wait_for(lambda: 'C4 phase=OFF' in logs(), 9)
            d.shot(f'c4-overtime-{seconds}-{sound}')
            text = logs()
            if sound:
                assert 'C4 media start=pre_explosion' in text and 'C4 media start=explosion' in text, text
                offset = int(re.search(r'C4 media start=pre_explosion offset=(\d+)', text)[1])
                assert offset >= 44_439-seconds*1000-100, offset
            else:
                assert 'C4 media start=' not in text
            (d.output/f'c4-expiry-{seconds}-{sound}.log').write_text(text, encoding='utf-8')
            d.finish()
            assert d.records()[-1]['status'] == 'MANUAL'
        expiry(10, True)
        expiry(30, False)

        print('C4: transparent corner reaches a DIFFERENT-UID Settings search control...', flush=True)
        start(30, sound=False)
        d.shell('am', 'force-stop', 'com.android.settings')
        # Repeated runs must pop the previous Search activity rather than bring that task back on top.
        d.shell('am', 'start', '-W', '-a', 'android.settings.SETTINGS', '-f', '0x14000000')
        tree = d.dump()
        search = next(n for n in tree.iter('node') if n.get('resource-id') == 'com.android.settings:id/search_action_bar')
        sx,sy,sr,sb = d.bounds(search)
        target = (sr-8*d.density, (sy+sb)/2)
        x,y,r,b = d.frame()
        # Default right/top placement should put the search row's far-right end in
        # the device's transparent top-right corner, INSIDE its rectangular frame.
        assert x < target[0] < r and y < target[1] < b, (target, d.frame())
        assert target[0] > x+(r-x)*.95 and target[1] < y+(b-y)*.08, (target, d.frame())
        previous = d.foreground()
        d.tapxy(*target)
        assert d.foreground() != previous and ('Search' in d.foreground() or 'search' in d.foreground()), d.foreground()
        assert d.overlay() is not None
        d.shot('c4-transparent-cross-uid')
        code()
        d.shell('input', 'keyevent', 'KEYCODE_BACK')

        print('C4: compact landscape keyboard and same-window rotation...', flush=True)
        d.shell('settings', 'put', 'system', 'accelerometer_rotation', 0)
        d.shell('settings', 'put', 'system', 'user_rotation', 1)
        time.sleep(.5)
        viewport()
        wid,_ = start(30, sound=False)
        for char in '*#': key(char)
        d.shot('c4-landscape-keypad')
        d.shell('settings', 'put', 'system', 'user_rotation', 0); time.sleep(.6)
        viewport()
        assert d.overlay()[0] == wid
        d.shot('c4-portrait-after-rotation')
        code()

        print('C4: stop/permission/normal-mode boundaries...', flush=True)
        # Force-stop must clean up all audio/window resources, never synthesize an explosion.
        start(40); d.shot('c4-short-planting'); d.stop(); d.wait_stopped()
        assert 'C4 explosion consumed' not in logs()
        start(30, sound=False)
        d.shell('appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'deny')
        d.wait_stopped(); assert 'C4 explosion consumed' not in logs()
        d.shell('appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'allow')
        for shape in ('ROUNDED_RECTANGLE', 'VERTICAL_CAPSULE', 'CIRCLE'):
            start(60, shape=shape, sound=False)
            d.shot('c4-folded-'+shape)
            d.finish()
            assert 'C4 phase=PLANTING' not in logs()
        start(10, enabled=False); time.sleep(.5); assert 'C4 phase=ARMED' not in logs(); d.finish()
        start(10, mode='STOPWATCH'); time.sleep(.5); assert 'C4 phase=ARMED' not in logs(); d.finish()
        return dict(defaultsAndDisclosure=True, inputDroppedBeforePlanting=True, realKeysAndWrongRetry=True,
                    backspaceAndClear=True, keyDragCancelled=True, bodyDragSameWindow=True, noReplayOnNavigation=True,
                    homeAndNotificationStopRemoved=True, switchNextSession=True, defuseStopsWithoutExplosion=True,
                    naturalExpirySingleExplosion=True, tailRestoresOvertime=True, shortStartsSeekToCurrentOffset=True, lockUnlockNoReplay=True,
                    mutedVisualAndInput=True, landscapeKeysAndRotation=True, forceStopAndRevocation=True,
                    foldedShapes=True, stopwatchExcluded=True, audioEvidence='MediaPlayer request/position logs only; no listening assertion',
                    transparentPassThrough=dict(api=36, windowOpacity=.7, crossUid='Android Settings search',
                                                pointInsideBoundingFrame=True, higherOpacityAndOlderApis='not tested'))
    finally:
        d.shell('appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'allow')
        d.shell('settings', 'put', 'system', 'user_rotation', rotation)
        d.shell('settings', 'put', 'system', 'accelerometer_rotation', auto_rotate)
