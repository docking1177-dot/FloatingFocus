"""Actual screen power transitions and saved intervals on the dedicated test emulator."""
import csv
import io
import json
import re
import time

PACKAGE = 'app.floatingfocus'


def effective_millis(row):
    assert row['source'] == 'SCREEN_ON' and row['mode'] == 'STOPWATCH' and row['packageName'] == ''
    previous = total = 0
    for start, end in row['activeIntervals']:
        assert 0 <= previous <= start < end <= row['screenSpanMillis']
        total += end - start
        previous = end
    assert row['elapsedMillis'] == total
    return total


def screen_on(d):
    def until(check, message, seconds=12):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if check():
                return
            time.sleep(.25)
        raise AssertionError(message)

    def record(id_):
        return next(row for row in d.records() if row['id'] == id_)

    def home():
        d.start()
        for _ in range(4):
            if any(n.get('content-desc')=='设置' for n in d.dump().iter('node')):
                return
            d.shell('input','keyevent','KEYCODE_BACK')
        raise AssertionError('Could not return to the existing home without clearing the task')

    def open_page():
        home(); d.tap('设置'); d.tap('随亮屏计时', scroll=True)
        assert any(n.get('text') == '未开启' or n.get('text') == '亮屏计时中' or
                   n.get('text') == '已有计时，请先结束当前会话。' for n in d.dump().iter('node'))

    def switch():
        return next(n for n in d.dump().iter('node') if n.get('checkable') == 'true')

    def toggle():
        node = switch()
        assert node.get('enabled') == 'true'
        x,y,r,b = d.bounds(node); d.tapxy((x+r)//2, (y+b)//2)

    def visible_text(text, scroll=False):
        for _ in range(6 if scroll else 2):
            if any(n.get('text')==text for n in d.dump().iter('node')):
                return True
            if scroll:d.shell('input','swipe',d.width//2,int(d.height*.8),d.width//2,int(d.height*.3),300)
        return False

    def wake():
        d.shell('input', 'keyevent', 'KEYCODE_WAKEUP')
        until(lambda: 'mWakefulness=Awake' in d.shell('dumpsys', 'power'), 'Screen did not wake')

    def sleep():
        d.shell('input', 'keyevent', 'KEYCODE_SLEEP')
        until(lambda: 'mWakefulness=Asleep' in d.shell('dumpsys', 'power') or
              'mWakefulness=Dozing' in d.shell('dumpsys', 'power'), 'Screen did not sleep')

    timeout = d.shell('settings', 'get', 'system', 'screen_off_timeout').strip()
    keyguard_disabled = d.shell('locksettings','get-disabled').strip()
    lock_delay = d.shell('settings','get','secure','lock_screen_lock_after_timeout').strip()
    font = d.shell('settings','get','system','font_scale').strip()
    night = d.shell('cmd','uimode','night').strip().split()[-1]
    rotation = d.shell('settings','get','system','user_rotation').strip()
    auto_rotate = d.shell('settings','get','system','accelerometer_rotation').strip()
    export_path = None
    try:
        wake(); d.shell('wm', 'dismiss-keyguard')
        # This dedicated AVD had swipe-lock disabled; create no PIN/password and restore below.
        d.shell('locksettings','set-disabled','false')
        d.shell('settings','put','secure','lock_screen_lock_after_timeout','0')
        d.shell('settings', 'put', 'system', 'screen_off_timeout', '600000')
        d.stop()
        d.write_prefs('onboarding', dict(completed=True))
        d.write_prefs('c4_settings', dict(enabled=True, sound=False, noticeAcknowledged=True))
        d.write_prefs('overtime_reminders', dict(enabled=True, intervalSeconds=60))
        c4_before = d.adb('shell','run-as',PACKAGE,'cat','shared_prefs/c4_settings.xml')
        ids_before = {r['id'] for r in d.records()}
        # Enabling without permission must leave the switch off and create no record.
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','deny')
        open_page(); toggle()
        assert switch().get('checked') == 'false' and d.overlay() is None
        assert {r['id'] for r in d.records()} == ids_before
        d.shot('screen-on-missing-permission')
        d.stop(); d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','allow')
        open_page(); d.shot('screen-on-disabled'); toggle()
        until(lambda: d.overlay() is not None, 'Screen timer did not start')
        time.sleep(2)
        new_rows = [r for r in d.records() if r['id'] not in ids_before]
        assert len(new_rows) == 1
        id_ = new_rows[0]['id']
        assert effective_millis(record(id_)) >= 1000
        d.shot('screen-on-running')
        # End-to-end off gap: check persisted effective time, not just UI that stopped drawing.
        sleep(); time.sleep(1)
        off = record(id_); effective_millis(off)
        time.sleep(4)
        assert record(id_) == off, 'Off-screen record advanced without a screen event'
        d.shot('screen-on-off')
        wake(); time.sleep(3)
        locked = d.shell('dumpsys','window','policy')
        (d.output/'screen-on-keyguard.txt').write_text(locked,encoding='utf-8')
        assert re.search(r'KeyguardServiceDelegate\s*\n\s*showing=true', locked), 'No real keyguard showing; do not claim locked-screen coverage'
        after_wake = record(id_)
        assert effective_millis(after_wake) >= off['elapsedMillis'] + 1500
        assert len(after_wake['activeIntervals']) == 2
        d.shot('screen-on-locked-running')
        # Repeated wake requests must not create sessions or overlap intervals.
        wake(); wake(); time.sleep(1)
        assert {r['id'] for r in d.records()} - ids_before == {id_}
        effective_millis(record(id_))
        print('Screen timer: real on/off gap and locked wake verified',flush=True)
        d.shell('wm','dismiss-keyguard')
        d.shell('am','start','-W','-n','com.android.gallery3d/.app.GalleryActivity')
        until(lambda: 'com.android.gallery3d' in d.foreground(), 'Gallery did not resume')
        before_bg = record(id_)['elapsedMillis']; time.sleep(2)
        assert effective_millis(record(id_)) > before_bg
        # Return/recreate settings; enabling state is the service, never another persisted switch.
        open_page()
        assert switch().get('checked') == 'true'
        d.shell('cmd','uimode','night','yes');d.shell('settings','put','system','font_scale','1.5')
        time.sleep(.8)
        assert switch().get('checked')=='true' and record(id_)['status']=='RUNNING'
        d.shot('screen-on-dark-large')
        d.shell('settings','put','system','accelerometer_rotation','0')
        d.shell('settings','put','system','user_rotation','1');time.sleep(.8)
        assert switch().get('checked')=='true'
        assert {r['id'] for r in d.records()} - ids_before == {id_}
        d.shot('screen-on-landscape')
        d.shell('settings','put','system','user_rotation','0');time.sleep(.8)
        toggle(); d.wait_stopped()
        until(lambda: record(id_)['status'] == 'MANUAL','End was not saved')
        finished = record(id_)
        assert effective_millis(finished) < finished['screenSpanMillis'] - 3000
        sleep(); time.sleep(1); wake(); d.shell('wm','dismiss-keyguard'); time.sleep(1)
        assert d.overlay() is None and record(id_) == finished
        assert d.adb('shell','run-as',PACKAGE,'cat','shared_prefs/c4_settings.xml') == c4_before
        log = d.shell('logcat','-d','-s','FloatingFocus:D','*:S')
        assert 'C4 session enabled=false mode=STOPWATCH' in log
        assert 'C4 audio play=' not in log and 'Overtime schedule next=' not in log
        (d.output/'screen-on-transitions.log').write_text(log,encoding='utf-8')
        # Actual storage reload and export: all original columns remain, appended ones explain the gaps.
        d.stop(); d.start(); d.tap('时长记录',scroll=True)
        assert record(id_) == finished
        assert visible_text('亮屏计时',scroll=True), 'Saved screen record is not readable after scrolling'
        d.shot('screen-on-history')
        d.tap('导出全部记录')
        field = next(n for n in d.dump().iter('node') if n.get('class') == 'android.widget.EditText')
        x,y,r,b = d.bounds(field); d.tapxy((x+r)//2,(y+b)//2)
        filename='FF-screen-on-'+str(int(time.time()))+'.csv'
        d.shell('input','keyevent',123); d.shell('input','keyevent',*([67]*80)); d.shell('input','text',filename)
        d.shell('input','keyevent','KEYCODE_BACK')
        save=next(n for n in d.dump().iter('node') if n.get('text') in ('SAVE','Save','保存') and n.get('enabled')=='true')
        x,y,r,b=d.bounds(save);d.tapxy((x+r)//2,(y+b)//2)
        export_path='/sdcard/Download/'+filename
        raw=d.adb('exec-out','cat',export_path);(d.output/'screen-on-export.csv').write_bytes(raw)
        exported=next(row for row in csv.DictReader(io.StringIO(raw.decode('utf-8-sig'))) if row['记录ID']==id_)
        assert int(exported['累计秒数'])==finished['elapsedMillis']//1000
        assert int(exported['会话跨度秒数'])==finished['screenSpanMillis']//1000
        assert json.loads(exported['亮屏活动区间毫秒偏移'])==finished['activeIntervals']
        assert exported['计时口径']=='亮屏有效时长'
        print('Screen timer: configuration, end, storage reload and real CSV verified',flush=True)
        # Ordinary stopwatch conflict first, then an actual C4 code phase conflict.
        home();d.home_tab('计时');d.tap('正计时');d.tap('开始正计时')
        until(lambda:d.overlay() is not None,'Ordinary timer did not start')
        ordinary=d.records()[-1]
        open_page()
        assert switch().get('enabled')=='false' and switch().get('checked')=='false'
        assert d.records()[-1]['id']==ordinary['id']
        home();d.tap('结束计时');d.wait_stopped()
        d.stop();d.write_prefs('quick_durations',dict(seconds='300,600,900,1800',startup_mode='COUNTDOWN',startup_seconds=30))
        home();d.home_tab('计时');d.tap('开始倒计时')
        until(lambda:d.overlay() is not None,'C4 timer did not start')
        c4_record=d.records()[-1]
        assert c4_record['mode']=='COUNTDOWN' and c4_record['plannedMillis']==30000
        open_page()
        assert switch().get('enabled')=='false' and d.records()[-1]['id']==c4_record['id']
        d.shot('screen-on-c4-conflict')
        until(lambda:record(c4_record['id']).get('overtimeMillis',0)>0,'C4 did not reach natural overtime',seconds=38)
        home();d.tap('结束计时');d.wait_stopped()
        print('Screen timer: ordinary and C4-stage conflicts verified',flush=True)
        # Force stop has no cleanup callback. Recovery keeps only the saved checkpoint and stays off.
        open_page();toggle();until(lambda:d.overlay() is not None,'Second screen session missing')
        time.sleep(2); interrupted=d.records()[-1];effective_millis(interrupted)
        d.stop()
        checkpoint=record(interrupted['id']) # A final queued save may land between sampling and force-stop.
        sleep();time.sleep(2);wake();d.shell('wm','dismiss-keyguard');d.start()
        until(lambda:record(interrupted['id'])['status']=='INTERRUPTED','Interrupted record not recovered')
        recovered=record(interrupted['id'])
        assert recovered['elapsedMillis']==checkpoint['elapsedMillis']
        assert recovered['activeIntervals']==checkpoint['activeIntervals'] and d.overlay() is None
        open_page();assert switch().get('checked')=='false'
        # Revoking overlay permission ends the active screen session and prevents later wake restart.
        toggle();until(lambda:d.overlay() is not None,'Revocation fixture missing')
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','deny');d.wait_stopped()
        sleep();wake();d.shell('wm','dismiss-keyguard')
        assert d.overlay() is None
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','allow')
        # Corrupt new metadata must surface an incomplete-read error, not become continuous elapsed time.
        d.stop()
        bad=dict(finished,id='screen-on-invalid',elapsedMillis=10000,screenSpanMillis=10000,
                 activeIntervals=[[0,6000],[5000,9000]])
        d.adb('shell','run-as',PACKAGE,'tee','files/timer_records/screen-on-invalid.json',
              data=json.dumps(bad).encode())
        d.start();d.tap('时长记录',scroll=True)
        assert visible_text('统计仅含成功读取的记录。')
        d.shot('screen-on-malformed-intervals')
        return dict(permissionDeniedNoStart=True,sameSessionAcrossScreens=True,offGapExcluded=True,
                    lockedScreenCounted=True,duplicateWakeNoNewSession=True,backgroundContinues=True,
                    manualEndDisarms=True,ordinaryConflictPreserved=True,c4StageConflictPreserved=True,c4GlobalUnchanged=True,
                    persistedIntervalsReloaded=True,csvActiveAndSpanVerified=True,processRecoveryDoesNotAddDowntime=True,
                    permissionRevocationDisarms=True,configurationRebuildKeepsSession=True,
                    malformedIntervalsShowError=True,record=finished)
    finally:
        wake(); d.shell('wm','dismiss-keyguard')
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','allow')
        d.shell('settings','put','system','user_rotation',rotation)
        d.shell('settings','put','system','accelerometer_rotation',auto_rotate)
        d.shell('settings','put','system','font_scale',font)
        d.shell('cmd','uimode','night',night)
        d.shell('locksettings','set-disabled',keyguard_disabled)
        if lock_delay=='null':d.shell('settings','delete','secure','lock_screen_lock_after_timeout')
        else:d.shell('settings','put','secure','lock_screen_lock_after_timeout',lock_delay)
        if timeout=='null': d.shell('settings','delete','system','screen_off_timeout')
        else: d.shell('settings','put','system','screen_off_timeout',timeout)
        if export_path:d.shell('rm','-f',export_path)
