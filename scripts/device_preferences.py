"""Edit actual preference controls and observe custom nodes on a real one-minute clock."""
import time
import xml.etree.ElementTree as ET

PACKAGE = 'app.floatingfocus'


def preferences(d):
    def values():
        result = {}
        tree = ET.fromstring(d.adb('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/reminder_settings.xml'))
        for node in tree:
            kind = node.tag
            result[node.get('name')] = (node.get('value') == 'true' if kind == 'boolean' else
                int(node.get('value')) if kind == 'int' else float(node.get('value')) if kind == 'float' else node.text)
        return result

    def edit(index, value):
        fields = [n for n in d.dump().iter('node') if n.get('class') == 'android.widget.EditText']
        assert -len(fields) <= index < len(fields), ('Missing numeric field', index, len(fields))
        x, y, r, b = d.bounds(fields[index])
        d.tapxy((x+r)//2, (y+b)//2)
        d.shell('input', 'keyevent', 123)  # MOVE_END, then clear before entering a replacement
        d.shell('input', 'keyevent', *([67]*5))
        d.shell('input', 'text', value)
        d.shell('input', 'keyevent', 'KEYCODE_BACK')

    d.seed()
    d.finish()
    d.tap('设置')
    d.tap('常用时长')
    for index, value in enumerate([2, 7, 25, 45]):
        edit(index, value)
    d.shot('custom-duration-settings')
    d.tap('保存', scroll=True)
    d.shell('input', 'keyevent', 'KEYCODE_BACK')
    d.stop()
    d.start()
    texts = {n.get('text') for n in d.dump().iter('node')}
    assert all(str(value) + ' 分' in texts for value in [2, 7, 25, 45]), texts
    d.shot('custom-duration-home')
    d.tap('设置')
    d.tap('提醒策略')
    edit(0, 40)
    edit(1, 90)
    d.tap('保存', scroll=True)
    assert values()['warmPercent'] == 40 and values()['urgentPercent'] == 90
    d.tap('提醒策略')
    d.tap('剩余时间触发')
    edit(0, 45)
    edit(1, 10)
    d.tap('保存', scroll=True)
    assert values()['triggerMode'] == 'REMAINING' and values()['urgentSeconds'] == 10
    d.tap('提醒策略')
    d.tap('混合触发')
    d.tap('第三级 · 进度条件', scroll=True)
    d.tap('第三级 · 剩余时间条件', scroll=True)
    edit(-1, 20)  # after scrolling, the last visible numeric field is the urgent time
    d.shot('mixed-strategy-settings')
    d.tap('保存', scroll=True)
    settings = values()
    assert settings['triggerMode'] == 'MIXED' and settings['urgentSeconds'] == 20
    assert settings['warmProgress'] and not settings['warmRemaining']
    assert not settings['urgentProgress'] and settings['urgentRemaining']
    d.stop()
    d.start()
    assert values() == settings
    # Stable presentation fixture; trigger parameters above were set through real controls.
    d.stop()
    settings.update(colorEnabled=True, sizeEnabled=True, colorMode='STEPS', sizeMode='STEPS',
                    shape='ROUNDED_RECTANGLE', baseSize=1.0, opacity=1.0)
    d.write_prefs('reminder_settings', settings)
    d.start()
    custom = next(n for n in d.dump().iter('node') if n.get('text', '').startswith('自定义'))
    x, y, r, b = d.bounds(custom)
    d.tapxy((x+r)//2, (y+b)//2)
    edit(0, 1)
    d.tap('确定')
    d.shell('logcat', '-c')
    d.tap('开始倒计时')
    time.sleep(.5)
    initial = d.frame()
    d.shell('am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', '-p', 'com.android.gallery3d')
    frames = {}
    deadline = time.monotonic() + 55
    while time.monotonic() < deadline:
        log = d.shell('logcat', '-d', '-s', 'FloatingFocus:*')
        for stage in ('WARM', 'URGENT'):
            if stage not in frames and '-> ' + stage in log:
                frames[stage] = d.frame()
                d.shot('strategy-runtime-' + stage.lower())
        if 'URGENT' in frames:
            break
        time.sleep(.5)
    assert set(frames) == {'WARM', 'URGENT'}, frames
    width = lambda frame: frame[2]-frame[0]
    assert width(initial) < width(frames['WARM']) < width(frames['URGENT']), (initial, frames)
    assert 'com.android.gallery3d' in d.foreground()
    d.finish()
    return dict(durationButtonsPersisted=True, allModesSaved=True, mixedNodesPersisted=True,
                actualClockNodes=True, backgroundOverlay=True, initial=initial, stages=frames)
