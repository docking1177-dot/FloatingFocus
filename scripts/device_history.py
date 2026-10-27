"""Local-session statistics, chart/filter interaction, CSV and read-error retry."""
import calendar
import csv
import datetime as dt
import io
import json
import time

PACKAGE = 'app.floatingfocus'


def history(d):
    def texts():
        return [n.get('text', '') for n in d.dump().iter('node')]

    def top():
        for _ in range(4):
            d.shell('input', 'swipe', int(d.width*.03), int(d.height*.25), int(d.width*.03), int(d.height*.86), 250)

    def open_history():
        d.start()
        d.tap('时长记录', scroll=True)
        assert '.HistoryActivity' in d.foreground()

    def chart():
        return next(n for n in d.dump().iter('node') if n.get('content-desc', '').startswith('计时时长条形图'))

    def choose_app(label):
        top()
        node = next(n for n in d.dump().iter('node') if n.get('content-desc', '').startswith('筛选 App：'))
        x,y,r,b = d.bounds(node)
        d.tapxy((x+r)//2, (y+b)//2)
        d.tap(label, scroll=True)

    def write_record(record):
        d.adb('shell', 'run-as', PACKAGE, 'tee', 'files/timer_records/'+record['id']+'.json',
              data=json.dumps(record, ensure_ascii=False).encode('utf-8'))

    def saved_fixtures():
        return {row['id']: d.adb('shell', 'run-as', PACKAGE, 'cat', 'files/timer_records/'+row['id']+'.json') for row in rows}

    def duration(millis):
        seconds = millis//1000
        parts = [(seconds//3600, '小时'), (seconds//60 % 60, '分钟'), (seconds % 60, '秒')]
        return ' '.join(str(value)+unit for value,unit in parts if value) or ('不足1秒' if millis else '0秒')

    night = d.shell('cmd', 'uimode', 'night').strip().split()[-1]
    font = d.shell('settings', 'get', 'system', 'font_scale').strip()
    rotation = d.shell('settings', 'get', 'system', 'user_rotation').strip()
    auto_rotate = d.shell('settings', 'get', 'system', 'accelerometer_rotation').strip()
    export_path = None
    try:
        d.stop()
        d.write_prefs('onboarding', dict(completed=True))
        d.write_prefs('c4_settings', dict(enabled=False, sound=True))
        d.shell('run-as', PACKAGE, 'rm', '-rf', 'files/timer_records')
        d.shell('run-as', PACKAGE, 'mkdir', '-p', 'files/timer_records')
        now = int(d.shell('date', '+%s').strip())*1000
        offset = d.shell('date', '+%z').strip()
        minutes = int(offset[1:3])*60 + int(offset[3:5])
        zone = dt.timezone(dt.timedelta(minutes=minutes if offset[0] == '+' else -minutes))
        date = dt.datetime.fromtimestamp(now/1000, zone)
        day = date.replace(hour=0, minute=0, second=0, microsecond=0)
        start = int(day.timestamp()*1000)
        available = max(now-start-1000, 10_000)
        part = min(600_000, available//10)

        def row(id_, pkg, label, at, elapsed, status='MANUAL'):
            return dict(id=id_, packageName=pkg, appName=label, startedWallMillis=at, elapsedMillis=elapsed,
                        plannedMillis=300_000, mode='COUNTDOWN', status=status, overtimeMillis=max(0, elapsed-300_000))

        rows = [row('history-cross', 'com.android.gallery3d', '图库', start-part, part*2),
                row('history-gallery', 'com.android.gallery3d', '图库', start+available//6, min(1_800_000, available//10)),
                row('history-long', 'ff.history.missing', 'Long History App with a very long display name',
                    start+available//3, min(600_000, available//10), 'INTERRUPTED'),
                row('history-same-name', 'ff.history.other', '图库', start+available//2, min(300_000, available//10)),
                row('history-manual', '', '手动计时', start+available*2//3, min(900_000, available//10)),
                row('history-yesterday', 'ff.history.yesterday', 'Yesterday App', start-3_600_000, 600_000),
                row('history-old', 'ff.history.old', 'Old App', int((day.replace(day=1)-dt.timedelta(days=1)).timestamp()*1000), 120_000)]
        for record in rows:
            write_record(record)
        baseline = saved_fixtures()
        expected = sum(max(0, min(r['startedWallMillis']+r['elapsedMillis'], now)-max(r['startedWallMillis'], start)) for r in rows)
        open_history()
        assert '今日，共 ' in chart().get('content-desc') and '24 个时段' in chart().get('content-desc')
        assert '累计 '+duration(expected) in chart().get('content-desc')
        assert '含异常中断约计记录' in texts()
        d.shot('history-today')
        print('History: ranges and chart selection...', flush=True)
        # Precise picker is an accessible alternative to very narrow month bars.
        d.tap('查看时段', scroll=True)
        d.tap('00:00–01:00')
        assert '00:00–01:00' in texts()
        top()
        x,y,r,b = d.bounds(chart())
        d.tapxy(x+2, y+2)  # Blank chart margin clears selection.
        assert '00:00–01:00' not in texts()
        # Normal-font fixture uses a two-digit axis (22dp including the label gap).
        d.tapxy(x+22*d.density+(r-x-30*d.density)/48, b-40*d.density)
        assert '00:00–01:00' in texts(), 'First hourly bar did not respond to a real tap'
        d.shot('history-bar-selected')
        top(); d.tap('本周')
        assert '7 个时段' in chart().get('content-desc')
        d.shot('history-week')
        top(); d.tap('本月')
        assert f'{calendar.monthrange(date.year, date.month)[1]} 个时段' in chart().get('content-desc')
        d.shot('history-month')
        print('History: package filters, empty range and historical search...', flush=True)
        choose_app('com.android.gallery3d')  # Same labels are distinguished by package, never merged.
        assert any(n.get('content-desc') == '筛选 App：图库' for n in d.dump().iter('node'))
        d.shot('history-filtered-gallery')
        choose_app('Old App')
        top(); d.tap('今日')
        assert '此范围暂无记录' in texts()
        d.tap('查看全部 App', scroll=True)
        top()
        node = next(n for n in d.dump().iter('node') if n.get('content-desc') == '筛选 App：全部')
        x,y,r,b = d.bounds(node); d.tapxy((x+r)//2, (y+b)//2)
        field = next(n for n in d.dump().iter('node') if n.get('class') == 'android.widget.EditText')
        x,y,r,b = d.bounds(field); d.tapxy((x+r)//2, (y+b)//2)
        d.shell('input', 'text', 'Long')
        d.shell('input', 'keyevent', 'KEYCODE_BACK')
        d.tap('Long History App with a very long display name')
        assert '累计 '+duration(rows[2]['elapsedMillis']) in chart().get('content-desc')
        d.shot('history-long-app')
        print('History: saving and parsing ALL-session CSV...', flush=True)
        # Export remains ALL original sessions even with a single App selected.
        d.tap('导出全部记录')
        assert 'com.android.documentsui' in d.foreground()
        tree = d.dump()
        field = next(n for n in tree.iter('node') if n.get('class') == 'android.widget.EditText')
        filename = 'FF-history-regression-'+str(int(time.time()))+'.csv'
        x,y,r,b = d.bounds(field); d.tapxy((x+r)//2, (y+b)//2)
        d.shell('input', 'keyevent', 123)
        d.shell('input', 'keyevent', *([67]*80))
        d.shell('input', 'text', filename)
        d.shell('input', 'keyevent', 'KEYCODE_BACK')
        tree = d.dump()
        save = next(n for n in tree.iter('node') if n.get('text') in ('SAVE', 'Save', '保存') and n.get('enabled') == 'true')
        x,y,r,b = d.bounds(save); d.tapxy((x+r)//2, (y+b)//2)
        assert '.HistoryActivity' in d.foreground()
        # The dedicated AOSP fixture defaults to Downloads; retain evidence and remove only our export.
        export_path = '/sdcard/Download/'+filename
        raw = d.adb('exec-out', 'cat', export_path)
        (d.output/'history-export.csv').write_bytes(raw)
        exported = list(csv.DictReader(io.StringIO(raw.decode('utf-8-sig'))))
        assert len(exported) == len(rows) and {r['记录ID'] for r in exported} == {r['id'] for r in rows}
        assert saved_fixtures() == baseline, 'Chart, filters or export modified original history'
        d.tap('导出全部记录'); d.shell('input', 'keyevent', 'KEYCODE_BACK')
        assert any(n.get('content-desc', '').endswith('Long History App with a very long display name') for n in d.dump().iter('node'))
        # Rotation keeps the same filter/range; large text and dark mode use the same page/components.
        print('History: dark, large text and rotation...', flush=True)
        d.shell('cmd', 'uimode', 'night', 'yes')
        d.shell('settings', 'put', 'system', 'font_scale', '1.5')
        time.sleep(.8)
        top(); d.shot('history-dark-large-font')
        d.shell('settings', 'put', 'system', 'accelerometer_rotation', 0)
        d.shell('settings', 'put', 'system', 'user_rotation', 1)
        time.sleep(.8)
        assert any(n.get('content-desc', '').startswith('筛选 App：Long History') for n in d.dump().iter('node'))
        d.shot('history-landscape')
        d.shell('settings', 'put', 'system', 'user_rotation', 0)
        d.shell('settings', 'put', 'system', 'font_scale', font)
        d.shell('cmd', 'uimode', 'night', night)
        time.sleep(.6)
        # A malformed record is an error, not a false zero; retry must preserve an actual live session.
        print('History: read failure and live-session retry...', flush=True)
        d.stop()
        bad = 'files/timer_records/history-bad.json'
        d.adb('shell', 'run-as', PACKAGE, 'tee', bad, data=b'{invalid')
        open_history()
        assert '统计仅含成功读取的记录。' in texts()
        d.tap('返回')
        top(); d.tap('正计时'); d.tap('开始正计时')
        time.sleep(2)
        d.tap('时长记录', scroll=True)
        assert '含计时中会话 · 截至最近保存进度' in texts()
        d.shell('run-as', PACKAGE, 'rm', bad)
        d.tap('重试')
        assert '统计仅含成功读取的记录。' not in texts()
        assert '含计时中会话 · 截至最近保存进度' in texts()
        live = next(r for r in d.records() if r['id'] not in baseline)
        assert live['status'] == 'RUNNING'
        d.shot('history-live-after-retry')
        d.tap('返回'); top(); d.finish()
        assert saved_fixtures() == baseline
        # Empty storage exposes browsing controls and returns to the existing home to start a timer.
        d.stop()
        d.shell('run-as', PACKAGE, 'rm', '-rf', 'files/timer_records')
        d.shell('run-as', PACKAGE, 'mkdir', '-p', 'files/timer_records')
        open_history()
        assert '暂无记录' in texts()
        d.shot('history-empty')
        d.tap('去计时', scroll=True)
        assert '.MainActivity' in d.foreground()
        return dict(todayWeekMonth=True, sameNamePackagesSeparate=True, searchHistoricalApps=True,
                    uninstalledAppFallback=True, selectedBarAndBlankClear=True, exactPeriodPicker=True,
                    csvParsedRows=len(exported), exportAllDespiteFilter=True, exportCancelPreservesFilter=True,
                    originalRecordsUnchanged=True, readFailureAndRetry=True, retryKeepsLiveSession=True,
                    emptyState=True, darkLargeTextAndLandscape='screenshots require visual review')
    finally:
        d.shell('settings', 'put', 'system', 'font_scale', font)
        d.shell('settings', 'put', 'system', 'user_rotation', rotation)
        d.shell('settings', 'put', 'system', 'accelerometer_rotation', auto_rotate)
        d.shell('cmd', 'uimode', 'night', night)
        if export_path:
            d.shell('rm', '-f', export_path)
