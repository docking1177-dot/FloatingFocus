"""First-run tutorial, actual system permission navigation, replay and migration on the dedicated AVD."""
import hashlib
import json
import time
import xml.etree.ElementTree as ET

PACKAGE = 'app.floatingfocus'


def onboarding(d):
    def completed():
        root = ET.fromstring(d.adb('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/onboarding.xml'))
        return next(n.get('value') == 'true' for n in root if n.get('name') == 'completed')

    def page(number):
        texts = [(n.get('text', ''), n.get('content-desc', '')) for n in d.dump().iter('node')]
        assert any(desc == f'使用教程，第 {number} 页，共 4 页' for _, desc in texts), (number, texts)

    def back_from_system():
        for _ in range(3):
            d.shell('input', 'keyevent', 'KEYCODE_BACK')
            started = time.monotonic()
            while time.monotonic()-started < 12:
                foreground = d.foreground()
                if PACKAGE in foreground:
                    return
                # A surviving Settings list needs another Back; an app being recreated
                # must finish resuming first, otherwise a second Back navigates its pager.
                if 'com.android.settings' in foreground and time.monotonic()-started > 3:
                    break
                time.sleep(.3)
        raise AssertionError('Did not return from system settings')

    def functional_data():
        names = d.shell('run-as', PACKAGE, 'find', 'shared_prefs', 'files', '-type', 'f').splitlines()
        result = {name: hashlib.sha256(d.adb('exec-out', 'run-as', PACKAGE, 'cat', name)).hexdigest()
                  for name in names if (name.startswith('shared_prefs/') and name.endswith('.xml') and name != 'shared_prefs/onboarding.xml')
                  or (name.startswith('files/timer_records/') and name.endswith('.json'))}
        index = len(list(d.output.glob('functional-data-*.json')))
        (d.output / f'functional-data-{index}.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        return result

    night = d.shell('cmd', 'uimode', 'night').strip().split()[-1]
    rotation = d.shell('settings', 'get', 'system', 'user_rotation').strip()
    auto_rotate = d.shell('settings', 'get', 'system', 'accelerometer_rotation').strip()
    try:
        d.stop()
        # The runner has already backed up these exact app-private paths. No pm clear/uninstall.
        d.shell('run-as', PACKAGE, 'rm', '-rf', 'shared_prefs', 'files')
        d.shell('run-as', PACKAGE, 'mkdir', '-p', 'shared_prefs', 'files/timer_records')
        d.shell('appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'deny')
        d.shell('cmd', 'uimode', 'night', 'no')
        d.start()
        page(1)
        assert not completed() and not d.overlay() and not d.records()
        d.shot('onboarding-light-welcome')
        d.shell('input', 'swipe', int(d.width*.82), int(d.height*.42), int(d.width*.18), int(d.height*.42), 500)
        page(2)
        d.shot('onboarding-reminder')
        d.tap('下一步'); page(3)
        d.shot('onboarding-personalization')
        d.shell('input', 'swipe', int(d.width*.18), int(d.height*.42), int(d.width*.82), int(d.height*.42), 500)
        page(2)
        d.shell('input', 'keyevent', 'KEYCODE_BACK'); page(1)
        # Back on page one exits without falsely completing first use.
        d.shell('input', 'keyevent', 'KEYCODE_BACK')
        d.start(); page(1)
        d.tap('跳过'); page(4)
        assert not completed()
        d.shot('onboarding-permission-ungranted')
        d.tapxy(d.width//2, int(d.height*.42)) # The pictured switch must never grant permission.
        assert PACKAGE in d.foreground() and not completed()
        d.tap('授予权限')
        assert 'com.android.settings' in d.foreground(), 'Expected real Android settings'
        d.shot('onboarding-real-system-settings')
        back_from_system(); page(4)
        assert not completed()
        assert any(n.get('text') == '授予权限' for n in d.dump().iter('node'))
        # Test browsing without authorization, then replay with all functional data intact.
        d.tap('暂不授权')
        assert completed() and not d.overlay()
        d.tap('开始倒计时')
        assert not d.overlay()
        d.tap('稍后')
        d.stop(); d.start()
        assert any(n.get('text') == '常规计时' for n in d.dump().iter('node'))
        # Independent fresh-incomplete fixture, to verify a successful grant does not itself complete the tutorial.
        d.stop(); d.write_prefs('onboarding', dict(completed=False)); d.start(); page(1)
        d.tap('跳过'); page(4)
        d.tap('授予权限')
        tree = d.dump()
        toggles = [n for n in tree.iter('node') if n.get('checkable') == 'true']
        if not toggles:
            d.tap('Floating Focus', scroll=True)
            toggles = [n for n in d.dump().iter('node') if n.get('checkable') == 'true']
        assert len(toggles) == 1, 'Expected one actual Android overlay permission switch'
        x,y,r,b = d.bounds(toggles[0])
        if toggles[0].get('checked') != 'true':
            d.tapxy((x+r)//2, (y+b)//2)
        d.shot('onboarding-real-system-granted')
        # Simulate a process interruption while the real Settings app is foreground.
        old_pid = d.shell('pidof', PACKAGE).strip()
        d.shell('am', 'kill', PACKAGE)
        time.sleep(.5)
        back_from_system(); page(4)
        assert d.shell('pidof', PACKAGE).strip() != old_pid
        assert not completed() # Authorization alone must not complete the tutorial.
        assert any(n.get('text') == '已授权' for n in d.dump().iter('node'))
        d.shot('onboarding-granted')
        d.tap('开始使用')
        assert completed()
        d.seed(); d.finish()
        before = functional_data()
        d.tap('设置'); d.tap('使用教程'); page(1)
        d.shell('input', 'keyevent', 'KEYCODE_BACK')
        assert any(n.get('text') == '使用教程' for n in d.dump().iter('node'))
        assert functional_data() == before
        d.tap('使用教程'); d.tap('跳过'); page(4)
        assert any(n.get('text') == '已授权' for n in d.dump().iter('node'))
        d.tap('开始使用')
        assert functional_data() == before
        # Scoped dark theme and short landscape viewport; no real floating timer is started.
        d.shell('cmd', 'uimode', 'night', 'yes')
        d.stop(); d.start(); d.tap('设置'); d.tap('使用教程'); page(1)
        d.shot('onboarding-dark-welcome')
        d.tap('跳过'); page(4)
        d.shot('onboarding-dark-permission')
        d.shell('settings', 'put', 'system', 'accelerometer_rotation', 0)
        d.shell('settings', 'put', 'system', 'user_rotation', 1)
        time.sleep(.8)
        page(4)
        d.shot('onboarding-landscape')
        assert any(n.get('text') == '开始使用' and d.bounds(n)[3] > d.bounds(n)[1] for n in d.dump().iter('node'))
        d.shell('settings', 'put', 'system', 'user_rotation', 0)
        time.sleep(.5)
        d.tap('开始使用')
        # Revoke outside FF, then resume. Completion cannot be used as a permission cache.
        d.stop()
        d.shell('appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'deny')
        d.start()
        assert completed()
        d.tap('开始倒计时'); assert not d.overlay(); d.tap('稍后')
        d.tap('设置'); d.tap('使用教程'); d.tap('跳过'); page(4)
        assert any(n.get('text') == '授予权限' for n in d.dump().iter('node'))
        d.tap('暂不授权')
        # A legacy installation with real saved data is migrated without forced onboarding.
        d.stop()
        d.shell('run-as', PACKAGE, 'rm', 'shared_prefs/onboarding.xml')
        d.start()
        assert completed() and any(n.get('text') == '常规计时' for n in d.dump().iter('node'))
        assert functional_data() == before
        activity_log = d.shell('logcat', '-d', '-s', 'ActivityManager:E')
        (d.output / 'onboarding-activity-manager.txt').write_text(activity_log, encoding='utf-8')
        assert 'ANR in '+PACKAGE not in activity_log, 'Tutorial triggered an application ANR'
        return dict(firstRun=True, swipesAndButtonsAndBack=True, skipToPermission=True,
                    deferAndStartBlocked=True, actualSystemGrantAndReturn=True, backgroundProcessRecreated=True,
                    replayPreservesData=True, permissionRevocation=True, legacyMigration=True,
                    darkAndLandscape=True, noTutorialTimerOrSound=True)
    finally:
        d.shell('appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'allow')
        d.shell('cmd', 'uimode', 'night', night)
        d.shell('settings', 'put', 'system', 'user_rotation', rotation)
        d.shell('settings', 'put', 'system', 'accelerometer_rotation', auto_rotate)
