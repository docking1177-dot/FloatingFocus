"""Settings-only Debug tools, actual sandbox overlay and shared production effects."""
import time


def developer(d):
    def top():
        for _ in range(5):
            d.shell('input', 'swipe', int(d.width*.04), int(d.height*.25), int(d.width*.04), int(d.height*.86), 350)
        time.sleep(.3)

    def texts():
        return [n.get('text', '') for n in d.dump().iter('node')]

    d.stop()
    d.write_prefs('onboarding', dict(completed=True))
    d.write_prefs('reminder_settings', dict(style='COLOR', color='BLUE', baseSize=1.0, opacity=.9, intensity=1.0,
                                         colorEnabled=True, sizeEnabled=True))
    d.start()
    home = texts()
    assert not any(t in home for t in ('开发者模式', '提醒实验室 · Debug', '开源许可', '使用与提醒说明'))
    before = len(d.records())
    d.tap('设置'); d.tap('开发者模式', scroll=True)
    assert '.ReminderLabActivity' in d.foreground()
    seen = set(texts())
    d.shot('developer-controls')
    for _ in range(4):
        d.shell('input', 'swipe', int(d.width*.04), int(d.height*.82), int(d.width*.04), int(d.height*.27), 400)
        seen.update(texts())
    assert not {'提醒方案', '变色', '变尺寸', '交互确认', '退出实验室', '重新请求确认'} & seen
    top()
    tree = d.dump()
    buttons = [next(n for n in tree.iter('node') if n.get('text') == label) for label in ('显示浮窗', '关闭浮窗')]
    assert d.bounds(buttons[0])[1] == d.bounds(buttons[1])[1] and d.bounds(buttons[0])[3] == d.bounds(buttons[1])[3]
    d.tap('显示浮窗')
    deadline = time.monotonic()+5
    while time.monotonic() < deadline:
        if d.overlay() and d.frame()[2]-d.frame()[0] > 100:
            break
        time.sleep(.2)
    initial = d.frame()
    d.shot('developer-zero-blue')
    d.tap('80%', scroll=True)
    time.sleep(.4)
    urgent = d.frame()
    assert urgent[2]-urgent[0] > initial[2]-initial[0], (initial, urgent)
    assert urgent[3]-urgent[1] > initial[3]-initial[1], (initial, urgent)
    d.shot('developer-urgent-red')
    top()
    assert '已显示' in texts()
    d.tap('关闭浮窗'); d.wait_stopped()
    d.tap('显示浮窗')
    deadline = time.monotonic()+5
    while not d.overlay() and time.monotonic() < deadline:
        time.sleep(.2)
    assert d.overlay()
    d.shell('input', 'keyevent', 'KEYCODE_BACK')
    d.wait_stopped()
    assert '.SettingsActivity' in d.foreground()
    assert len(d.records()) == before
    return dict(settingsOnly=True, renamed=True, obsoleteControlsRemoved=True, buttonsSameRow=True,
                sharedCombinedEffects=True, showCloseAndBackCleanup=True, recordsUnaffected=True)
