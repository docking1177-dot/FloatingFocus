"""Opt-in periodic reminders: real minute intervals, real wheels and C4 isolation.

No hidden timer acceleration. Only the dedicated emulator runner calls this;
original private data and display settings are restored. Audio logs are request
evidence, not a listening or physical vibration assertion.
"""
import json
import re
import subprocess
import time

from device_controls import fixture, key_point, wait_for
from device_preferences import pref_values, set_wheel

PACKAGE = 'app.floatingfocus'


def reminder_events(log):
    pattern = (r'Overtime reminder round=(\d+) effects=\[([^]]*)\] level=(\w+) '
               r'overtime=(\d+) elapsed=(\d+) planned=(\d+) next=(\d+)')
    return [dict(round=int(n), effects=[e.strip() for e in effects.split(',') if e.strip()], level=level,
                 overtime=int(o), elapsed=int(e), planned=int(p), next=int(t))
            for n, effects, level, o, e, p, t in re.findall(pattern, log)]


def snooze_events(log):
    return [dict(seconds=int(s), count=int(c), timerStart=int(t), elapsed=int(e), planned=int(p))
            for s, c, t, e, p in re.findall(
                r'Overtime snoozed seconds=(\d+) count=(\d+) timerStart=(\d+) elapsed=(\d+) planned=(\d+)', log)]


def layout_bounds(log, after, roles):
    rows = re.findall(r'Overlay layout t=(\d+) role=([\w-]+) bounds=([-\d.,]+)', log)
    return {role: sorted({tuple(map(float, coords.split(','))) for t, kind, coords in rows
                          if kind == role and int(t) >= after}) for role in roles}


def overtime_reminders(d):
    def log():
        return d.shell('logcat', '-d', '-s', 'FloatingFocus:*')

    def save_log(name):
        data = log()
        (d.output / (name + '.log')).write_text(data, encoding='utf-8')
        return data

    def probe(role):
        rows = re.findall(r'role=' + re.escape(role) + r' bounds=([-\d.,]+)', log())
        assert rows, 'No measured overlay target: ' + role
        x, y, r, b = map(float, rows[-1].split(','))
        a, c, _, _ = d.frame()
        return a+x, c+y, a+r, c+b

    def tap_probe(role):
        x, y, r, b = probe(role)
        assert r > x and b > y
        d.tapxy((x+r)/2, (y+b)/2)

    def finish():
        d.start()
        # Move the overlay away from home/dialog controls using its actual time area.
        if d.overlay():
            x, y, r, b = probe('time')
            d.shell('input', 'swipe', int((x+r)/2), int((y+b)/2), int(d.width*.8), int(d.height*.88), 350)
        d.tap('结束计时')
        d.wait_stopped()

    def open_settings():
        d.tap('设置')
        d.tap('超时提醒', scroll=True)
        wait_for(lambda: any(n.get('text') == '启用超时提醒' for n in d.dump().iter('node')),
                 'Overtime settings did not open', 10)

    def back_home():
        d.shell('input', 'keyevent', 'KEYCODE_BACK')
        d.shell('input', 'keyevent', 'KEYCODE_BACK')

    def start_current():
        d.shell('logcat', '-c')
        d.home_tab('计时')
        d.tap('开始倒计时')
        wait_for(lambda: d.overlay() and d.frame()[2]-d.frame()[0] > 40, 'Timer did not create measured overlay')
        time.sleep(2)
        return d.records()[-1]

    def capture_round(name, wait_seconds=85):
        before_log = log()
        before = reminder_events(before_log)
        expected = before[-1]['round']+1 if before else 1
        roles = {'c4-body', 'c4-display', 'c4-keypad'} if name.startswith('c4-full') else (
            {'time'} if name.startswith('c4-compact') else {'time', 'controls'})
        marks = [int(t) for t in re.findall(r'Overlay layout t=(\d+) role=', before_log)]
        assert marks, 'No settled content geometry before reminder'
        marker = max(marks)
        reference = {role: rows[-1] for role in roles for rows in [re.findall(
            r'Overlay layout t=\d+ role='+re.escape(role)+r' bounds=([-\d.,]+)', before_log)] if rows}
        assert roles <= reference.keys(), 'Missing expected content state before reminder'
        stable = []
        movie = None
        remote = '/sdcard/ff-overtime-status.mp4'
        def finish_movie():
            if movie is not None:
                _, error = movie.communicate(timeout=20)
                assert movie.returncode == 0, error
                (d.output/(name+'.mp4')).write_bytes(d.adb('exec-out', 'cat', remote))
                d.shell('rm', '-f', remote)
        deadline = time.monotonic()+wait_seconds
        announced = 0
        while time.monotonic() < deadline:
            assert d.overlay(), 'Overlay ended while waiting for reminder'
            window, frame = d.overlay()
            stable.append(dict(at=time.monotonic(), window=window, frame=frame))
            current_log = log()
            rows = reminder_events(current_log)
            if movie is None and name == 'overtime-first-visual':
                schedules = re.findall(r'Overtime schedule next=(\d+) now=\d+', current_log)
                if schedules:
                    now = int(float(d.shell('cat', '/proc/uptime').split()[0])*1000)
                    if int(schedules[-1])-now <= 6000:
                        movie = subprocess.Popen([d.exe, '-s', d.serial, 'shell', 'screenrecord', '--time-limit', '12',
                            '--size', f'{d.width//4*2}x{d.height//4*2}', remote], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            if rows and rows[-1]['round'] >= expected:
                d.shot(name+'-during')
                # Keep sampling after the effect ends; no layout change may be hidden by a screenshot.
                for _ in range(12):
                    stable.append(dict(at=time.monotonic(), window=d.overlay()[0], frame=d.frame()))
                    time.sleep(.18)
                d.shot(name+'-after')
                finish_movie()
                (d.output/(name+'-frames.json')).write_text(json.dumps(stable, indent=2), encoding='utf-8')
                assert len({s['window'] for s in stable}) == 1, 'Reminder recreated overlay'
                assert len({tuple(s['frame']) for s in stable}) == 1, 'Reminder moved/resized the overlay'
                data = save_log(name)
                bounds = layout_bounds(data, marker, roles)
                for role, coords in reference.items():
                    bounds[role] = sorted(set(bounds[role]) | {tuple(map(float, coords.split(',')))})
                (d.output/(name+'-bounds.json')).write_text(json.dumps(bounds, indent=2), encoding='utf-8')
                assert all(len(values) == 1 for values in bounds.values()), 'Reminder changed measured content: '+str(bounds)
                return rows[-1]
            if len(stable)//20 > announced:
                announced = len(stable)//20
                print(name+': waiting for real scheduled interval...', flush=True)
            time.sleep(.35)
        finish_movie()
        raise AssertionError('No scheduled reminder: '+name)

    fixture(d, seconds=1)
    d.stop()
    d.write_prefs('overtime_reminders', {})  # Missing keys exercise the opt-in migration.
    d.start()
    open_settings()
    d.shot('overtime-settings-default')
    assert not pref_values(d, 'overtime_reminders').get('enabled', False)
    d.tap('启用超时提醒')
    d.tap('基准间隔')
    set_wheel(d, '分', 1)
    set_wheel(d, '秒', 0)
    d.tap('确认')
    d.tap('背景提亮', scroll=True)
    d.tap('描边脉冲', scroll=True)  # Force STATUS once; randomness cannot hide text metric defects.
    d.tap('两段短震动', scroll=True)
    d.tap('短提示音', scroll=True)
    d.tap('明显', scroll=True)
    d.tap('主动选择下一次', scroll=True)
    d.shot('overtime-settings-choice')
    saved = pref_values(d, 'overtime_reminders')
    assert saved['enabled'] and saved['interval_seconds'] == 60
    assert set(saved['effects'].split(',')) == {'STATUS', 'VIBRATION', 'SOUND'}
    assert saved['limit'] == 'NOTICEABLE' and saved['interaction'] == 'CHOICE'
    back_home()
    d.stop(); d.start()
    assert pref_values(d, 'overtime_reminders') == saved, 'Periodic settings lost across process restart'

    before = start_current()
    assert before['plannedMillis'] == 1000
    wid = d.overlay()[0]
    # The real service owns the next deadline; no timestamp fixture or internal Intent is injected.
    schedule = re.findall(r'Overtime schedule next=(\d+) now=(\d+) exact=false count=0', log())
    assert len(schedule) == 1, 'Initial deadline was redrawn or not scheduled'
    assert 48000 <= int(schedule[0][0])-int(schedule[0][1]) <= 72000
    d.shell('am', 'start', '-n', 'com.android.gallery3d/.app.GalleryActivity')
    wait_for(lambda: 'com.android.gallery3d' in d.foreground(), 'Periodic check is not actually cross-app', 10)
    first = capture_round('overtime-first-visual')
    assert first['round'] == 1 and first['level'] == 'LIGHT'
    assert first['effects'] == ['STATUS']
    assert 'Overtime tone requested=' not in log()
    assert log().count('Timer completed:') == 1, 'Periodic reminder replayed completion'

    # Inline cancel must not alter the scheduled deadline, clock or active leg.
    tap_probe('snooze-open')
    time.sleep(.5)
    editor = d.frame()
    x, y, r, b = probe('snooze-wheels')
    d.shell('input', 'swipe', int(x+(r-x)*.75), int(y+(b-y)*.8), int(x+(r-x)*.75), int(y+(b-y)*.3), 550)
    assert d.frame() == editor, 'Snooze wheel dragged the window'
    tap_probe('snooze-cancel')
    assert not snooze_events(log()), 'Cancel counted as a snooze'
    assert 'Timer extended:' not in log()

    # Three deliberate confirmations, all real taps, qualify the explanatory higher tier.
    for expected in range(1, 4):
        tap_probe('snooze-open')
        time.sleep(.35)
        tap_probe('snooze-confirm')
        events = snooze_events(log())
        assert events[-1]['count'] == expected and events[-1]['seconds'] == 60
        assert events[-1]['planned'] == 1000
    confirmation = snooze_events(log())[-1]
    assert d.records()[-1]['id'] == before['id'] and d.records()[-1]['plannedMillis'] == 1000
    assert d.overlay()[0] == wid
    d.shot('overtime-three-explicit-choices')
    second = capture_round('overtime-after-three-choices', 72)
    assert second['round'] >= 2 and second['level'] == 'NOTICEABLE'
    assert second['planned'] == 1000 and second['elapsed'] > confirmation['elapsed']
    assert second['overtime']-confirmation['elapsed']+1000 >= 59000, 'User interval fired early'
    assert 'Timer extended:' not in log(), 'Snooze extended timer'
    # Real home dialog: cancel and rotation retain a frozen session/leg request.
    d.start()
    x, y, r, b = probe('time')
    d.shell('input', 'swipe', int((x+r)/2), int((y+b)/2), int(d.width*.8), int(d.height*.91), 400)
    d.tap('稍后提醒')
    auto = d.shell('settings', 'get', 'system', 'accelerometer_rotation').strip()
    rotation = d.shell('settings', 'get', 'system', 'user_rotation').strip()
    try:
        d.shell('settings', 'put', 'system', 'accelerometer_rotation', '0')
        d.shell('settings', 'put', 'system', 'user_rotation', '1'); time.sleep(.7)
        assert any(n.get('text') == '多久后再提醒' for n in d.dump().iter('node')), 'Rotation lost snooze draft'
        d.shot('overtime-home-dialog-rotated')
    finally:
        d.shell('settings', 'put', 'system', 'user_rotation', rotation if rotation != 'null' else '0')
        if auto == 'null': d.shell('settings', 'delete', 'system', 'accelerometer_rotation')
        else: d.shell('settings', 'put', 'system', 'accelerometer_rotation', auto)
        time.sleep(.7)
    d.tap('取消')
    assert snooze_events(log())[-1]['count'] == 3, 'Home cancel incremented count'
    # An explicit Add Time changes the leg; the older home reminder draft must disappear.
    d.tap('稍后提醒')
    tap_probe('extension-open'); time.sleep(.4)
    tap_probe('extension-confirm')
    time.sleep(.7)
    assert not any(n.get('text') == '多久后再提醒' for n in d.dump().iter('node')), 'Old-leg reminder dialog remained usable'
    assert d.records()[-1]['id'] == before['id'] and d.records()[-1]['plannedMillis'] == 121000
    assert snooze_events(log())[-1]['count'] == 3 and log().count('Timer extended:') == 1
    finish()
    count = len(reminder_events(log()))
    time.sleep(3)
    assert len(reminder_events(log())) == count and d.overlay() is None, 'Reminder survived End'

    # Real C4 countdown: full form must stay unchanged and skip visual effects at a due round.
    fixture(d, seconds=10, c4=True)
    d.stop()
    d.write_prefs('overtime_reminders', dict(enabled=True, interval_seconds=60,
        effects='OUTLINE', limit='LIGHT', interaction='CHOICE'))
    d.write_prefs('c4_settings', dict(enabled=True, sound=True, noticeAcknowledged=True))
    d.start()
    c4_start = start_current()
    assert not reminder_events(log()), 'Periodic reminder ran before zero'
    d.tapxy(*d.center())
    wait_for(lambda: d.frame()[3]-d.frame()[1] > 220*d.density, 'C4 did not expand')
    full = d.frame(); c4_wid = d.overlay()[0]
    d.shot('c4-full-before-periodic')
    first_c4 = capture_round('c4-full-skips-visual', 88)
    assert first_c4['effects'] == []
    assert d.frame() == full and d.overlay()[0] == c4_wid
    assert log().count('C4 explosion consumed') == 1
    assert 'Overtime tone requested=' not in log()
    # Collapse, drag and re-expand must not replay skipped visual effects or restart C4.
    x, y, r, b = full
    d.tapxy(x+(r-x)*.55, y+(b-y)*.20)
    compact = d.frame()
    assert compact != full
    time.sleep(2)
    assert len(reminder_events(log())) == 1
    d.shot('c4-compact-after-skipped-round')
    # C4 offers the same home wheel only after zero, not an extra keyboard/button action.
    x, y, r, b = probe('time')
    d.shell('input', 'swipe', int((x+r)/2), int((y+b)/2), int(d.width*.8), int(d.height*.91), 350)
    d.start(); d.tap('稍后提醒')
    set_wheel(d, '秒', 5)
    d.tap('确认')
    choice = snooze_events(log())[-1]
    assert choice['seconds'] == 65 and choice['count'] == 1 and choice['planned'] == 10000
    schedule_before_edit = re.findall(r'Overtime schedule next=(\d+) now=(\d+) exact=true count=1', log())[-1]
    # The schedule log's now is sampled after admission; allow its few milliseconds of processing.
    assert 64900 <= int(schedule_before_edit[0])-int(schedule_before_edit[1]) <= 65000
    open_settings(); d.tap('基准间隔'); set_wheel(d, '分', 2); d.tap('确认')
    assert re.findall(r'Overtime schedule next=(\d+) now=(\d+) exact=true count=1', log())[-1] == schedule_before_edit
    back_home()
    compact_cue = capture_round('c4-compact-next-round', 83)
    assert compact_cue['effects'] == ['OUTLINE']
    d.tapxy(*d.center())
    assert d.overlay()[0] == c4_wid
    assert d.frame()[2]-d.frame()[0] == full[2]-full[0] and d.frame()[3]-d.frame()[1] == full[3]-full[1]
    d.tapxy(*key_point(d, '#')); d.wait_stopped()
    c4_log = save_log('c4-periodic-final')
    assert 'C4 phase=ARMED' in c4_log and c4_log.count('C4 explosion consumed') == 1
    assert 'Timer extended:' not in c4_log and 'Timer paused=true' not in c4_log
    assert d.records()[-1]['id'] == c4_start['id']

    # UI-only dark/large-font checks; restore system state even on failure.
    font = d.shell('settings', 'get', 'system', 'font_scale').strip()
    night = d.shell('cmd', 'uimode', 'night').strip()
    try:
        d.start(); open_settings()
        d.shell('cmd', 'uimode', 'night', 'yes'); time.sleep(.8)
        d.shot('overtime-settings-dark')
        d.shell('settings', 'put', 'system', 'font_scale', '1.5'); time.sleep(.8)
        d.shot('overtime-settings-large')
        d.tap('基准间隔', scroll=True)
        assert any(n.get('class') == 'android.widget.NumberPicker' for n in d.dump().iter('node'))
        d.shot('overtime-wheel-dark-large'); d.tap('取消')
    finally:
        if font == 'null': d.shell('settings', 'delete', 'system', 'font_scale')
        else: d.shell('settings', 'put', 'system', 'font_scale', font)
        mode = re.search(r'Night mode: (\w+)', night)
        d.shell('cmd', 'uimode', 'night', mode[1] if mode else 'no')

    return dict(missingDefaultsOptIn=True, uiPreferencesPersisted=True, firstVisualOnly=True,
                initialIntervalMillis=int(schedule[0][0])-int(schedule[0][1]),
                cancelDoesNotSnooze=True, inlineWheelDoesNotDrag=True, explicitChoiceCount=3,
                exactChoiceNoExtension=True, first=first, afterChoices=second,
                endCancels=True, c4FullSkipped=first_c4, c4Compact=compact_cue,
                c4WindowNotRecreated=True, c4NoPauseOrExtension=True,
                homeCancelAndRotation=True, oldLegDialogDismissed=True, c4HomeSnooze=True, contentBoundsStable=True,
                explicitDeadlineSurvivesBaseEdit=True,
                c4AudioEvidence='original C4 sound enabled; requests do not verify actual listening or tail continuity',
                audioEvidence='only selected request logs, not actual sound/vibration acceptance',
                screenshotReview='dark/large-font screenshots require visual review')


def overtime_reminders_lifecycle(d):
    """Short lifecycle checks, separate from the minute-long visual/interaction suite."""
    from device_c4 import has_timer_service

    def log():
        return d.shell('logcat', '-d', '-s', 'FloatingFocus:*')

    def prepare():
        fixture(d, seconds=1)
        d.stop()
        d.write_prefs('overtime_reminders', dict(enabled=True, interval_seconds=60,
            effects='BRIGHTEN,OUTLINE,STATUS', limit='LIGHT', interaction='LIGHTWEIGHT'))
        d.start()
        d.shell('logcat', '-c')
        d.tap('开始倒计时')
        wait_for(lambda: 'Overtime schedule next=' in log(), 'No overdue schedule', 8)

    prepare()
    d.start()
    coords = re.findall(r'role=time bounds=([-\d.,]+)', log())[-1]
    x, y, r, b = map(float, coords.split(','))
    left, top, _, _ = d.frame()
    d.shell('input', 'swipe', int(left+(x+r)/2), int(top+(y+b)/2), int(d.width*.8), int(d.height*.92), 400)
    d.tap('设置'); d.tap('超时提醒')
    d.tap('启用超时提醒')
    wait_for(lambda: 'Overtime schedule next=null' in log(), 'Master off did not cancel scheduled callback')
    assert pref_values(d, 'overtime_reminders')['enabled'] is False
    d.tap('启用超时提醒')
    assert pref_values(d, 'overtime_reminders')['enabled'] is True
    schedules = re.findall(r'Overtime schedule next=(\d+) now=(\d+) exact=false count=0', log())
    assert len(schedules) == 2, 'Re-enable did not create exactly one fresh deadline'
    assert 48000 <= int(schedules[-1][0])-int(schedules[-1][1]) <= 72000
    (d.output/'lifecycle-toggle.log').write_text(log(), encoding='utf-8')

    d.stop()  # Process death must not persist a scheduled restart.
    d.shell('logcat', '-c')
    d.start(); time.sleep(2)
    assert d.overlay() is None and not has_timer_service(d.shell('dumpsys', 'activity', 'services', PACKAGE))
    assert 'Overtime schedule' not in log() and 'Overtime reminder' not in log()
    assert pref_values(d, 'overtime_reminders')['enabled'] is True
    d.shot('overtime-after-process-reopen')

    prepare()
    try:
        d.shell('appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'ignore')
        wait_for(lambda: d.overlay() is None, 'Revoked overlay permission did not clean up', 5)
        assert not has_timer_service(d.shell('dumpsys', 'activity', 'services', PACKAGE))
        assert 'Overtime schedule next=' in log() and 'Overtime reminder round=' not in log()
        (d.output/'lifecycle-permission.log').write_text(log(), encoding='utf-8')
    finally:
        d.shell('appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'allow')

    fixture(d, seconds=60)
    d.stop()
    d.write_prefs('overtime_reminders', dict(enabled=True, interval_seconds=60, effects='SOUND,VIBRATION',
        limit='NOTICEABLE', interaction='CHOICE'))
    d.write_prefs('c4_settings', dict(enabled=True, sound=True, noticeAcknowledged=True))
    d.start(); d.shell('logcat', '-c')
    d.tap('正计时'); d.tap('开始正计时')
    wait_for(lambda: d.overlay() is not None, 'Stopwatch did not start')
    time.sleep(3)
    assert d.records()[-1]['mode'] == 'STOPWATCH'
    assert not re.search(r'Overtime (?:schedule|reminder)|C4 phase=|C4 media start=', log())
    d.finish()
    return dict(masterToggleReschedulesOnce=True, processReopenDoesNotResume=True,
                permissionRevocationCleansUp=True, stopwatchNoC4OrPeriodicSchedule=True)
