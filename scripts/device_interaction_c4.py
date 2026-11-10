"""Prepared companion; invoke c4_lock_companion(Device) inside the isolated runner.

No standalone ADB entry point: root's runner must provide emulator/APK/source
identity checks and app-data backup/restore. This module does not alter system
opacity thresholds. Frame/log assertions prove only the behavior they observe;
screenshots require visual review, physical sound/vibration stays unverified.
"""
import json
import re
import time

from device_controls import fixture, key_point, wait_for
from device_live_ui import prepare_live_dump, live_dump
from device_c4 import c4_device_geometry
from device_interactions import control, tap_control, window_state, texts, setup, seed_recovery
from device_timer_lifecycle import home, end, record

PACKAGE = 'app.floatingfocus'


def logs(d):
    return d.shell('logcat', '-d', '-s', 'FloatingFocus:*')


def key_results(trace):
    return re.findall(r'C4 key result=(\w+) length=(\d+)', trace)


def bound_state(trace, locked):
    matches = re.findall(r'Overlay touch locked=' + str(locked).lower() +
        r' session=([^\s]+) leg=(\d+)', trace)
    assert matches, 'No service acceptance log for desired overlay lock state'
    return matches[-1]


def notification_record(d):
    raw = d.shell('dumpsys', 'notification', '--noredact')
    found = next((p for p in raw.split('NotificationRecord(')
        if 'pkg=app.floatingfocus' in p and 'id=1 ' in p), None)
    assert found, 'No current formal FF notification record'
    return found


def notification_unlock(d, expected_binding, name):
    """Read fresh live SystemUI nodes without waiting for a ticking clock to idle."""
    d.shell('cmd','statusbar','expand-notifications');time.sleep(.3)
    try:
        tree=live_dump(d)
        try:node=control(d,'解锁浮窗',tree)
        except AssertionError:
            parents={c:p for p in tree.iter('node') for c in p}
            title=next(n for n in tree.iter('node') if 'Floating Focus' in n.get('text',''))
            row=title;expand=None
            while row is not None:
                expand=next((n for n in row.iter('node') if n.get('resource-id','').endswith('/expand_button')),None)
                if expand is not None:break
                row=parents.get(row)
            assert expand is not None,'Actual FF notification expand control unavailable'
            x,y,r,b=d.bounds(expand);d.tapxy((x+r)//2,(y+b)//2)
            tree=live_dump(d);node=control(d,'解锁浮窗',tree)
        assert node.get('package')=='com.android.systemui'
        x,y,r,b=d.bounds(node)
        (d.output/(name+'-notification-hierarchy.xml')).write_bytes(ET_to_bytes(tree))
        d.shot(name+'-notification-before-actual-tap')
        d.shell('input','tap',(x+r)//2,(y+b)//2)
        wait_for(lambda:not window_state(d)['notTouchable'],'Observed SystemUI action did not unlock native window')
        assert bound_state(logs(d),False)==expected_binding,'Notification unlocked another session/leg'
    finally:
        d.shell('input','keyevent','KEYCODE_BACK')
        def shade_closed():
            focus=re.search(r'mCurrentFocus=(.*)',d.shell('dumpsys','window'))
            return focus is not None and any(x in focus[1] for x in ('app.floatingfocus','com.android.gallery3d','com.android.launcher3'))
        wait_for(shade_closed,'Notification shade did not finish closing before C4 input')
        time.sleep(.3)


def ET_to_bytes(tree):
    import xml.etree.ElementTree as ET
    return ET.tostring(tree,encoding='utf-8')


def start_full_c4(d, seconds, sound=False):
    fixture(d, seconds=seconds, c4=True)
    if sound:
        d.stop()
        d.write_prefs('c4_settings', dict(enabled=True, sound=True, noticeAcknowledged=True))
        d.start()
    d.home_tab('预设')
    d.shell('logcat', '-c')
    d.tap('启动预设 Controls', scroll=True)
    wait_for(lambda: d.overlay() is not None and any(r['status'] == 'RUNNING' for r in d.records()),
        'Real C4 preset did not start')
    first = window_state(d)
    assert not first['notTouchable'], 'New C4 session inherited a previous touch lock'
    compact = d.frame()
    d.tapxy(*d.center())
    wait_for(lambda: d.frame()[3]-d.frame()[1] > compact[3]-compact[1], 'Full C4 device did not expand')
    wait_for(lambda: 'C4 phase=ARMED' in logs(d), 'C4 keyboard is not actually ARMED')
    current = window_state(d)
    assert current['id'] == first['id']
    return current, next(r for r in reversed(d.records()) if r['status'] == 'RUNNING')['id']


def lock_and_attempt_touches(d, name):
    home(d)
    tap_control(d, '锁定浮窗')
    wait_for(lambda: window_state(d)['notTouchable'], 'Main lock did not update native window')
    locked = window_state(d)
    binding = bound_state(logs(d), True)
    assert locked['alpha'] <= .7 + .001, 'C4 lock increased its original fixed opacity'
    notice = notification_record(d)
    assert '解锁浮窗' in notice
    if 'C4 phase=EXPLODING' not in logs(d):
        assert '结束计时' not in notice, 'Code-protected C4 advertises normal End'
    (d.output/(name+'-locked-notification.txt')).write_text(notice, encoding='utf-8')
    prior = key_results(logs(d))
    for char in '56':
        d.shell('input', 'tap', *key_point(d, char))
    g = c4_device_geometry(d)
    # The LCD is a real body drag/toggle area, separate from the keyboard.
    sx = int(g['x'] + (2+g['w']*.5)*d.density)
    sy = int(g['y'] + (2+g['screenY']+g['screenHeight']/2)*d.density)
    d.shell('input', 'swipe', sx, sy, max(40, sx-100), sy+70, 450)
    after = window_state(d)
    assert after['id'] == locked['id'] and after['frame'] == locked['frame'], 'Locked C4 moved or rebuilt'
    assert after['notTouchable']
    assert key_results(logs(d)) == prior, 'A real key attempt reached C4 while touch locked'
    d.shot(name+'-locked-input-drag-ignored')
    return locked, binding


def input_survives_notification_unlock(d):
    original, rid = start_full_c4(d, 44, sound=True)
    d.shell('input', 'tap', *key_point(d, '7'))
    wait_for(lambda: key_results(logs(d))[-1:] == [('CHANGED', '1')], 'First actual C4 digit not accepted')
    media_before = logs(d).count('C4 media start=pre_explosion')
    locked, binding = lock_and_attempt_touches(d, 'interaction-c4-input')
    elapsed_before = record(d, rid)['elapsedMillis']
    notification_unlock(d, binding, 'interaction-c4-input')
    assert 'C4 phase=EXPLODING' not in logs(d), 'Input preservation was not tested before zero'
    d.shell('input', 'tap', *key_point(d, '3'))
    wait_for(lambda: key_results(logs(d))[-1:] == [('CHANGED', '2')],
        'Unlock reset/changed prior input or locked key attempts were accepted')
    assert window_state(d)['id'] == original['id'] == locked['id']
    assert abs(window_state(d)['alpha']-.7) < .001, 'C4 opacity did not restore after unlock'
    wait_for(lambda: record(d, rid)['elapsedMillis'] > elapsed_before, 'Clock did not continue through touch lock')
    trace = logs(d)
    media_after = trace.count('C4 media start=pre_explosion')
    assert media_after == media_before, 'Lock/unlock restarted pre-explosion playback by logs'
    d.shot('interaction-c4-input-preserved-after-notification-unlock')
    # Clear the two entered digits and use the normal seven-digit exit.
    d.shell('input', 'tap', *key_point(d, '#'))
    for char in '7355608':
        d.shell('input', 'tap', *key_point(d, char))
    wait_for(lambda: d.overlay() is None, 'Normal C4 code did not end the unlocked session', timeout=8)
    (d.output/'interaction-c4-input.log').write_text(logs(d), encoding='utf-8')
    return dict(sessionLeg=binding, nativeWindow=original['id'], inputPreserved=True,
        lockedPhysicalKeysIgnored=True, lockedDragIgnored=True, realNotificationUnlock=True,
        continuedClock=True, audioNotRestartedByLogs=bool(media_before),
        audioBoundary='No claim about heard output; no available media start means playback continuity unverified')


def natural_zero_while_locked(d):
    original, rid = start_full_c4(d, 20)
    locked, binding = lock_and_attempt_touches(d, 'interaction-c4-expiry')
    elapsed_before = record(d, rid)['elapsedMillis']
    wait_for(lambda: 'C4 phase=OVERTIME' in logs(d), 'Locked C4 did not naturally reach dedicated overtime', timeout=30)
    trace = logs(d)
    assert trace.count('C4 explosion consumed') == 1
    assert 'C4 phase=EXPLODING' in trace and 'C4 key result=DEFUSED' not in trace
    current = window_state(d)
    assert current['id'] == original['id'] and current['notTouchable'] and current['frame'] == locked['frame']
    assert record(d, rid)['elapsedMillis'] > elapsed_before
    assert record(d, rid)['status'] == 'RUNNING', 'Touch lock ended the C4 session'
    d.shot('interaction-c4-natural-overtime-still-locked')
    notification_unlock(d, binding, 'interaction-c4-expiry')
    point=key_point(d,'#')
    (d.output/'interaction-expired-key-target.json').write_text(json.dumps(dict(point=point,frame=d.frame(),geometry=c4_device_geometry(d))),encoding='utf-8')
    (d.output/'interaction-expired-input-before.txt').write_text(d.shell('dumpsys','input'),encoding='utf-8')
    (d.output/'interaction-expired-window-before.txt').write_text(window_state(d)['raw'],encoding='utf-8')
    d.shot('interaction-expired-before-end-key')
    d.shell('input','tap',*point)
    try:wait_for(lambda:d.overlay() is None,'Unlocked expired C4 # did not end normally')
    finally:
        (d.output/'interaction-expired-input-after.txt').write_text(d.shell('dumpsys','input'),encoding='utf-8')
        (d.output/'interaction-expired-touch.log').write_text(logs(d),encoding='utf-8')
    assert record(d, rid)['status'] == 'MANUAL'
    (d.output/'interaction-c4-expiry.log').write_text(logs(d), encoding='utf-8')
    return dict(sessionLeg=binding, naturalZero=True, explosionOnce=True,
        dedicatedOvertimeSameLockedWindow=True, realNotificationUnlock=True, normalEndAfterZero=True)


def recovery_resets_lock(d):
    setup(d)
    seed_recovery(d)
    def resume_candidate():
        tap_control(d, '继续计时 · ', prefix=True)
        d.tap('Interaction older manual', scroll=True)
        tap_control(d, '继续计时')
    resume_candidate()
    wait_for(lambda: d.overlay() is not None, 'Initial reliable recovery failed')
    running = next(r for r in reversed(d.records()) if r['status'] == 'RUNNING')
    assert running.get('stopwatch', {}).get('continuationOf') == 'interaction-old'
    assert not window_state(d)['notTouchable'], 'New recovery was locked'
    tap_control(d, '关闭')
    tap_control(d, '锁定浮窗')
    wait_for(lambda: window_state(d)['notTouchable'], 'Could not lock recovery source before process termination')
    d.stop(); d.start()
    wait_for(lambda: record(d, running['id'])['status'] != 'RUNNING', 'Reliable orphan checkpoint not recovered', timeout=15)
    assert d.overlay() is None, 'Process restart automatically recreated a locked overlay'
    resume_candidate()
    wait_for(lambda: d.overlay() is not None and any(r.get('stopwatch', {}).get('continuationOf') == running['id']
        for r in d.records()), 'Explicit continuation of the previously locked session failed')
    assert not window_state(d)['notTouchable'], 'Recovery inherited the source touch lock'
    child = next(r for r in d.records() if r.get('stopwatch', {}).get('continuationOf') == running['id'])
    assert child['stopwatch']['carriedMillis'] == running['stopwatch']['carriedMillis'] + record(d, running['id'])['elapsedMillis']
    d.shot('interaction-recovery-from-locked-session-starts-unlocked')
    tap_control(d, '关闭'); end(d)
    return dict(source=running['id'], continuation=child['id'], explicitRecoveryUnlocked=True, noAutomaticRestart=True)


def c4_lock_companion(d):
    prepare_live_dump(d)
    d.shell('input','keyevent','KEYCODE_BACK')
    time.sleep(.4)
    result = dict(naturalZero=natural_zero_while_locked(d),
        input=input_survives_notification_unlock(d), recovery=recovery_resets_lock(d),
        limits='AOSP emulator only; physical audio/vibration and HyperOS touch policy remain unverified')
    (d.output/'interaction-c4-companion.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    return result
