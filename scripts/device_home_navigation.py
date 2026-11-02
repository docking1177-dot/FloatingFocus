"""Real home tabs, long-press reorder, restoration and shared-session guards.

Called by the existing isolated home suite after its own fixture setup. No
automatic routing fallback: every tab navigation is a deliberate test action.
"""
import time
import struct
import xml.etree.ElementTree as ET

PACKAGE = 'app.floatingfocus'
LABELS = ('计时', '预设')
HEADINGS = {'计时': '倒计时', '预设': '我的预设'}


def home_reorder(d):
    """Narrow real-gesture regression; does not claim the whole home suite."""
    d.seed(); d.finish(); d.home_tab('预设')
    def order():
        raw = d.adb('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/home_navigation.xml')
        return ET.fromstring(raw).find("string[@name='order']").text
    def move_to(label):
        points=[]
        for entry in ('预设', label):
            x,y,r,b=d.bounds(d.home_tab_node(entry)); points.append(((x+r)//2,(y+b)//2))
        d.shell('input','draganddrop',*points[0],*points[1],700); time.sleep(.5)
    for index in range(2):
        move_to('计时')
        assert order()=='presets,timer', ('forward',index,order())
        assert d.home_tab_node('预设').get('selected')=='true'
        move_to('计时')
        assert order()=='timer,presets', ('reverse',index,order())
        assert d.home_tab_node('预设').get('selected')=='true'
    d.shot('home-reorder-roundtrip')
    return dict(selectedEntryBidirectional=True,roundTrips=2,scope='reorder-only')


def home_navigation(d):
    def texts(tree=None): return [n.get('text', '') for n in (tree if tree is not None else d.dump()).iter('node')]
    def tab_node(label, tree=None):
        return d.home_tab_node(label, tree)
    def center(label):
        x, y, r, b = d.bounds(tab_node(label))
        return (x+r)//2, (y+b)//2
    def saved_order():
        raw = d.adb('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/home_navigation.xml')
        return ET.fromstring(raw).find("string[@name='order']").text
    def exclusive(label):
        tree = d.dump()
        assert tab_node(label, tree).get('selected') == 'true', 'Selected tab changed unexpectedly'
        labels = texts(tree)
        assert HEADINGS[label] in labels
        assert all(heading not in labels for key, heading in HEADINGS.items() if key != label), 'Two home sections are visible'
    def drag(label, destination):
        a, b = center(label), center(destination)
        d.shell('input', 'draganddrop', *a, *b, 700)
        time.sleep(.4)
    def actual_order():
        tree = d.dump()
        return sorted(LABELS, key=lambda label: d.bounds(tab_node(label, tree))[0])
    def surface_point(label):
        x, y, r, b = d.bounds(tab_node(label))
        return round(x+(r-x)*.15), round(y+(b-y)*.3)
    def color_at(point):
        raw = d.adb('exec-out', 'screencap')
        width, height, format_ = struct.unpack_from('<III', raw)
        offset = len(raw)-width*height*4
        assert format_ == 1 and offset in (12, 16), 'Unexpected framebuffer format'
        x, y = point
        return tuple(raw[offset+(y*width+x)*4:][:3])

    print('Home tabs: two exclusive pages, shared selection and real long-press reorder...', flush=True)
    exclusive('计时')
    d.shot('home-tabs-timer')
    d.tap('10分')
    d.home_tab('预设'); exclusive('预设'); d.shot('home-tabs-presets')
    d.home_tab('计时'); d.tap('正计时'); d.home_tab('预设'); d.home_tab('计时')
    assert '开始正计时' in texts(), 'Tab switch discarded stopwatch mode'
    d.tap('倒计时')
    initial = saved_order()
    a, b = center('预设'), center('计时')
    d.shell('input', 'swipe', *a, *b, 150)
    assert saved_order() == initial, 'A short swipe reordered tabs'
    drag('预设', '计时')
    assert saved_order() == 'presets,timer'
    assert actual_order() == ['预设', '计时']
    exclusive('计时')  # Dragging another entry must not select it.
    d.shot('home-tabs-reordered')
    d.stop(); d.start(); exclusive('预设')
    assert saved_order() == 'presets,timer', 'Order was not restored on cold start'

    print('Home tabs: foreground/configuration restore and cancelled drag...', flush=True)
    # Choose the non-first page: resetting to the saved first page must fail.
    d.home_tab('计时'); d.tap('设置'); d.shell('input', 'keyevent', 'KEYCODE_BACK'); exclusive('计时')
    d.shell('input', 'keyevent', 'KEYCODE_HOME'); d.start(); exclusive('计时')
    rotation = {key: d.shell('settings', 'get', 'system', key).strip() for key in ('accelerometer_rotation', 'user_rotation')}
    font = d.shell('settings', 'get', 'system', 'font_scale').strip()
    night = d.shell('cmd', 'uimode', 'night').strip().split()[-1]
    try:
        d.shell('settings', 'put', 'system', 'accelerometer_rotation', '0')
        for value in ('1', '0'):
            d.shell('settings', 'put', 'system', 'user_rotation', value); time.sleep(1.2)
            deadline = time.monotonic()+5
            while True:
                width, height = struct.unpack_from('<II', d.adb('exec-out', 'screencap'))
                if (width > height) == (value == '1'): break
                assert time.monotonic() < deadline, 'Requested screen rotation never took effect'
                time.sleep(.2)
            exclusive('计时')
        # Losing window focus cancels the in-progress gesture. Never finish the
        # drag with UP in FF: that would be a completed, persistable reorder.
        a, b = center('计时'), center('预设')
        before = saved_order()
        target_sample = surface_point('预设')
        selected_color = color_at(surface_point('计时'))
        assert color_at(target_sample) != selected_color, 'Cancel fixture target is already selected'
        try:
            d.shell('input', 'motionevent', 'DOWN', *a); time.sleep(.8)
            d.shell('input', 'motionevent', 'MOVE', *b); time.sleep(.4)
            # A held pointer prevents a reliable idle UIAutomator dump. Verify
            # real drag feedback reached the previously unselected target cell
            # before cancelling, so a gesture which never began cannot pass.
            assert max(abs(x-y) for x, y in zip(color_at(target_sample), selected_color)) <= 3, 'Cancel fixture never entered a real drag'
            d.shot('home-tabs-drag-before-cancel')
            d.shell('input', 'keyevent', 'KEYCODE_HOME')
        finally:
            d.shell('input', 'motionevent', 'UP', *b)
        d.start(); exclusive('计时')
        assert saved_order() == before, 'Cancelled drag was persisted'
        assert actual_order() == ['预设', '计时']
        for mode in ('yes', 'no'):
            d.shell('cmd', 'uimode', 'night', mode); time.sleep(.8)
            for label in LABELS:
                d.home_tab(label); exclusive(label)
                d.shot('home-tabs-' + ('dark-' if mode == 'yes' else 'light-') + str(LABELS.index(label)))
        d.shell('settings', 'put', 'system', 'font_scale', '2.0'); time.sleep(.8)
        for label in LABELS:
            d.home_tab(label)
            for item in LABELS:
                x, y, r, b = d.bounds(tab_node(item))
                assert 0 <= x < r <= d.width and b-y >= 48*d.density-2, 'Large-font tab is clipped or too small'
            d.shot('home-tabs-large-' + str(LABELS.index(label)))
        d.home_tab('预设'); d.tap('新建预设', scroll=True); d.tap('取消')
        old_bounds = d.bounds(next(n for n in d.dump().iter('node') if n.get('content-desc') == '新建预设'))
        d.home_tab('计时'); d.home_tab('预设')
        new_bounds = d.bounds(next(n for n in d.dump().iter('node') if n.get('content-desc') == '新建预设'))
        assert max(abs(a-b) for a, b in zip(old_bounds, new_bounds)) <= 3, 'Preset scroll position was reset'
    finally:
        d.shell('cmd', 'uimode', 'night', night)
        d.shell('settings', 'delete' if font == 'null' else 'put', 'system', 'font_scale', *([] if font == 'null' else [font]))
        for key, value in rotation.items():
            d.shell('settings', 'delete' if value == 'null' else 'put', 'system', key, *([] if value == 'null' else [value]))
        time.sleep(.8)

    print('Home tabs: session state and stop stay available in every section...', flush=True)
    d.home_tab('计时'); d.tap('10分')
    before = len(d.records()); d.tap('开始倒计时'); time.sleep(.8)
    assert d.overlay() and len(d.records()) == before+1
    record = d.records()[-1]
    assert record['plannedMillis'] == 600000 and record['mode'] == 'COUNTDOWN'
    # The user's real overlay is above FF too. Move the fixture away from the
    # pinned tabs before touching them, rather than accidentally expanding it.
    d.shell('input', 'swipe', *d.center(), int(d.width*.7), int(d.height*.78), 450)
    time.sleep(.4)
    for label, action in [('预设', '启动预设 Home 0'), ('计时', '打开 Gallery 并计时')]:
        d.home_tab(label)
        assert '结束计时' in texts(), 'Active session controls are hidden in ' + label
        d.tap(action, scroll=True)
        assert '.MainActivity' in d.foreground() and len(d.records()) == before+1
        assert d.records()[-1]['id'] == record['id'], 'Switching or disabled entry replaced the session'
    d.tap('结束计时'); d.wait_stopped()
    deadline = time.monotonic()+3
    while d.records()[-1]['status'] != 'MANUAL' and time.monotonic() < deadline: time.sleep(.1)
    assert d.records()[-1]['status'] == 'MANUAL'
    drag('计时', '预设')
    assert saved_order() == 'timer,presets'
    d.home_tab('计时')
    for _ in range(3): d.shell('input','swipe',int(d.width*.03),int(d.height*.25),int(d.width*.03),int(d.height*.85),200)
    return dict(tabIsolation=True, sharedSelection=True, longPressReorderAndColdStart=True,
                cancelledDragNotSaved=True, selectionRestoredOnReturnAndRotation=True,
                largeFontTargetsAndScrollRestore=True, activeSessionSharedGuards=True)
