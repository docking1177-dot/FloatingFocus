"""Daily quotas using actual UI, service, atomic files and the Launcher3 ID entry.

Fixtures are app-private and owned by the dedicated runner's full data backup.
Calendar/clock edge cases use JVM tests; no emulator system clock is changed.
"""
import datetime
import json
import time
from device_preferences import pref_values, set_wheel, wheel

PACKAGE = 'app.floatingfocus'
APP = 'com.android.gallery3d'


def seed_quota(d, limit=180000, used=10000):
    wall = int(d.shell('date', '+%s').strip()) * 1000
    elapsed = int(float(d.shell('cat', '/proc/uptime').split()[0]) * 1000)
    boot = int(d.shell('settings', 'get', 'global', 'boot_count').strip())
    start = int(datetime.datetime.fromtimestamp(wall / 1000, datetime.timezone.utc)
                .replace(hour=0, minute=0, second=0, microsecond=0).timestamp()) * 1000
    row = dict(version=1, zoneId='UTC', resetMinute=0, pendingReset=-1,
               clock=dict(logicalMillis=wall, elapsedMillis=elapsed, boot=boot),
               day=dict(startMillis=start, endMillis=start+86400000, used={APP: used}),
               history=[], apps={APP: dict(limitMillis=limit, enabled=True, enabledSince=wall-1000)}, cursor={})
    d.adb('shell', 'run-as', PACKAGE, 'tee', 'files/app_quotas.json', data=json.dumps(row).encode())
    d.write_prefs('app_quota_meta', dict(initialized=True))
    return row


def ledger(d):
    return json.loads(d.adb('shell', 'run-as', PACKAGE, 'cat', 'files/app_quotas.json'))


def quota(d):
    def wait(check, message, seconds=10):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            if check(): return
            time.sleep(.2)
        raise AssertionError(message)
    def texts(): return [n.get('text', '') for n in d.dump().iter('node')]
    def items(): return json.loads(pref_values(d, 'timer_presets')['items'])
    def used(): return ledger(d)['day']['used'].get(APP, 0)
    def remaining():
        row = ledger(d); return max(0, row['apps'][APP]['limitMillis'] - row['day']['used'].get(APP, 0))
    def end():
        d.finish(); wait(lambda: ledger(d)['cursor'].get('closed'), 'Quota did not durably settle')
    def started(before, expected, mode='COUNTDOWN'):
        wait(lambda: d.overlay() and len(d.records()) == before+1, 'No unique quota session')
        row = d.records()[-1]
        assert row['packageName'] == APP and row['mode'] == mode and row['plannedMillis'] == expected, row
        assert APP in d.foreground(), 'Wrong target App'
        return row
    def enter(id):
        d.shell('am', 'start', '-n', PACKAGE+'/.PresetShortcutActivity', '-a', PACKAGE+'.action.LAUNCH_PRESET', '--es', 'preset_id', id)
    def open_editor(name):
        d.start(); d.home_tab('预设'); d.tap('管理', scroll=True); d.tap('编辑 '+name, scroll=True)
    def quota_editor():
        d.start(); d.home_tab('App'); d.tap('管理（1）'); d.tap('每日额度', scroll=True)
    def click_desktop(name):
        d.shell('input', 'keyevent', 'KEYCODE_HOME')
        for direction in (None, 'left', 'left', 'right', 'right', 'right'):
            if direction:
                a,b = (.85,.15) if direction == 'left' else (.15,.85)
                d.shell('input', 'swipe', int(d.width*a), int(d.height*.5), int(d.width*b), int(d.height*.5), 350)
            nodes = [n for n in d.dump().iter('node') if n.get('text') == name and n.get('package') == 'com.android.launcher3']
            if nodes:
                d.shot('quota-actual-desktop-icon'); x,y,r,b = d.bounds(nodes[0]); d.tapxy((x+r)/2, (y+b)/2); return
        raise AssertionError('Real pinned quota icon not found')

    result = {}
    try:
        d.seed(); d.finish(); d.stop()
        seed_quota(d)
        appearance = dict(shape='ROUNDED_RECTANGLE', color='BLUE', baseSize=.75, opacity=.7, intensity=0., colorEnabled=False, sizeEnabled=False)
        one = dict(id='quota-test-one', name='QuotaOne', minutes=1, durationSeconds=30, mode='COUNTDOWN', settings=appearance,
                   targetPackage=APP, targetLabel='Gallery', c4Enabled=False, positionX=.8, positionY=.8)
        two = dict(one, id='quota-test-two', name='QuotaTwo', timeSource='APP_DAILY_QUOTA')
        fixed = dict(one, id='quota-test-fixed', name='Fixed', durationSeconds=1)
        d.write_prefs('timer_presets', {'items':json.dumps([one, two, fixed])})
        d.write_prefs('quick_durations', dict(seconds='300,600,900,1800', startup_mode='COUNTDOWN', startup_seconds=30))
        d.start()
        print('Quota: independent preset draft and real duration editor...', flush=True)
        open_editor('QuotaOne'); d.tap('今日 App 剩余额度', scroll=True); d.tap('取消')
        assert items()[0].get('timeSource', 'FIXED') == 'FIXED'
        open_editor('QuotaOne'); d.tap('今日 App 剩余额度', scroll=True); d.tap('保存')
        assert items()[0]['timeSource'] == 'APP_DAILY_QUOTA'
        quota_editor(); d.tap('每日总时长'); assert wheel(d, '分')[1] == 3 and wheel(d, '秒')[1] == 0
        set_wheel(d, '秒', 5); d.tap('确认'); d.tap('取消')
        assert ledger(d)['apps'][APP]['limitMillis'] == 180000 and used() == 10000
        d.tap('每日额度', scroll=True); d.tap('每日总时长'); set_wheel(d, '秒', 5); d.tap('确认'); d.tap('保存')
        wait(lambda: ledger(d)['apps'][APP]['limitMillis'] == 185000, 'Quota edit not persisted')
        assert used() == 10000
        d.shot('quota-app-management'); d.tap('取消')
        result['draftAndLimitPreserveUsage'] = True

        print('Quota: repeated home starts, latest ID entry and actual desktop icon...', flush=True)
        d.home_tab('预设'); before=len(d.records()); balance=remaining(); d.tap('启动预设 QuotaOne', scroll=True)
        first=started(before, balance)
        quota_editor(); d.tap('每日总时长'); set_wheel(d, '秒', 10); d.tap('确认')
        d.shot('quota-edit-active'); d.tap('保存')
        wait(lambda: ledger(d)['apps'][APP]['limitMillis'] == 190000, 'Active edit not saved')
        assert d.records()[-1]['plannedMillis'] == balance, 'Active edit reset countdown'
        d.tap('取消'); time.sleep(2); end(); used_one=used(); assert used_one > 10000
        before=len(d.records()); balance=remaining(); d.tap('首页入口 预设'); d.tap('启动预设 QuotaTwo', scroll=True)
        second=started(before, balance); assert second['plannedMillis'] < first['plannedMillis']
        enter(one['id']); assert '已有计时正在运行，请先结束。' in texts(); assert len(d.records()) == before+1
        d.tap('取消'); end(); result['sharedBalanceAndConflict'] = True
        d.home_tab('预设'); d.tap('管理', scroll=True); d.tap('添加到桌面 QuotaTwo', scroll=True)
        wait(lambda: 'launcher3' in d.foreground(), 'No system pin confirmation')
        for label in ('ADD TO HOME SCREEN', 'Add to Home screen', 'Add automatically', 'ADD', 'Add', '添加'):
            if label in texts(): d.tap(label); break
        else: raise AssertionError('System confirmation button missing')
        d.stop(); before=len(d.records()); balance=remaining(); click_desktop('QuotaTwo')
        started(before, balance); time.sleep(1); end(); result['actualDesktopLatestBalance'] = True

        print('Quota: bound stopwatch, overtime and interrupted process...', flush=True)
        d.home_tab('计时'); d.tap('正计时'); d.home_tab('App'); before=len(d.records()); prior=used()
        d.tap('打开 Gallery 并计时', scroll=True); started(before, 0, 'STOPWATCH'); time.sleep(1); end(); assert used() > prior
        d.home_tab('预设'); before=len(d.records()); prior=used(); d.tap('启动预设 Fixed', scroll=True)
        started(before, 1000); time.sleep(2); assert d.records()[-1].get('overtimeMillis', 0) > 0
        end(); assert used() > prior; result['stopwatchAndOvertimeDeduct'] = True
        before=len(d.records()); balance=remaining(); enter(one['id']); started(before, balance)
        time.sleep(2); d.stop(); saved=used(); time.sleep(2); d.start(); d.home_tab('预设'); time.sleep(.5)
        assert used() == saved, 'Stopped process time was charged'
        assert d.records()[-1]['status'] == 'INTERRUPTED'
        d.shot('quota-restarted-balance'); result['interruptedNoBackfill'] = True

        print('Quota: exhausted and unreadable ledgers fail closed...', flush=True)
        d.stop(); seed_quota(d, limit=30000, used=33000); d.start(); d.home_tab('预设'); before=len(d.records())
        d.tap('启动预设 QuotaOne', scroll=True); assert '该 App 今日额度已用完。' in texts()
        assert d.overlay() is None and len(d.records()) == before
        enter(two['id']); assert '该 App 今日额度已用完。' in texts(); assert d.overlay() is None
        d.shot('quota-exhausted'); result['exhaustedNoStart'] = True
        d.stop(); d.adb('shell', 'run-as', PACKAGE, 'tee', 'files/app_quotas.json', data=b'{broken')
        d.start(); d.home_tab('预设'); d.tap('启动预设 QuotaOne', scroll=True)
        assert any('额度读取或保存失败' in t for t in texts())
        assert d.overlay() is None and len(d.records()) == before
        d.shot('quota-read-failure'); result['readFailureNoRefill'] = True
        return result
    finally:
        d.stop()
        d.shell('input', 'keyevent', 'KEYCODE_BACK')
        # Runner refuses original pins, so this removes only this suite's FF identities.
        d.shell('cmd', 'shortcut', 'clear-shortcuts', '--user', '0', PACKAGE)
