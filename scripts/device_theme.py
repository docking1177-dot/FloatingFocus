"""System night-mode switching, semantic surfaces, native wheels and C4 exclusion."""
import json
import struct
import time

PACKAGE = 'app.floatingfocus'


def theme(d):
    def texts(): return [n.get('text', '') for n in d.dump().iter('node')]
    def top():
        for _ in range(3):
            d.shell('input', 'swipe', int(d.width*.03), int(d.height*.25), int(d.width*.03), int(d.height*.85), 200)
    def background(dark, label):
        # Read the emulator's real raw framebuffer; no image/test dependency.
        raw = d.adb('exec-out', 'screencap')
        width, height, format_ = struct.unpack_from('<III', raw)
        assert format_ == 1, ('Expected RGBA_8888', format_)
        offset = len(raw) - width*height*4
        assert offset in (12, 16), ('Unknown framebuffer header', offset)
        rgb = tuple(raw[offset + (height//2*width+2)*4:][:3])
        assert (sum(rgb) < 150 if dark else sum(rgb) > 600), (label, dark, rgb)
        d.shot(label)
        return rgb
    def choose_night(value):
        d.shell('cmd', 'uimode', 'night', value)
        time.sleep(.8)
    def write_record():
        now = int(d.shell('date', '+%s').strip())*1000
        record = dict(id='theme-record', packageName='com.android.gallery3d', appName='Gallery',
                      startedWallMillis=now-120000, elapsedMillis=90000, plannedMillis=120000,
                      mode='COUNTDOWN', status='MANUAL', overtimeMillis=0)
        d.adb('shell', 'run-as', PACKAGE, 'tee', 'files/timer_records/theme-record.json',
              data=json.dumps(record).encode('utf-8'))

    night = d.shell('cmd', 'uimode', 'night').strip().split()[-1]
    font = d.shell('settings', 'get', 'system', 'font_scale').strip()
    try:
        d.seed(); d.finish(); d.stop()
        d.write_prefs('c4_settings', dict(enabled=True, sound=True, noticeAcknowledged=True))
        d.write_prefs('quick_durations', dict(seconds='300,600,900,1800', startup_mode='COUNTDOWN', startup_seconds=900))
        write_record()
        cases = []
        for dark in (False, True):
            name = 'dark' if dark else 'light'
            print('Theme: '+name+' home, wheels, settings, dialogs and history...', flush=True)
            choose_night('yes' if dark else 'no')
            d.start(); top(); d.tap('倒计时'); home = background(dark, 'theme-'+name+'-home')
            d.tap('自定义时长'); d.shot('theme-'+name+'-duration-wheel'); d.tap('取消')
            d.tap('手动设置'); background(dark, 'theme-'+name+'-defaults')
            d.shell('input', 'keyevent', 'KEYCODE_BACK')
            d.tap('设置'); background(dark, 'theme-'+name+'-settings')
            d.tap('悬浮窗'); background(dark, 'theme-'+name+'-appearance')
            # Switch the real system mode while the parameter page stays open.
            choose_night('no' if dark else 'yes')
            assert '悬浮窗' in texts() and '.SettingsActivity' in d.foreground()
            background(not dark, 'theme-'+name+'-live-switch')
            choose_night('yes' if dark else 'no')
            d.tap('返回'); d.tap('提醒')
            assert any('正计时不触发' in t for t in texts())
            background(dark, 'theme-'+name+'-reminders')
            d.tap('C4 倒计时'); d.tap('C4 倒计时')  # Existing acknowledged toggle, no new permission.
            d.tap('返回'); d.tap('使用与提醒说明'); background(dark, 'theme-'+name+'-help')
            d.tap('返回'); d.tap('返回'); d.tap('时长记录', scroll=True)
            background(dark, 'theme-'+name+'-history')
            d.tap('统计说明', scroll=True); d.shot('theme-'+name+'-history-dialog'); d.tap('知道了')
            d.shell('input', 'keyevent', 'KEYCODE_BACK')
            cases.append(dict(mode=name, homeRgb=home, actualSystemSwitch=True, nativeWheels=True, dialogs=True))

        print('Theme: active stopwatch with C4 enabled, disabled controls and recovery...', flush=True)
        choose_night('yes'); d.start(); top(); d.tap('正计时')
        d.shell('logcat', '-c')
        began = time.monotonic(); d.tap('开始正计时'); time.sleep(.5)
        record = d.records()[-1]
        assert record['mode'] == 'STOPWATCH' and record['plannedMillis'] == 0
        tree = d.dump(); parents = {c:p for p in tree.iter('node') for c in p}
        node = next(n for n in tree.iter('node') if n.get('text') == '倒计时')
        enabled = node
        while enabled is not None and enabled.get('enabled') != 'false': enabled = parents.get(enabled)
        assert enabled is not None and enabled.get('enabled') == 'false'
        d.shot('theme-dark-active-disabled')
        d.tap('倒计时')  # Disabled: must not change a running stopwatch.
        choose_night('no'); d.shot('theme-light-active-disabled')
        choose_night('yes')
        # Preserve the same record through system-driven Activity recreation.
        assert d.records()[-1]['id'] == record['id']
        time.sleep(max(0, 46-(time.monotonic()-began)))
        trace = d.shell('logcat', '-d', '-s', 'FloatingFocus:*')
        assert all(s not in trace for s in ('C4 phase=', 'C4 event=', 'C4 explosion consumed', 'C4 media', 'C4 audio'))
        assert d.overlay() and d.records()[-1]['mode'] == 'STOPWATCH'
        (d.output/'theme-stopwatch.log').write_text(trace, encoding='utf-8')
        d.finish()

        # Read failure is an actual store error, not fabricated zero or a preview.
        d.stop()
        d.adb('shell', 'run-as', PACKAGE, 'tee', 'files/timer_records/theme-broken.json', data=b'{broken')
        d.start(); d.tap('时长记录', scroll=True)
        assert '重试' in texts(); d.shot('theme-dark-history-error')
        choose_night('no'); assert '重试' in texts(); d.shot('theme-light-history-error')
        d.stop(); d.shell('run-as', PACKAGE, 'rm', 'files/timer_records/theme-broken.json')

        choose_night('yes'); d.shell('settings', 'put', 'system', 'font_scale', '1.5')
        d.start(); d.tap('设置'); d.tap('提醒'); d.shot('theme-dark-large-reminders')
        assert '返回' in texts()
        return dict(cases=cases, followsSystem=True, settingsPageRestored=True, nativeDialogReadable='screenshots reviewed separately',
                    runningStopwatch46SecondsWithoutC4=True, sameSessionAcrossThemeSwitch=True,
                    actualReadError=True, largeFont=1.5, appPermissionsAdded=False)
    finally:
        d.stop(); d.shell('cmd', 'uimode', 'night', night)
        if font == 'null': d.shell('settings', 'delete', 'system', 'font_scale')
        else: d.shell('settings', 'put', 'system', 'font_scale', font)
