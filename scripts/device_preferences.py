"""Real wheel, timeline, conflict and second-precision storage regression."""
import json
import time
import xml.etree.ElementTree as ET

PACKAGE = 'app.floatingfocus'


def pref_values(d, name='reminder_settings'):
    tree = ET.fromstring(d.adb('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/' + name + '.xml'))
    return {n.get('name'): (n.get('value') == 'true' if n.tag == 'boolean' else
            int(n.get('value')) if n.tag == 'int' else float(n.get('value')) if n.tag == 'float' else n.text) for n in tree}


def wheel(d, label):
    root = d.dump()
    nodes = sorted((n for n in root.iter('node') if n.get('class') == 'android.widget.NumberPicker'), key=lambda n: d.bounds(n)[0])
    assert len(nodes) == 2, 'Expected two native time wheels'
    node = nodes[0 if label == '\u5206' else 1]
    selected = next(n for n in node.iter('node') if n.get('class') == 'android.widget.EditText')
    return node, int(selected.get('text'))


def set_wheel(d, label, target):
    # NumberPicker exposes increment/decrement as native buttons; no keyboard or preference injection.
    for _ in range(3):
        node, current = wheel(d, label)
        if current == target:
            return
        x, y, r, b = d.bounds(node)
        direction = 1 if target > current else -1
        for _ in range(abs(target-current)):
            d.tapxy((x+r)//2, y+(b-y)*(.85 if direction > 0 else .15))
        time.sleep(.4)
    assert wheel(d, label)[1] == target, ('Wheel failed', label, target)


def edit_percent(d, target):
    node = next(n for n in d.dump().iter('node') if n.get('class') == 'android.widget.EditText')
    x, y, r, b = d.bounds(node)
    d.tapxy((x+r)//2, (y+b)//2)
    d.shell('input', 'keyevent', 123)
    d.shell('input', 'keyevent', 67, 67, 67)
    d.shell('input', 'text', target)
    d.shell('input', 'keyevent', 'KEYCODE_BACK')


def preferences(d):
    d.seed()
    d.finish()
    d.stop()
    d.write_prefs('quick_durations', {'minutes': '1,2,15,30'}) # exercise legacy read
    d.start()
    d.tap('1\u5206')
    d.tap('\u81ea\u5b9a\u4e49\u65f6\u957f')
    assert wheel(d, '\u5206')[1] == 1 and wheel(d, '\u79d2')[1] == 0
    node, _ = wheel(d, '\u79d2')
    x,y,r,b = d.bounds(node)
    d.shell('input', 'swipe', (x+r)//2, int(y+(b-y)*.75), (x+r)//2, int(y+(b-y)*.35), 650)
    time.sleep(.5)
    assert wheel(d, '\u79d2')[1] > 0
    set_wheel(d, '\u79d2', 5)
    d.shot('duration-wheel')
    d.tap('\u786e\u8ba4')
    d.tap('\u81ea\u5b9a\u4e49\u65f6\u957f')
    assert wheel(d, '\u5206')[1] == 1 and wheel(d, '\u79d2')[1] == 5
    d.tap('\u53d6\u6d88')
    d.tap('\u5f00\u59cb\u5012\u8ba1\u65f6')
    time.sleep(1)
    assert d.records()[-1]['plannedMillis'] == 65000
    d.finish()
    d.tap('\u624b\u52a8\u8bbe\u7f6e')
    d.tap('\u7b2c 1 \u4e2a\u65f6\u957f')
    set_wheel(d, '\u79d2', 5)
    d.tap('\u786e\u8ba4')
    d.tap('\u4fdd\u5b58')
    assert pref_values(d, 'quick_durations')['seconds'] == '65,120,900,1800'
    assert any(n.get('text') == 'Floating Focus' for n in d.dump().iter('node'))
    d.tap('\u8bbe\u7f6e')
    d.tap('\u63d0\u9192')
    d.tap('\u63d0\u9192\u7b56\u7565')
    d.shot('strategy-default')
    # Both labels drag independently; ensure drag is not mistaken for opening a panel.
    node = next(n for n in d.dump().iter('node') if n.get('text') == '\u7b2c\u4e8c\u7ea7')
    x,y,r,b = d.bounds(node)
    d.shell('input', 'swipe', (x+r)//2, (y+b)//2, (x+r)//2-int(d.width*.1), (y+b)//2, 650)
    d.tap('\u4fdd\u5b58')
    assert pref_values(d)['warmPercent'] < 50
    d.tap('\u63d0\u9192\u7b56\u7565')
    d.tap('\u7b2c\u4e8c\u7ea7')
    edit_percent(d, 50)
    d.tap('\u786e\u8ba4')
    d.tap('\u7b2c\u4e09\u7ea7')
    d.tap('\u5269\u4f59\u65f6\u95f4')
    assert wheel(d, '\u79d2')[1] == 20
    d.tap('\u786e\u8ba4')
    d.shot('strategy-mixed-timeline')
    d.tap('\u4fdd\u5b58')
    saved = pref_values(d)
    assert saved['warmProgress'] and not saved['warmRemaining']
    assert saved['urgentRemaining'] and not saved['urgentProgress']
    d.stop()
    d.start()
    assert pref_values(d) == saved
    assert any(n.get('text') == '1:05' for n in d.dump().iter('node'))
    # Same configuration becomes invalid at a shorter actual duration. Test all three launch paths.
    d.stop()
    settings = dict(saved, warmPercent=80, urgentSeconds=20)
    d.write_prefs('reminder_settings', settings)
    preset = dict(id='ff-conflict', name='Conflict', minutes=1, durationSeconds=65, mode='COUNTDOWN',
                  targetPackage='com.android.gallery3d', targetLabel='Gallery', settings=settings)
    d.write_prefs('timer_presets', {'items': json.dumps([preset])})
    d.start()
    d.tap('1:05')
    for entry in ('\u5f00\u59cb\u5012\u8ba1\u65f6', '\u542f\u52a8\u9884\u8bbe Conflict', '\u6253\u5f00 Gallery \u5e76\u8ba1\u65f6'):
        d.tap(entry, scroll=True)
        assert d.overlay() is None
        assert any('\u89e6\u53d1\u987a\u5e8f\u51b2\u7a81' in n.get('text', '') for n in d.dump().iter('node'))
        d.shot('conflict-' + str(len(list(d.output.glob('conflict-*.png')))))
        d.tap('\u8fd4\u56de')
    # Preset editor uses the same wheel and retains seconds through save/reopen/start.
    d.stop()
    preset['settings'] = dict(saved)
    d.write_prefs('timer_presets', {'items': json.dumps([preset])})
    d.start()
    d.tap('\u7ba1\u7406', scroll=True)
    d.tap('\u7f16\u8f91 Conflict')
    d.tap('\u8ba1\u65f6\u65f6\u957f', scroll=True)
    assert wheel(d, '\u5206')[1] == 1 and wheel(d, '\u79d2')[1] == 5
    set_wheel(d, '\u79d2', 6)
    d.tap('\u786e\u8ba4')
    d.tap('\u4fdd\u5b58')
    items = json.loads(pref_values(d, 'timer_presets')['items'])
    assert items[0]['durationSeconds'] == 66
    d.stop()
    d.start()
    d.tap('\u542f\u52a8\u9884\u8bbe Conflict', scroll=True)
    time.sleep(1)
    assert d.overlay() and 'com.android.gallery3d' in d.foreground()
    assert d.records()[-1]['plannedMillis'] == 66000
    d.finish()
    return dict(nativeWheelSwipe=True, secondsRoundTrip=True, shortcutMigration=True, timelineDrag=True,
                independentTypes=True, threeStartPathsRejectConflict=True, presetSecondsPersistedAndStarted=True)
