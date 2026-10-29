"""Same-window C4 device, real keys, supplied local tracks and single natural completion."""
import datetime
import json
import re
import time

PACKAGE = 'app.floatingfocus'


def has_timer_service(dump):
    # dumpsys includes an unstarred global "Last ANR service" even with a package
    # filter. Only current service entries for this component count as running.
    return bool(re.search(r'(?m)^\s*\*\s+ServiceRecord\{[^\n]*\bapp\.floatingfocus/(?:\.|app\.floatingfocus\.)FloatingService(?:\s|\})', dump))


def c4(d, focus=None):
    row_centers = {}
    snapshot_seconds = []
    def logs():
        return d.shell('logcat', '-d', '-s', 'FloatingFocus:*')

    def top():
        for _ in range(4):
            d.shell('input', 'swipe', int(d.width*.03), int(d.height*.25), int(d.width*.03), int(d.height*.85), 200)

    def texts():
        began = time.monotonic()
        tree = d.dump()
        snapshot_seconds.append(time.monotonic()-began)
        for n in tree.iter('node'):
            if n.get('text') in ('提醒', 'C4 倒计时'):
                _,y,_,b=d.bounds(n)
                row_centers[n.get('text')]=(y+b)/2
        return [n.get('text', '') for n in tree.iter('node')]

    def open_settings():
        d.tap('设置')
        # Cold emulator startup can defer the first navigation traversal. Retry only
        # against a fresh home hierarchy; never treat an unrelated page as settings.
        for attempt in range(3):
            if '提醒' in texts(): return
            assert 'MainActivity' in d.foreground(), 'Unexpected destination while opening settings'
            d.tap('设置')
        raise AssertionError('Settings did not open')

    def right_entry(label):
        # Coordinates come from a successful fresh idle-page snapshot above.
        # Repeated dumps while the real LED/time is changing can consume its whole deadline.
        assert label in row_centers
        d.tapxy(d.width-30*d.density, row_centers[label])

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

    last_sound = False
    home_start_point = None
    gear_point = None

    def start(seconds, enabled=True, sound=True, mode='COUNTDOWN', shape='ROUNDED_RECTANGLE', base_size=1.0, opacity=.7, color='BLUE'):
        nonlocal last_sound, home_start_point, gear_point
        last_sound = sound
        d.stop()
        d.write_prefs('onboarding', dict(completed=True))
        appearance = dict(shape=shape, color=color, style='COLOR', baseSize=base_size, opacity=opacity,
                          intensity=1.0, colorEnabled=True, sizeEnabled=True)
        preset = dict(id='c4-test', name='C4 '+str(seconds), minutes=max(1, seconds//60), durationSeconds=seconds,
                      settings=appearance, mode=mode, c4Enabled=enabled, targetPackage='com.android.gallery3d', targetLabel='Gallery')
        d.write_prefs('c4_settings', dict(enabled=enabled, sound=sound, noticeAcknowledged=True))
        d.write_prefs('reminder_settings', appearance)
        d.write_prefs('timer_presets', {'items': json.dumps([preset])})
        d.write_prefs('quick_launch', {'targets': json.dumps([dict(package='com.android.gallery3d', label='Gallery')])})
        d.start(); d.shell('logcat', '-c')
        home_tree = d.dump()
        gear = next(n for n in home_tree.iter('node') if n.get('content-desc')=='设置')
        gx,gy,gr,gb=d.bounds(gear); gear_point=((gx+gr)/2,(gy+gb)/2)
        primary = next((n for n in home_tree.iter('node') if n.get('text') in ('开始倒计时', '开始正计时')), None)
        # In landscape the start button is legitimately below the first viewport.
        # Only the portrait handoff case uses this cached action.
        home_start_point = None if primary is None else tuple((a+b)/2 for a,b in zip(d.bounds(primary)[:2],d.bounds(primary)[2:]))
        d.tap('启动预设 C4 '+str(seconds), scroll=True)
        wait_for(lambda: d.overlay() and 'com.android.gallery3d' in d.foreground() and d.frame()[2]-d.frame()[0] < d.width, 8)
        time.sleep(.3)
        return d.overlay()[0], d.frame()

    def expand():
        old = d.frame()
        d.tapxy(*d.center())
        until = time.monotonic()+4
        previous = None
        stable = 0
        while time.monotonic() < until:
            current = d.frame()
            if current != old and current == previous:
                stable += 1
                if stable >= 2: return
            else: stable = 0
            previous = current
            time.sleep(.1)
        raise AssertionError(('C4 expansion not stable', old, previous))

    def device_geometry():
        x,y,r,b = d.frame()
        w,h = (r-x)/d.density-4, (b-y)/d.density-4
        bw=min(w,max(w*.8,164)); bx=w*.14 if w*.8>=164 else (w-bw)/2
        tight=h-18<286 or bw<210
        gap=4 if tight else 6; padding=5 if tight else 6
        pw=min(188,bw-12); ph=44*4+gap*3+padding*2
        sh=50 if tight else 64; fh=sh+10
        bottom=h-8; edge=min(8,max(0,(h-18-fh-gap-ph)/2))
        sy=max(10+edge,min(43,bottom-edge-ph-gap-fh))+5
        px=min(max(bx+6,bx+bw-pw-6),max(bx+6,(w-pw)/2+6))
        py=max(sy+sh+5+gap,min(126,bottom-edge-ph))
        return dict(x=x,y=y,w=w,h=h,gap=gap,kw=(pw-padding*2-gap*2)/3,kh=44,
                    left=px+padding,top=py+padding,screenY=sy,screenHeight=sh)

    def key_rect(char):
        g=device_geometry(); gap=g['gap']; kw=g['kw']; kh=g['kh']
        i = '123456789*0#'.index(char)
        return g['x']+(2+g['left']+(i%3)*(kw+gap))*d.density, g['y']+(2+g['top']+(i//3)*(kh+gap))*d.density, kw*d.density, kh*d.density

    def key_point(char):
        x,y,r,b = d.frame()
        kx,ky,kw,kh = key_rect(char)
        px,py = kx+kw/2, ky+kh/2
        assert x <= px < r and y <= py < b, ('Key outside frame', char, (px,py), (x,y,r,b))
        return px,py

    def key(char):
        px,py = key_point(char)
        d.shell('input', 'tap', int(px), int(py))

    def code(success_shot=False):
        points = [key_point(c) for c in '7355608']
        for index, (char, (px,py)) in enumerate(zip('7355608', points)):
            d.shell('input', 'tap', int(px), int(py))
            expected = 'C4 key result=DEFUSED' if index == 6 else f'C4 key result=CHANGED length={index+1}'
            try:
                wait_for(lambda: expected in logs(), 1.5)
            except AssertionError:
                (d.output/'c4-key-target-failure.txt').write_text(
                    repr(dict(index=index, digit=char, target=(px,py), frame=d.frame(), window=d.overlay()))+'\n'+d.shell('dumpsys','window','windows'), encoding='utf-8')
                raise
        if success_shot:
            d.shot('c4-defused-feedback')
        wait_for(lambda: d.overlay() is None, 5)
        assert logs().count('C4 key result=DEFUSED') == 1
        assert 'C4 explosion consumed' not in logs()
        ended = d.records()[-1]
        if last_sound:
            assert logs().count('C4 media start=defused') == 1
            assert 'C4 success window closed' in logs()
            wait_for(lambda: 'C4 success audio finished' in logs(), 4)
            assert d.records()[-1]['elapsedMillis'] == ended['elapsedMillis'], 'Voice was added to record'
            trace = logs()
            assert 'C4 media completed=defused' in trace, 'Success voice was truncated before natural completion'
            assert int(re.search(r'C4 media start=defused offset=\d+ target=(\d+)', trace)[1])==0, 'Success voice skipped its beginning'
            assert trace.index('C4 success window closed') < trace.index('C4 success audio finished')
            stamp = re.search(r'(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}).*C4 key result=DEFUSED', trace)[1]
            year = datetime.datetime.fromtimestamp(ended['startedWallMillis']/1000, datetime.timezone.utc).year
            success_wall = datetime.datetime.strptime(str(year)+'-'+stamp, '%Y-%m-%d %H:%M:%S.%f').replace(tzinfo=datetime.timezone.utc).timestamp()*1000
            assert abs(ended['startedWallMillis']+ended['elapsedMillis']-success_wall) < 500, 'Record included success voice'
        else:
            assert 'C4 media start=defused' not in logs()

    def handoff():
        print('C4: new timer preempts success voice and old callbacks cannot stop it...', flush=True)
        start(30)
        expand()
        d.start(); top()
        for char in '735560': key(char)
        kx,ky = key_point('8')
        assert home_start_point is not None, 'Handoff start action is not visible'
        hx,hy = home_start_point
        d.shell('sh', '-c', f"'input tap {int(kx)} {int(ky)}; sleep 0.6; input tap {int(hx)} {int(hy)}'")
        wait_for(lambda: d.overlay() is not None and 'C4 success window closed' in logs(), 4)
        time.sleep(2.3)
        assert d.overlay() is not None, 'Old success callback stopped the new session'
        assert 'C4 success audio cancelled' in logs(), 'New session did not preempt old voice'
        (d.output/'c4-new-session-handoff.log').write_text(logs(), encoding='utf-8')
        d.shot('c4-handoff-new-session')
        # Preset launch intentionally applies its 30s duration to the main page too;
        # the newly started timer is therefore also C4 and must use its normal code exit.
        d.shell('logcat','-c')
        expand(); code()


    def transparent():
        print('C4: transparent corner reaches a DIFFERENT-UID Gallery menu...', flush=True)
        start(30, sound=False)
        expand()
        # Settings hides overlays on this image. Its missing window cannot prove
        # pass-through. Gallery keeps the device visible and has a different UID.
        packages = d.shell('pm', 'list', 'packages', '-U')
        uid = lambda package: re.search(r'package:'+re.escape(package)+r' uid:(\d+)', packages)[1]
        assert uid(PACKAGE) != uid('com.android.gallery3d')
        tree = d.dump()
        menu = next(n for n in tree.iter('node') if n.get('content-desc') == 'More options')
        sx,sy,sr,sb = d.bounds(menu)
        target = ((sx+sr)/2, (sy+sb)/2)
        x,y,r,b = d.frame()
        # Align the real transparent right edge with the external control; base-size
        # isolation deliberately changes the old fixed fixture's expansion width.
        dx = target[0] + (r-x)*.02 - r
        if abs(dx) > 3*d.density:
            px,py=x+(r-x)*.5,y+(b-y)*.34
            d.shell('input','swipe',int(px),int(py),int(px+dx),int(py),350)
            moved=d.frame()
            assert abs(moved[0]-(x+dx)) <= 3, ('Small body drag was swallowed', (x,y,r,b), moved, dx)
            x,y,r,b=moved
        # The external menu control is in
        # the device's transparent top-right corner, INSIDE its rectangular frame.
        assert x < target[0] < r and y < target[1] < b, (target, d.frame())
        assert target[0] > x+(r-x)*.95 and target[1] < y+(b-y)*.2, (target, d.frame())
        before_downs = logs().count('C4 touch DOWN')
        d.shot('c4-transparent-before-tap')
        d.tapxy(*target)
        popup = d.dump()
        assert any(n.get('text') == 'Select album' for n in popup.iter('node')), 'Gallery menu did not receive transparent-corner tap'
        assert logs().count('C4 touch DOWN') == before_downs, 'Transparent corner reached the device'
        assert d.overlay() is not None
        d.shot('c4-transparent-cross-uid')
        d.shell('input', 'keyevent', 'KEYCODE_BACK')
        code()

    def keypad_layout():
        cases=[]
        full_size=None
        profiles=[('.75',.75,.3,'BLUE','CIRCLE',1.0),('1.0',1.0,.7,'BROWN','VERTICAL_CAPSULE',1.0),
                  ('1.5',1.5,1.0,'PURPLE','ROUNDED_RECTANGLE',1.0),('large-font',1.0,.45,'GREEN','CIRCLE',1.5)]
        for label,base,opacity,color,shape,font in profiles:
            d.shell('settings','put','system','font_scale',font)
            wid,folded=start(30, sound=False, base_size=base, opacity=opacity, color=color, shape=shape)
            dims=lambda frame:(frame[2]-frame[0],frame[3]-frame[1])
            fw,fh=dims(folded)
            assert (abs(fw-fh)<=2 if shape=='CIRCLE' else fh>fw if shape=='VERTICAL_CAPSULE' else fw>fh), 'Wrong compact C4 contour'
            expand()
            original=d.frame()
            if full_size is None: full_size=dims(original)
            assert dims(original)==full_size, 'Ordinary style or font scale changed C4 model dimensions'
            raw=d.shell('dumpsys','window','windows')
            block=raw.split('Window{'+wid+' u0 '+PACKAGE+'}:',1)[1].split('\n  Window #',1)[0]
            assert re.search(r'alpha=0\.7(?:\s|\})',block), 'C4 window inherited ordinary opacity'
            d.shot('c4-keypad-'+label)
            for length,char in enumerate('735560',1):
                key(char)
                wait_for(lambda: f'C4 text field=input length={length} fits=true' in logs(), 1)
                d.shot('c4-lcd-'+label+'-'+str(length))
            key('#')
            rects={char:key_rect(char) for char in '123456789*0#'}
            for x,y,w,h in rects.values():
                assert w>=44*d.density-.1 and h>=44*d.density-.1
            # Actual edge taps remain in their own slots, not neighbouring caps.
            x,y,w,h=rects['1']; d.tapxy(x+1*d.density,y+h/2)
            assert 'C4 key result=CHANGED length=1' in logs()
            key('#')
            for char in '1234567890':
                key(char); assert logs().split('C4 key result=')[-1].startswith('CHANGED length=1')
                key('#'); assert logs().split('C4 key result=')[-1].startswith('CHANGED length=0')
            key('1'); key('2'); key('*')
            before=logs().count('C4 key result=')
            a=rects['1']; b=rects['2']
            d.tapxy((a[0]+a[2]+b[0])/2,a[1]+a[3]/2)
            assert logs().count('C4 key result=')==before and d.frame()==original
            g=device_geometry()
            d.tapxy((original[0]+original[2])/2,g['y']+(2+g['screenY']+g['screenHeight']/2)*d.density)
            assert d.frame()!=original
            expand(); assert d.overlay()[0]==wid
            key('2'); assert logs().split('C4 key result=')[-1].startswith('CHANGED length=2')
            key('#')
            kx,ky=key_point('7'); before=logs().count('C4 key result='); old=d.frame()
            d.shell('input','swipe',int(kx),int(ky),int(kx+30*d.density),int(ky),300)
            assert d.frame()==old and logs().count('C4 key result=')==before
            code()
            trace=logs()
            assert 'fits=false' not in trace, 'Actual Compose glyph layout overflowed'
            assert all(f'C4 text field=input length={n} fits=true' in trace for n in range(8)), 'Missing 0..7 digit layout evidence'
            assert all('C4 text field=key-'+k+' length=0 fits=true' in trace for k in '123456789*0#')
            (d.output/('c4-layout-'+label+'.log')).write_text(trace,encoding='utf-8')
            cases.append(dict(baseSize=base,ordinaryOpacity=opacity,color=color,shape=shape,fontScale=font,
                              c4Opacity=.7,frame=original,allInputLengthsFit=True,realTwelveKeys=True,gapInert=True,
                              edgeTap=True,keyDragCancelled=True,foldRetainsInput=True))
        return dict(scope='independent C4 layout and interaction',cases=cases,audio='muted; timing/audio code unchanged')


    rotation = d.shell('settings', 'get', 'system', 'user_rotation').strip()
    auto_rotate = d.shell('settings', 'get', 'system', 'accelerometer_rotation').strip()
    font_scale = d.shell('settings','get','system','font_scale').strip()
    try:
        if focus == 'compact':
            cases=[]; full_size=None; compact_sizes={}
            profiles=[('ROUNDED_RECTANGLE',1.0,1.0,.7,'BLUE'),('VERTICAL_CAPSULE',1.0,1.0,.7,'BLUE'),
                      ('CIRCLE',1.0,1.0,.7,'BLUE'),('CIRCLE',1.5,1.0,.7,'BLUE'),
                      ('ROUNDED_RECTANGLE',1.0,1.5,.3,'PURPLE')]
            for shape,font,base,opacity,color in profiles:
                d.shell('settings','put','system','font_scale',font)
                wid, folded = start(30, sound=False, shape=shape,base_size=base,opacity=opacity,color=color)
                name=shape+('-large-font' if font>1 else '-ordinary-style' if base!=1 else '')
                d.shot('c4-compact-'+name)
                width,height=folded[2]-folded[0],folded[3]-folded[1]
                assert (abs(width-height)<=2 if shape=='CIRCLE' else height>width if shape=='VERTICAL_CAPSULE' else width>height)
                key_shape=(shape,font)
                if key_shape in compact_sizes:
                    assert (width,height)==compact_sizes[key_shape], 'Ordinary size leaked into compact C4'
                else: compact_sizes[key_shape]=(width,height)
                # A transparent contour corner cannot expand the device.
                d.tapxy(folded[0]+4*d.density,folded[1]+4*d.density)
                assert d.frame()==folded
                expand()
                full = d.frame()
                dims=(full[2]-full[0],full[3]-full[1])
                if full_size is None: full_size=dims
                assert dims==full_size, 'Compact contour leaked into full device geometry'
                d.shot('c4-full-'+name)
                g=device_geometry()
                d.tapxy((full[0]+full[2])/2,g['y']+(2+g['screenY']+g['screenHeight']/2)*d.density)
                before=d.frame()
                assert (before[2]-before[0],before[3]-before[1])==(width,height)
                x,y=d.center()
                d.shell('input','swipe',x,y,x-35*d.density,y+40*d.density,350)
                moved=d.frame()
                assert moved!=before and d.overlay()[0]==wid
                assert (moved[2]-moved[0],moved[3]-moved[1])==(width,height)
                d.shot('c4-compact-dragged-'+name)
                expand(); key('7'); key('3')
                g=device_geometry(); old=d.frame()
                d.tapxy((old[0]+old[2])/2,g['y']+(2+g['screenY']+g['screenHeight']/2)*d.density)
                expand(); key('5')
                assert 'C4 key result=CHANGED length=3' in logs()
                assert logs().count('C4 phase=ARMED')==1, 'Switching shape replayed C4 timeline'
                key('#'); code()
                cases.append(dict(shape=shape,fontScale=font,ordinaryBaseSize=base,ordinaryOpacity=opacity,ordinaryColor=color,
                                  foldedFrame=folded,fullFrame=full,
                                  compactStable=True,transparentCornerInert=True,drag=True,expand=True,inputSurvives=True))
            return dict(scope='capsule and circular C4 art; unchanged full model',cases=cases,audio='muted')
        if focus == 'keypad': return keypad_layout()
        if focus == 'handoff':
            handoff()
            return dict(scope='handoff-only diagnostic', newSessionPreemptsVoice=True)
        if focus == 'touch':
            transparent()
            return dict(scope='transparency-touch-only diagnostic')
        if focus == 'success':
            start(30); expand(); code()
            return dict(scope='success-voice-only diagnostic')
        assert focus is None
        print('C4: first-enable disclosure and defaults...', flush=True)
        d.stop(); d.write_prefs('onboarding', dict(completed=True)); d.write_prefs('c4_settings', {})
        d.start(); open_settings(); d.tap('提醒')
        assert '音效' not in texts()
        d.tap('C4 倒计时'); assert '开启 C4 倒计时' in texts()
        d.tap('取消'); assert '音效' not in texts()
        d.tap('C4 倒计时'); d.tap('开启'); assert '音效' in texts()
        d.tap('音效'); d.stop(); d.start(); open_settings(); d.tap('提醒')
        d.shot('c4-settings-disclosure')

        print('C4: 60s boundary, planting input drop, real keyboard and drag...', flush=True)
        wid, folded = start(60)
        d.shot('c4-folded')
        wait_for(lambda: 'C4 phase=PLANTING' in logs(), 22)
        assert d.frame()[2]-d.frame()[0] <= folded[2]-folded[0]+2, 'C4 inherited progress scale or auto-expanded'
        expand()
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
        key('7'); key('3')
        before_collapse = d.frame()
        x,y,r,b = before_collapse
        d.tapxy(x+(r-x)*.6, y+(b-y)*.2)
        compact = d.frame()
        assert compact[3]-compact[1] < before_collapse[3]-before_collapse[1]
        time.sleep(.6)
        assert d.frame()==compact, 'C4 compact size changed with progress'
        expand()
        assert d.frame()[2]-d.frame()[0] == before_collapse[2]-before_collapse[0]
        key('5')
        assert 'C4 key result=CHANGED length=3' in logs(), 'Collapse lost partial input'
        key('*'); key('#')
        assert 'C4 key result=CHANGED length=0' in logs()
        old = d.frame(); before = logs().count('C4 key result=')
        ax,ay=key_point('1'); bx,by=key_point('2')
        d.tapxy((ax+bx)/2, ay)
        assert d.frame()==old and logs().count('C4 key result=')==before, 'Key gap toggled, dragged, or typed'
        kx,ky = key_point('7')
        d.shell('input', 'swipe', int(kx), int(ky), int(kx+35*d.density), int(ky), 400)
        assert d.frame() == old and logs().count('C4 key result=') == before, 'Key drag moved window or typed'
        x,y,r,b = old
        d.shell('input', 'swipe', int(x+(r-x)*.5), int(y+(b-y)*.34), int(d.width*.38), int(d.height*.52), 400)
        assert d.overlay()[0] == wid and d.frame() != old
        code()
        wid,_ = start(40)
        expand()
        x,y,r,b = d.frame()
        d.shell('input', 'swipe', int(x+(r-x)*.5), int(y+(b-y)*.34), int(d.width*.38), int(d.height*.52), 400)
        audio_starts = logs().count('C4 media start=pre_explosion')
        d.start(); top()
        home_texts=texts()
        assert '结束计时' not in home_texts and '加时' not in home_texts
        notifications = d.shell('dumpsys', 'notification', '--noredact')
        records = re.split(r'(?=NotificationRecord\()', notifications)
        current = next(p for p in records if 'pkg=app.floatingfocus' in p and 'id=1 ' in p)
        assert '结束计时' not in current, 'C4 notification retained a normal stop action'
        (d.output/'c4-active-notification.txt').write_text(current, encoding='utf-8')
        d.tapxy(*gear_point)
        wait_for(lambda: 'SettingsActivity' in d.foreground(), 5)
        right_entry('提醒'); right_entry('C4 倒计时')
        assert '本次会话不受启用状态修改影响。' in texts()
        assert d.overlay()[0] == wid, 'Global switch ended the current session'
        assert logs().count('C4 media start=pre_explosion') == audio_starts, 'Navigation replayed audio'
        code(success_shot=True)
        assert d.records()[-1]['status'] == 'MANUAL' and d.records()[-1]['plannedMillis'] == 40_000
        assert logs().count('C4 media start=defused') == 1, 'Success voice missing or repeated'
        (d.output/'c4-defuse.log').write_text(logs(), encoding='utf-8')

        handoff()

        print('C4: revoke during success voice stops service resources...', flush=True)
        start(30)
        expand()
        for char in '735560': key(char)
        kx,ky=key_point('8')
        d.shell('sh', '-c', f"'input tap {int(kx)} {int(ky)}; sleep 0.6; appops set {PACKAGE} SYSTEM_ALERT_WINDOW deny'")
        wait_for(lambda: not has_timer_service(d.shell('dumpsys', 'activity', 'services', PACKAGE)), 3)
        assert 'C4 success audio cancelled' in logs()
        d.shell('appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'allow')

        def expiry(seconds, sound):
            print(f'C4: real {seconds}s expiry, sound={sound}...', flush=True)
            wid,initial_compact = start(seconds, sound=sound)
            assert 'C4 phase=ARMED' in logs()
            d.shot(f'c4-armed-{seconds}-{sound}')
            if sound:
                before_lock = re.findall(r'C4 media start=pre_explosion offset=(\d+)', logs())
                d.shell('input', 'keyevent', 'KEYCODE_SLEEP'); time.sleep(1)
                d.shell('input', 'keyevent', 'KEYCODE_WAKEUP'); d.shell('wm', 'dismiss-keyguard')
                after_lock = re.findall(r'C4 media start=pre_explosion offset=(\d+)', logs())
                assert all(int(offset) >= 44_439-seconds*1000-100 for offset in after_lock[len(before_lock):]), 'Lock/unlock replayed an earlier section'
            if not sound:
                wait_for(lambda: any(int(v)>=38439 for v in re.findall(r'C4 event=\d+ offset=(\d+)', logs())), seconds+3)
                full_sizes=[]
                for _ in range(2):
                    expand()
                    x,y,r,b=d.frame(); full_sizes.append((r-x,b-y))
                    d.tapxy(x+(r-x)*.6, y+(b-y)*.2)
                    x,y,r,b=d.frame()
                    assert (r-x,b-y)==(initial_compact[2]-initial_compact[0], initial_compact[3]-initial_compact[1])
                assert full_sizes[0]==full_sizes[1], 'Last20 C4 grew while toggling'
                d.shot('c4-last20-compact')
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

        transparent()

        print('C4: compact landscape keyboard and same-window rotation...', flush=True)
        d.shell('settings', 'put', 'system', 'accelerometer_rotation', 0)
        d.shell('settings', 'put', 'system', 'user_rotation', 1)
        time.sleep(.5)
        viewport()
        wid,_ = start(30, sound=False)
        expand()
        for char in '*#': key(char)
        d.shot('c4-landscape-keypad')
        d.shell('settings', 'put', 'system', 'user_rotation', 0); time.sleep(.6)
        viewport()
        assert d.overlay()[0] == wid
        d.shot('c4-portrait-after-rotation')
        code()

        print('C4: minimum base size keeps last keypad row reachable...', flush=True)
        start(30, sound=False, base_size=.75)
        expand()
        d.shot('c4-minimum-base-keypad')
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
                    keyGapNoAction=True, last20StableAcrossForms=True, minimumBaseKeypad=True, partialInputSurvivesToggle=True, defaultCompact=True, successVoiceSingleAndRecordFrozen=True,
                    newSessionPreemptsVoice=True, revokeDuringSuccessCleansService=True,
                    foldedShapes=True, stopwatchExcluded=True, audioEvidence='MediaPlayer request/position logs only; no listening assertion',
                    transparentPassThrough=dict(api=36, windowOpacity=.7, crossUid='Gallery menu, UIDs checked',
                                                pointInsideBoundingFrame=True, higherOpacityAndOlderApis='not tested'))
    finally:
        (d.output/'c4-snapshot-seconds.json').write_text(json.dumps(snapshot_seconds), encoding='utf-8')
        d.shell('appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'allow')
        d.shell('settings', 'put', 'system', 'user_rotation', rotation)
        d.shell('settings', 'put', 'system', 'accelerometer_rotation', auto_rotate)
        if font_scale=='null': d.shell('settings','delete','system','font_scale')
        else: d.shell('settings','put','system','font_scale',font_scale)


def c4_keypad(d):
    return c4(d, focus='keypad')


def c4_compact(d):
    return c4(d, focus='compact')
