"""ADB regression ported from the existing overlay and release smoke scripts.

Only the dedicated test emulator is accepted. Back up/restore app data; never run
on a phone. Screenshots are evidence for human review, not visual assertions.
"""
import argparse
import datetime
import hashlib
import io
import json
import re
import subprocess
import tarfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from source_state import ROOT, sha256, source_state
from device_settings import settings
from device_preferences import preferences
from device_c4 import c4
from device_onboarding import onboarding
from device_developer import developer
from device_history import history

PACKAGE = 'app.floatingfocus'


def archive_contents(raw):
    with tarfile.open(fileobj=io.BytesIO(raw)) as tar:
        return {m.name: tar.extractfile(m).read() for m in tar if m.isfile()}


class Device:
    def __init__(self, serial, output):
        self.serial, self.output = serial, output
        self.exe = str(ROOT / '.tools/android-sdk/platform-tools/adb.exe')
        self.launch_timeouts = 0
        self.hierarchy_retries = 0

    def adb(self, *args, data=None):
        return subprocess.run([self.exe, '-s', self.serial, *map(str, args)], input=data,
                              capture_output=True, check=True, timeout=45).stdout

    def shell(self, *args):
        return self.adb('shell', *args).decode('utf-8')

    def stop(self):
        self.shell('am', 'force-stop', PACKAGE)

    def start(self):
        launch = self.shell('am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
        with (self.output / 'activity-launch.txt').open('a', encoding='utf-8') as file:
            file.write(launch + '\n')
        if 'Status: ok' not in launch:
            # Some emulator sessions time out in ActivityManager's wait despite
            # a resumed interactive Activity. Never accept that status alone.
            assert 'Status: timeout' in launch and PACKAGE + '/.MainActivity' in self.foreground(), 'Activity launch failed: ' + launch.strip()
            tree = self.dump()
            assert any(n.get('text') == 'Floating Focus' for n in tree.iter('node')), 'Timed-out launch has no fresh home UI'
            self.launch_timeouts += 1
            print('WARN: ActivityManager wait timed out; foreground and fresh home UI verified. Startup latency is not a pass.', flush=True)

    def dump(self):
        # A failed UIAutomator dump may leave an old file behind and still exit 0.
        for attempt in range(3):
            self.shell('rm', '-f', '/sdcard/ff-regression.xml')
            status = self.shell('uiautomator', 'dump', '/sdcard/ff-regression.xml')
            if 'dumped to:' in status:
                break
            if attempt < 2:
                self.hierarchy_retries += 1
                time.sleep(.5)
        assert 'dumped to:' in status, 'UIAutomator did not produce a fresh hierarchy: ' + status.strip()
        raw = self.adb('shell', 'cat', '/sdcard/ff-regression.xml')
        (self.output / 'current.xml').write_bytes(raw)
        return ET.fromstring(raw)

    @staticmethod
    def bounds(node):
        return tuple(map(int, re.findall(r'-?\d+', node.get('bounds'))))

    def tapxy(self, x, y):
        self.shell('input', 'tap', int(x), int(y))
        time.sleep(.3)

    def tap(self, text, scroll=False):
        for _ in range(7 if scroll else 2):
            for node in self.dump().iter('node'):
                if text in (node.get('text'), node.get('content-desc')):
                    x, y, r, b = self.bounds(node)
                    if b > y and r > x:
                        self.tapxy((x+r)//2, (y+b)//2)
                        return
            if scroll:
                self.shell('input', 'swipe', self.width//2, int(self.height*.8), self.width//2, int(self.height*.3), 350)
        raise AssertionError(f'UI control not found: {text}')

    def overlay_tap(self, text, xf, yf):
        # Non-focusable TYPE_APPLICATION_OVERLAY is often omitted by UIAutomator.
        # Fractions refer to the compact inline regression fixture.
        for node in self.dump().iter('node'):
            if text in (node.get('text'), node.get('content-desc')):
                x, y, r, b = self.bounds(node)
                self.tapxy((x+r)//2, (y+b)//2)
                return
        x, y, r, b = self.frame()
        self.tapxy(x+(r-x)*xf, y+(b-y)*yf)

    def overlay(self):
        raw = self.shell('dumpsys', 'window', 'windows')
        match = re.search(r'Window\{([^ ]+) u0 app\.floatingfocus\}:.*?Frames:.*?frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', raw, re.S)
        return (match[1], tuple(map(int, match.groups()[1:]))) if match else None

    def frame(self):
        overlay = self.overlay()
        assert overlay, 'Overlay missing'
        return overlay[1]

    def center(self):
        x, y, r, b = self.frame()
        return (x+r)//2, (y+b)//2

    def shot(self, name):
        (self.output / (name + '.png')).write_bytes(self.adb('exec-out', 'screencap', '-p'))

    def foreground(self):
        return next((line.strip() for line in self.shell('dumpsys', 'activity', 'activities').splitlines() if 'topResumedActivity=' in line), '')

    def records(self):
        names = self.shell('run-as', PACKAGE, 'ls', 'files/timer_records').split()
        return sorted([json.loads(self.adb('shell', 'run-as', PACKAGE, 'cat', 'files/timer_records/' + n))
                       for n in names if n.endswith('.json')], key=lambda r: r['startedWallMillis'])

    def write_prefs(self, name, values):
        tree = ET.Element('map')
        for key, value in values.items():
            if isinstance(value, bool):
                ET.SubElement(tree, 'boolean', name=key, value=str(value).lower())
            elif isinstance(value, int):
                ET.SubElement(tree, 'int', name=key, value=str(value))
            elif isinstance(value, float):
                ET.SubElement(tree, 'float', name=key, value=str(value))
            else:
                ET.SubElement(tree, 'string', name=key).text = str(value)
        self.adb('shell', 'run-as', PACKAGE, 'tee', 'shared_prefs/' + name + '.xml', data=ET.tostring(tree))

    def seed(self, shape='ROUNDED_RECTANGLE', style='COLOR', minutes=15):
        self.stop()
        self.write_prefs('onboarding', {'completed': True})
        # Normal-overlay suites own their fixture; an optional C4 setting must not hijack inline expiry.
        self.write_prefs('c4_settings', dict(enabled=False, sound=True, noticeAcknowledged=True))
        settings = dict(shape=shape, style=style, color='BLUE', colorMode='CONTINUOUS',
                        sizeMode='CONTINUOUS', baseSize=1.5, opacity=.6, intensity=1.0)
        preset = dict(id='ff-regression', name='Regression', minutes=minutes, mode='COUNTDOWN',
                      targetPackage='com.android.gallery3d', targetLabel='Gallery', settings=settings)
        self.write_prefs('reminder_settings', settings)
        self.write_prefs('timer_presets', {'items': json.dumps([preset])})
        self.write_prefs('quick_launch', {'targets': json.dumps([{'package': 'com.android.gallery3d', 'label': 'Gallery'}])})
        self.start()
        self.tap('启动预设 Regression', scroll=True)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if 'com.android.gallery3d' in self.foreground() and self.overlay():
                break
            time.sleep(.25)
        assert 'com.android.gallery3d' in self.foreground(), 'Target App did not become foreground'
        assert self.overlay()
        # addView first exposes an invisible [0, inset] frame, before measured placement.
        # The unpositioned fixture has no saved anchor; wait for its actual right inset.
        deadline = time.monotonic() + 8
        while abs(self.frame()[2] - (self.width - round(16*self.density))) > 2 and time.monotonic() < deadline:
            time.sleep(.15)
        assert abs(self.frame()[2] - (self.width - round(16*self.density))) <= 2, 'Initial overlay placement not ready'

    def finish(self):
        self.start()
        self.tap('结束计时')
        self.wait_stopped()

    def wait_stopped(self):
        deadline = time.monotonic() + 3
        while self.overlay() is not None and time.monotonic() < deadline:
            time.sleep(.15)
        assert self.overlay() is None, 'Overlay did not stop'


def home(d):
    d.seed()
    d.finish()
    targets = [{'package': package, 'label': label} for package, label in [
        ('com.android.gallery3d', 'Gallery'), ('com.android.documentsui', 'Files'),
        ('com.android.messaging', 'Messaging')]
        if 'package:' in d.shell('pm', 'path', package)]
    assert len(targets) >= 2, 'Need two installed fixture Apps'
    d.stop()
    d.write_prefs('quick_launch', {'targets': json.dumps(targets)})
    fixture = dict(settings=dict(shape='ROUNDED_RECTANGLE', color='BLUE'), minutes=15, mode='COUNTDOWN',
                   targetPackage='com.android.gallery3d', targetLabel='Gallery')
    d.write_prefs('timer_presets', {'items': json.dumps([dict(fixture, id='home-'+str(i), name='Home '+str(i)) for i in range(3)])})
    d.start()
    texts = [n.get('text', '') for n in d.dump().iter('node')]
    assert '常规计时' in texts and not any('布局比较' in t for t in texts)
    d.shot('home-top')
    d.tap('自定义时长')
    fields = [n for n in d.dump().iter('node') if n.get('class') == 'android.widget.EditText']
    assert [n.get('text') for n in fields] == ['01', '00'], 'Custom time must default to one minute'
    d.tap('取消')
    d.tap('手动设置')
    assert any(n.get('text') == '常用时长' for n in d.dump().iter('node'))
    d.tap('返回')
    assert any(n.get('text') == '设置' for n in d.dump().iter('node')), 'Deep-link back did not reach settings home'
    d.shell('input', 'keyevent', 'KEYCODE_BACK')
    assert any(n.get('text') == 'Floating Focus' for n in d.dump().iter('node')), 'Settings did not return to home'
    d.tap('新建预设', scroll=True)
    name = next(n for n in d.dump().iter('node') if n.get('class') == 'android.widget.EditText')
    x,y,r,b = d.bounds(name)
    d.tapxy((x+r)//2, (y+b)//2)
    d.shell('input', 'keyevent', 123)
    d.shell('input', 'keyevent', *([67]*40))
    d.shell('input', 'text', 'HomeNew')
    d.shell('input', 'keyevent', 'KEYCODE_BACK')
    d.tap('Gallery')
    d.tap('保存')
    d.shot('home-preset-grid')
    d.tap('启动预设 HomeNew', scroll=True)
    assert d.overlay() and 'com.android.gallery3d' in d.foreground()
    d.finish()
    d.tap('添加 App', scroll=True)
    tree = d.dump()
    assert any(n.get('text') == '管理常用 App' for n in tree.iter('node'))
    assert any(n.get('checked') == 'true' for n in tree.iter('node')), 'Existing apps were not retained'
    candidate = next(n for n in tree.iter('node') if n.get('checkable') == 'true' and n.get('checked') == 'false')
    x,y,r,b = d.bounds(candidate)
    d.tapxy((x+r)//2, (y+b)//2)
    d.tap('保存')
    raw = ET.fromstring(d.adb('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/quick_launch.xml'))
    updated = json.loads(next(n.text for n in raw if n.get('name') == 'targets'))
    assert len(updated) == len(targets)+1 and {a['package'] for a in targets}.issubset({a['package'] for a in updated})
    d.shot('home-app-grid')
    tree = d.dump()
    icons = [n for n in tree.iter('node') if n.get('content-desc', '').startswith('打开 ') and n.get('content-desc', '').endswith(' 并计时')]
    # Four targets share a row; long names may increase the next row's height.
    assert len(icons) >= 3
    assert len({d.bounds(n)[1] for n in icons[:4]}) == 1, 'App shortcuts are not in the same four-column row'
    d.tap('打开 Gallery 并计时', scroll=True)
    assert d.overlay() and 'com.android.gallery3d' in d.foreground()
    d.start()
    for _ in range(4):
        d.shell('input', 'swipe', int(d.width*.1), int(d.height*.25), int(d.width*.1), int(d.height*.9), 500)
        time.sleep(.4)
    d.finish()
    assert not any(n.get('text') in ('使用与提醒说明', '开源许可', '开发者模式', '提醒实验室 · Debug') for n in d.dump().iter('node'))
    d.tap('设置')
    d.tap('使用与提醒说明', scroll=True)
    assert any('离开 App 后计时仍继续' in n.get('text', '') for n in d.dump().iter('node'))
    d.shot('settings-help')
    d.tap('返回')
    d.tap('开源许可', scroll=True)
    assert any('FloatingCountdownTimer' in n.get('text', '') for n in d.dump().iter('node'))
    d.shot('settings-licenses')
    d.tap('返回')
    d.shell('input', 'keyevent', 'KEYCODE_BACK')
    try:
        d.stop()
        d.shell('appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'deny')
        d.start()
        assert any(n.get('text') == '允许显示悬浮窗' for n in d.dump().iter('node'))
        d.shot('home-permission-needed')
        d.tap('开始倒计时')
        assert any(n.get('text') == '前往系统设置' for n in d.dump().iter('node'))
        assert d.overlay() is None
    finally:
        d.stop()
        d.shell('appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'allow')
    d.start()
    assert not any(n.get('text') == '允许显示悬浮窗' for n in d.dump().iter('node'))
    return dict(fourColumnGrid=True, addAppRetainsSelection=True, newPresetEntry=True, customDefaultOneMinute=True, quickLaunch=True, helpAndLicensesInSettings=True,
                contextualOverlayPermission=True, icons='screenshots require visual review')


def smoke(d):
    before = len(d.records())
    d.seed()
    d.shot('preset-start')
    d.finish()
    d.tap('时长记录', scroll=True)
    assert len(d.records()) == before + 1
    assert d.records()[-1]['status'] == 'MANUAL'
    d.shot('history')
    return {'oneTapPreset': True, 'manualStop': True, 'newRecord': True}


def edges(d):
    results = []
    for shape in ('ROUNDED_RECTANGLE', 'VERTICAL_CAPSULE', 'CIRCLE'):
        d.seed(shape)
        for edge, (x, y) in {'left': (0, d.height//2), 'right': (d.width-1, d.height//2),
                             'top': (d.width//2, 0), 'bottom': (d.width//2, d.height-1)}.items():
            cx, cy = d.center()
            d.shell('input', 'swipe', cx, cy, x, y, 450)
            time.sleep(.3)
            initial = d.frame()
            for attempt in range(2):
                # Exercise both the title and visible background away from digits.
                a, b, r, bottom = d.frame()
                d.tapxy((a+r)//2, (b+bottom)//2 if attempt == 0 else b+(bottom-b)*.18)
                expanded = d.frame()
                assert expanded != initial, (shape, edge, 'did not expand')
                # Expanded controls are one 48dp row. Fixture scale is 1.5.
                # Prefer semantics; non-focusable overlay may be absent from UIAutomator.
                node = next((n for n in d.dump().iter('node') if '点击收起操作' in n.get('content-desc', '')), None)
                if node is not None:
                    a, b, r, bottom = d.bounds(node)
                    d.tapxy((a+r)//2, (b+bottom)//2)
                else:
                    cx, cy = d.center()
                    d.tapxy(cx, cy - 24*d.density*1.5)
                # ADB queries are asynchronous; allow the collapse traversal to run.
                # Transient frame placement also needs a recorded-frame review.
                deadline = time.monotonic() + 3
                collapsed = d.frame()
                while max(abs(a-b) for a, b in zip(initial, collapsed)) > 1 and time.monotonic() < deadline:
                    time.sleep(.1)
                    collapsed = d.frame()
                assert max(abs(a-b) for a, b in zip(initial, collapsed)) <= 1, (shape, edge, initial, collapsed)
            results.append(dict(shape=shape, edge=edge, initial=initial, expanded=expanded, collapsed=collapsed))
            d.shot(shape + '-' + edge)
        d.finish()
    return results


def inline(d):
    d.seed('ROUNDED_RECTANGLE', 'SIZE', 1)
    initial, wid, front = d.frame(), d.overlay()[0], d.foreground()
    original = d.records()[-1]
    print('Inline: waiting for the real one-minute countdown...', flush=True)
    deadline = time.monotonic() + 75
    while time.monotonic() < deadline:
        if d.records()[-1].get('overtimeMillis', 0) > 0:
            break
        time.sleep(1)
    else:
        raise AssertionError('Countdown did not enter overtime')
    d.shot('inline-expired-compact')
    d.overlay_tap('加时', .27, .64)
    time.sleep(1)  # allow embedded native wheels and window measurement to settle
    d.shot('inline-add-default-two')
    editor = d.frame()
    x,y,r,b = editor
    d.shell('input', 'swipe', int(x+(r-x)*.65), int(y+(b-y)*.68), int(x+(r-x)*.65), int(y+(b-y)*.36), 650)
    time.sleep(.5)
    assert d.frame() == editor, 'Wheel gesture moved overlay'
    d.shot('inline-wheel-scrolled')
    d.overlay_tap('\u53d6\u6d88', .35, .82)
    assert d.records()[-1]['plannedMillis'] == 60000
    d.overlay_tap('加时', .27, .64)
    time.sleep(1)
    x,y,r,b = d.frame()
    d.shell('input', 'swipe', int(x+(r-x)*.35), int(y+(b-y)*.36), int(x+(r-x)*.35), int(y+(b-y)*.68), 650)
    time.sleep(.6)
    d.shot('inline-wheel-one-minute')
    d.overlay_tap('\u786e\u8ba4\u52a0\u65f6', .65, .82)
    time.sleep(1.2)
    added = d.records()[-1]
    assert added['id'] == original['id'] and added['plannedMillis'] == 120000
    assert d.overlay()[0] == wid and d.foreground() == front
    now = d.frame()
    assert abs((now[2]-now[0])-(initial[2]-initial[0])) <= 3
    assert abs((now[3]-now[1])-(initial[3]-initial[1])) <= 3
    d.shot('inline-extended')
    print('Inline: checking second completion and dynamic growth...', flush=True)
    time.sleep(35)
    grown = d.frame()
    assert grown[2]-grown[0] > now[2]-now[0] and grown[3]-grown[1] > now[3]-now[1]
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        log = d.shell('logcat', '-d', '-s', 'FloatingFocus:*')
        if log.count('Timer completed:') == 2:
            break
        time.sleep(1)
    else:
        raise AssertionError('Expected exactly two completion events')
    assert log.count('Timer extended:') == 1
    d.shot('inline-second-expiry')
    # End in the overlay: the large expired window can cover the Activity's button.
    d.overlay_tap('结束计时', .68, .64)
    d.wait_stopped()
    final = d.records()[-1]
    assert final['id'] == original['id'] and final['status'] == 'MANUAL'
    return dict(sameWindow=True, sameForeground=True, original=original, added=added, final=final,
                initialFrame=initial, extendedFrame=now, grownFrame=grown)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', default='emulator-5554')
    p.add_argument('--suite', choices=['smoke', 'edges', 'inline', 'settings', 'home', 'preferences', 'c4', 'onboarding', 'developer', 'history', 'all'], default='all')
    p.add_argument('--apk', type=Path, default=ROOT / 'app/build/outputs/apk/debug/app-debug.apk')
    p.add_argument('--allow-test-data-changes', action='store_true')
    args = p.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial) or not args.allow_test_data_changes:
        p.error('Use a dedicated emulator and explicitly pass --allow-test-data-changes.')
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    out = ROOT / '.tools/device-checks' / stamp
    out.mkdir(parents=True)
    d = Device(args.serial, out)
    result = dict(schemaVersion=1, source=source_state(), apkSha256=sha256(args.apk), device=args.serial,
                  status='failed', passedSuites=[], output=str(out), details={})
    backed_up = False
    try:
        assert 'FloatingFocus_API_36' in d.shell('getprop', 'ro.boot.qemu.avd_name'), 'Use FloatingFocus_API_36 only'
        assert d.shell('getprop', 'sys.boot_completed').strip() == '1', 'Emulator not booted'
        d.width, d.height = map(int, re.findall(r'(\d+)x(\d+)', d.shell('wm', 'size'))[-1])
        d.density = int(re.findall(r'\d+', d.shell('wm', 'density'))[-1]) / 160
        assert 'package:' in d.shell('pm', 'path', 'com.android.gallery3d'), 'Gallery fixture App missing'
        # Install only if absent. Reinstall after backup exercises upgrade preservation.
        if 'package:'+PACKAGE not in d.shell('pm', 'list', 'packages', PACKAGE).splitlines():
            assert 'Success' in d.adb('install', str(args.apk)).decode()
        assert d.overlay() is None, 'End the existing test timer before regression'
        d.stop()
        assert d.shell('run-as', PACKAGE, 'pwd').strip() in ('/data/user/0/' + PACKAGE, '/data/data/' + PACKAGE)
        d.shell('run-as', PACKAGE, 'mkdir', '-p', 'shared_prefs', 'files/timer_records')
        archive = d.adb('exec-out', 'run-as', PACKAGE, 'tar', '-cf', '-', 'shared_prefs', 'files')
        (out / 'app-data-before.tar').write_bytes(archive)
        backed_up = True
        assert 'Success' in d.adb('install', '-r', str(args.apk)).decode()
        installed = [line.removeprefix('package:') for line in d.shell('pm', 'path', PACKAGE).splitlines() if line.startswith('package:')]
        assert len(installed) == 1, 'Expected a single installed base APK'
        assert hashlib.sha256(d.adb('exec-out', 'cat', installed[0])).hexdigest() == result['apkSha256'], 'Installed APK differs from input'
        after = d.adb('exec-out', 'run-as', PACKAGE, 'tar', '-cf', '-', 'shared_prefs', 'files')
        # Tar metadata/order can vary; compare member contents instead.
        assert archive_contents(archive) == archive_contents(after), 'Install changed stored data'
        result['upgradeDataPreserved'] = True
        # These grants are test-emulator setup only, not production app permission requests.
        d.shell('appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'allow')
        d.shell('pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
        for suite in (['smoke', 'edges', 'inline', 'settings', 'home', 'preferences', 'c4', 'onboarding', 'developer', 'history'] if args.suite == 'all' else [args.suite]):
            d.shell('logcat', '-c')
            result['details'][suite] = globals()[suite](d)
            runtime = d.shell('logcat', '-d', '-s', 'AndroidRuntime:*')
            (out / (suite + '-runtime.txt')).write_text(runtime, encoding='utf-8')
            assert 'FATAL EXCEPTION' not in runtime
            result['passedSuites'].append(suite)
            print('PASS ' + suite, flush=True)
        assert source_state() == result['source'], 'Source changed during device checks'
        assert sha256(args.apk) == result['apkSha256'], 'APK changed during device checks'
        result['status'] = 'passed'
    except Exception as error:
        result['error'] = f'{type(error).__name__}: {error}'
        try:
            d.shot('failure')
        except Exception:
            pass
        raise
    finally:
        if backed_up:
            try:
                d.stop()
                # Explicit app-private paths, after run-as pwd verification; restore exact backup.
                d.shell('run-as', PACKAGE, 'rm', '-rf', 'shared_prefs', 'files')
                d.adb('shell', 'run-as', PACKAGE, 'tar', '-xf', '-', data=archive)
                restored = d.adb('exec-out', 'run-as', PACKAGE, 'tar', '-cf', '-', 'shared_prefs', 'files')
                assert archive_contents(archive) == archive_contents(restored), 'Restored data differs from backup'
                result['appDataRestored'] = True
            except Exception as error:
                result['status'], result['restoreError'] = 'failed', str(error)
        result['activityWaitTimeouts'] = d.launch_timeouts
        result['hierarchyRetries'] = d.hierarchy_retries
        (out / 'results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print('Result: ' + str(out / 'results.json'), flush=True)
    if result['status'] != 'passed':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
