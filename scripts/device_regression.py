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

PACKAGE = 'app.floatingfocus'


def archive_contents(raw):
    with tarfile.open(fileobj=io.BytesIO(raw)) as tar:
        return {m.name: tar.extractfile(m).read() for m in tar if m.isfile()}


class Device:
    def __init__(self, serial, output):
        self.serial, self.output = serial, output
        self.exe = str(ROOT / '.tools/android-sdk/platform-tools/adb.exe')

    def adb(self, *args, data=None):
        return subprocess.run([self.exe, '-s', self.serial, *map(str, args)], input=data,
                              capture_output=True, check=True, timeout=45).stdout

    def shell(self, *args):
        return self.adb('shell', *args).decode('utf-8')

    def stop(self):
        self.shell('am', 'force-stop', PACKAGE)

    def start(self):
        launch = self.shell('am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
        assert 'Status: ok' in launch, 'Activity launch failed: ' + launch.strip()

    def dump(self):
        self.shell('uiautomator', 'dump', '/sdcard/ff-regression.xml')
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
        # Fractions are the existing vertical-capsule inline regression fixture.
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
            if isinstance(value, float):
                ET.SubElement(tree, 'float', name=key, value=str(value))
            else:
                ET.SubElement(tree, 'string', name=key).text = str(value)
        self.adb('shell', 'run-as', PACKAGE, 'tee', 'shared_prefs/' + name + '.xml', data=ET.tostring(tree))

    def seed(self, shape='ROUNDED_RECTANGLE', style='COLOR', minutes=15):
        self.stop()
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

    def finish(self):
        self.start()
        self.tap('结束计时')
        self.wait_stopped()

    def wait_stopped(self):
        deadline = time.monotonic() + 3
        while self.overlay() is not None and time.monotonic() < deadline:
            time.sleep(.15)
        assert self.overlay() is None, 'Overlay did not stop'


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
            for _ in range(2):
                d.tapxy(*d.center())
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
                collapsed = d.frame()
                assert max(abs(a-b) for a, b in zip(initial, collapsed)) <= 1, (shape, edge, initial, collapsed)
            results.append(dict(shape=shape, edge=edge, initial=initial, expanded=expanded, collapsed=collapsed))
            d.shot(shape + '-' + edge)
        d.finish()
    return results


def inline(d):
    d.seed('VERTICAL_CAPSULE', 'SIZE', 1)
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
    d.overlay_tap('加时', .27, .62)
    d.overlay_tap('+5 分', .32, .58)
    d.overlay_tap('取消', .28, .705)
    assert d.records()[-1]['plannedMillis'] == 60000
    d.overlay_tap('加时', .27, .62)
    for _ in range(4):
        d.overlay_tap('减少一分钟', .23, .455)
    d.overlay_tap('确认加时', .66, .705)
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
    d.overlay_tap('结束计时', .68, .62)
    d.wait_stopped()
    final = d.records()[-1]
    assert final['id'] == original['id'] and final['status'] == 'MANUAL'
    return dict(sameWindow=True, sameForeground=True, original=original, added=added, final=final,
                initialFrame=initial, extendedFrame=now, grownFrame=grown)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', default='emulator-5554')
    p.add_argument('--suite', choices=['smoke', 'edges', 'inline', 'all'], default='all')
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
        if 'package:' not in d.shell('pm', 'path', PACKAGE):
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
        for suite in (['smoke', 'edges', 'inline'] if args.suite == 'all' else [args.suite]):
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
        (out / 'results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print('Result: ' + str(out / 'results.json'), flush=True)
    if result['status'] != 'passed':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
