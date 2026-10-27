"""Settings navigation, independent effects and per-preset placement regression."""
import json
import time
import struct
import xml.etree.ElementTree as ET

PACKAGE = 'app.floatingfocus'


def settings(d):
    def prefs(name):
        tree = ET.fromstring(d.adb('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/' + name + '.xml'))
        result = {}
        for node in tree:
            if node.tag == 'boolean':
                value = node.get('value') == 'true'
            elif node.tag == 'float':
                value = float(node.get('value'))
            else:
                value = node.text
            result[node.get('name')] = value
        return result

    def presets():
        return json.loads(prefs('timer_presets')['items'])

    def launch(name):
        d.start()
        d.tap('启动预设 ' + name, scroll=True)
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if 'com.android.gallery3d' in d.foreground():
                overlay = d.overlay()
                if overlay:
                    x, y, r, b = overlay[1]
                    # addView initially exposes a full-display provisional frame.
                    # These base-size horizontal fixtures must be measured before
                    # center() can be used as the start of a real window drag.
                    if 0 < b-y < r-x < d.width:
                        return
            time.sleep(.2)
        raise AssertionError('Preset did not launch target App with a measured overlay')

    def drag_to(x, y):
        cx, cy = d.center()
        d.shell('input', 'swipe', cx, cy, x, y, 450)
        time.sleep(.4)

    def close_to(expected):
        actual = d.frame()
        assert max(abs(a-b) for a, b in zip(expected, actual)) <= 2, (expected, actual)

    def toggle(label):
        # Conditional mode controls change scroll offsets. Re-enter from the list
        # so each control lookup starts at the top instead of searching downward
        # for a control which may now be above the viewport.
        d.shell('input', 'keyevent', 'KEYCODE_BACK')
        d.tap('悬浮窗')
        d.tap(label, scroll=True)

    d.seed(style='SIZE')  # Old style-only fixture migrates without enabling color.
    d.finish()
    d.tap('设置')
    tree = d.dump()
    assert any(n.get('text') == '悬浮窗' for n in tree.iter('node'))
    assert not any('预览进度' in n.get('text', '') for n in tree.iter('node'))
    d.shot('settings-list')
    d.tap('悬浮窗')
    # Exercise four combinations through actual controls; preview stays pinned.
    toggle('变色')
    assert prefs('reminder_settings')['colorEnabled'] and prefs('reminder_settings')['sizeEnabled']
    d.shot('both-effects-settings')
    toggle('变尺寸')
    assert prefs('reminder_settings')['colorEnabled'] and not prefs('reminder_settings')['sizeEnabled']
    toggle('变色')
    assert not prefs('reminder_settings')['colorEnabled'] and not prefs('reminder_settings')['sizeEnabled']
    toggle('变色')
    toggle('变尺寸')
    expected = prefs('reminder_settings')
    assert expected['colorEnabled'] and expected['sizeEnabled']
    d.shell('input', 'keyevent', 'KEYCODE_BACK')
    assert any(n.get('text') == '悬浮窗' for n in d.dump().iter('node'))
    d.shell('input', 'keyevent', 'KEYCODE_BACK')
    d.stop()
    d.start()
    d.tap('设置')
    d.tap('悬浮窗')
    assert prefs('reminder_settings') == expected
    # Set preview progress to 100%; same renderer should show the enlarged red shape.
    node = next(n for n in d.dump().iter('node') if n.get('content-desc') == '预览进度')
    x, y, r, b = d.bounds(node)
    d.tapxy(r-30, (y+b)//2)
    d.shot('both-effects-preview')
    d.stop()

    appearance = dict(expected, baseSize=1.0, opacity=1.0, colorMode='STEPS', sizeMode='STEPS')
    rows = [dict(id=key, name=name, minutes=15, mode='COUNTDOWN', settings=appearance,
                 targetPackage='com.android.gallery3d', targetLabel='Gallery')
            for key, name in [('position-a', 'PositionA'), ('position-b', 'PositionB')]]
    d.write_prefs('timer_presets', {'items': json.dumps(rows)})
    launch('PositionA')
    drag_to(d.width-1, 350)
    frame_a = d.frame()
    saved_a = presets()[0]
    assert 'positionX' in saved_a and 'positionY' in saved_a
    # Expansion/collapse must not save the temporarily clamped position.
    d.tapxy(*d.center())
    cx, cy = d.center()
    d.tapxy(cx, cy-24*d.density)
    close_to(frame_a)
    assert presets()[0] == saved_a
    d.finish()
    launch('PositionB')
    assert 'positionX' not in presets()[1]
    drag_to(0, d.height-450)
    frame_b = d.frame()
    saved_b = presets()[1]
    assert saved_a['positionX'] != saved_b['positionX']
    d.finish()
    d.stop()  # Real process restart, not merely closing the Activity.
    launch('PositionA')
    close_to(frame_a)
    d.shot('preset-a-restored')
    # Rotation changes available bounds, never the persisted normalized anchor.
    auto = d.shell('settings', 'get', 'system', 'accelerometer_rotation').strip()
    rotation = d.shell('settings', 'get', 'system', 'user_rotation').strip()
    try:
        d.shell('settings', 'put', 'system', 'accelerometer_rotation', 0)
        d.shell('settings', 'put', 'system', 'user_rotation', 1)
        time.sleep(2)
        raw = d.adb('exec-out', 'screencap', '-p')
        width, height = struct.unpack('>II', raw[16:24])
        assert width > height, 'Emulator did not rotate'
        x, y, r, b = d.frame()
        assert 0 <= x < r <= width and 0 <= y < b <= height
        assert presets()[0] == saved_a
        (d.output/'preset-landscape.png').write_bytes(raw)
    finally:
        for key, value in [('user_rotation', rotation), ('accelerometer_rotation', auto)]:
            d.shell('settings', 'delete' if value == 'null' else 'put', 'system', key, *([] if value == 'null' else [value]))
        time.sleep(2)
    close_to(frame_a)
    d.finish()
    launch('PositionB')
    close_to(frame_b)
    d.shot('preset-b-restored')
    assert presets()[0] == saved_a and presets()[1] == saved_b
    d.finish()
    # Normal manual timer does not overwrite either preset's position.
    d.tap('开始倒计时')
    time.sleep(.5)
    drag_to(d.width//2, d.height//2)
    # The centered overlay covers the Activity's stop button. This case checks
    # preset isolation, so clean up the fixture without tapping through a window.
    d.stop()
    assert presets()[0] == saved_a and presets()[1] == saved_b
    return dict(independentSwitches=True, settingsPersisted=True, settingsBackNavigation=True,
                distinctPresetPositions=True, restartRestores=True, rotationPreservesAnchor=True,
                expansionPreservesAnchor=True, manualTimerIsolated=True, frameA=frame_a, frameB=frame_b)
