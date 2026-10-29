"""Startup defaults through real settings, wheels, recreation and timer records."""
import time
from device_preferences import pref_values, set_wheel, wheel

PACKAGE = 'app.floatingfocus'


def startup(d):
    def texts(): return [n.get('text', '') for n in d.dump().iter('node')]
    def selected(label):
        tree = d.dump()
        parents = {child: parent for parent in tree.iter('node') for child in parent}
        node = next(n for n in tree.iter('node') if n.get('text') == label)
        while node is not None:
            if node.get('selected') == 'true' or node.get('checked') == 'true': return True
            node = parents.get(node)
        return False
    def idle(): assert d.overlay() is None
    def park_overlay(height):
        if d.overlay():
            # Keep the real overlay away from the specific next action; settings
            # and Save live at opposite ends of this scrollable page.
            x, y = d.center()
            d.shell('input', 'swipe', x, y, int(d.width*.8), int(d.height*height), 350)
    def settings():
        park_overlay(.4)
        d.tap('手动设置')
        d.tap('启动默认配置', scroll=True)
    def save():
        park_overlay(.24)
        d.tap('保存', scroll=True)
    def fresh(): d.stop(); d.start()
    def scroll_top():
        for _ in range(3):
            d.shell('input', 'swipe', int(d.width*.03), int(d.height*.25), int(d.width*.03), int(d.height*.85), 200)

    print('Startup: migration, wheels and saved defaults...', flush=True)
    d.seed(); d.finish(); d.stop()
    # Existing users have four duration slots but no startup keys.
    d.write_prefs('quick_durations', {'seconds': '300,600,900,1800'})
    d.start(); idle()
    assert selected('倒计时') and selected('15分')
    before = len(d.records())
    settings()
    d.tap('正计时')
    assert '默认倒计时时长' not in texts()
    d.tap('倒计时'); d.tap('默认倒计时时长')
    assert wheel(d, '分')[1] == 15 and wheel(d, '秒')[1] == 0
    set_wheel(d, '分', 2); set_wheel(d, '秒', 5); d.tap('确认')
    d.shot('startup-default-countdown'); save()
    saved = pref_values(d, 'quick_durations')
    assert saved['startup_mode'] == 'COUNTDOWN' and saved['startup_seconds'] == 125
    assert saved['seconds'] == '300,600,900,1800'
    # Saving defaults does not override this existing home's choice or auto-start.
    assert selected('15分') and len(d.records()) == before; idle()
    d.tap('10分')
    d.shell('input', 'keyevent', 'KEYCODE_HOME'); d.start()
    assert selected('10分'); idle()
    rotation = d.shell('settings', 'get', 'system', 'user_rotation').strip()
    auto = d.shell('settings', 'get', 'system', 'accelerometer_rotation').strip()
    try:
        d.shell('settings', 'put', 'system', 'accelerometer_rotation', '0')
        d.shell('settings', 'put', 'system', 'user_rotation', '1'); time.sleep(.7)
        d.shell('settings', 'put', 'system', 'user_rotation', '0'); time.sleep(.7)
        assert selected('10分'); idle()
    finally:
        d.shell('settings', 'put', 'system', 'user_rotation', rotation)
        d.shell('settings', 'put', 'system', 'accelerometer_rotation', auto)

    print('Startup: fresh home, running session and mode retention...', flush=True)
    fresh()
    assert '已选时长 · 2 分 5 秒' in texts(); idle()
    d.tap('开始倒计时'); time.sleep(.5)
    row = d.records()[-1]
    assert row['mode'] == 'COUNTDOWN' and row['plannedMillis'] == 125000
    d.shot('startup-default-running')
    settings(); d.tap('正计时'); save()
    assert selected('倒计时') and d.records()[-1]['id'] == row['id']
    # A new home Activity while the service survives uses the active configuration.
    d.shell('am', 'start', '-W', '-n', PACKAGE+'/.MainActivity', '-f', '0x18000000')
    assert selected('倒计时') and '已选时长 · 2 分 5 秒' in texts()
    assert d.records()[-1]['id'] == row['id']
    d.finish(); fresh()
    assert selected('正计时'); idle()
    settings(); d.tap('倒计时'); d.tap('默认倒计时时长')
    assert wheel(d, '分')[1] == 2 and wheel(d, '秒')[1] == 5
    d.tap('取消'); d.shell('input', 'keyevent', 'KEYCODE_BACK')
    assert pref_values(d, 'quick_durations')['startup_mode'] == 'STOPWATCH'
    # Slot edits and slot reset preserve the independently saved defaults.
    print('Startup: independent slots, reset and actual stopwatch...', flush=True)
    settings(); scroll_top(); d.tap('第 1 个时长')
    set_wheel(d, '分', 4); d.tap('确认'); save()
    assert pref_values(d, 'quick_durations')['startup_seconds'] == 125
    assert pref_values(d, 'quick_durations')['startup_mode'] == 'STOPWATCH'
    settings(); d.tap('恢复默认', scroll=True); save()
    assert pref_values(d, 'quick_durations') == saved | {'startup_mode': 'STOPWATCH'}
    fresh(); assert selected('正计时'); idle()
    d.shot('startup-stopwatch-home')
    d.tap('开始正计时'); time.sleep(.5)
    final = d.records()[-1]
    assert final['mode'] == 'STOPWATCH' and final['plannedMillis'] == 0
    d.finish()
    return dict(firstUseCountdown900=True, savedCountdown125=True, slotsIndependent=True,
                noAutomaticStart=True, existingChoicePreserved=True, recreationPreserved=True,
                activeSessionRecovered=True, stopwatchRetainsCountdown=True,
                slotResetPreservesStartup=True, actualCountdown=row, actualStopwatch=final)
