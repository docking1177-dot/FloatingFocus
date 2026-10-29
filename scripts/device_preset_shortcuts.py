"""Pinned preset shortcuts: real launcher confirmation, icons and controlled ID-only entry."""
import json
import re
import time
import xml.etree.ElementTree as ET
from device_c4 import has_timer_service

PACKAGE = 'app.floatingfocus'
ACTION = PACKAGE + '.action.LAUNCH_PRESET'
PINNED_MATCH_FLAGS = '4'  # Android ShortcutManager.FLAG_MATCH_PINNED (2 means dynamic).


def require_empty_pins(raw):
    if 'ShortcutInfo' in raw:
        raise AssertionError('Use an isolated test AVD without existing pinned FF shortcuts; system pins are not covered by the app-data backup.')


def preset_shortcuts(d):
    def texts(): return [n.get('text', '') for n in d.dump().iter('node')]
    def wait(check, message, seconds=12):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            if check(): return
            time.sleep(.3)
        raise AssertionError(message)
    def pinned(): return d.shell('cmd','shortcut','get-shortcuts','--user','0','--flags',PINNED_MATCH_FLAGS,PACKAGE)
    def entry(id, **extras):
        args=['am','start','-n',PACKAGE+'/.PresetShortcutActivity','-a',ACTION,'--es','preset_id',id]
        for key,value in extras.items(): args+=['--es',key,str(value)]
        d.shell(*args)
    def idle():
        assert d.overlay() is None
        assert not has_timer_service(d.shell('dumpsys','activity','services',PACKAGE))
    def home(): d.shell('input','keyevent','KEYCODE_HOME'); time.sleep(.5)
    def tap_any(labels):
        tree=d.dump(); parents={c:p for p in tree.iter('node') for c in p}
        for label in labels:
            for n in tree.iter('node'):
                if label.casefold() not in ((n.get('text') or '').casefold(),(n.get('content-desc') or '').casefold()): continue
                control=n
                while control is not None and control.get('clickable')!='true': control=parents.get(control)
                if control is not None:
                    x,y,r,b=d.bounds(control)
                    if b>y and r>x: d.tapxy((x+r)/2,(y+b)/2); return
        raise AssertionError('No action '+repr(labels)+' in '+repr(texts()))
    def pin(name, accept=True, allow_existing=False):
        d.start()
        if '管理预设方案' not in texts(): d.tap('管理',scroll=True)
        confirmations=d.shell('logcat','-d','-s','FloatingFocus:*').count('PIN confirmed id=')
        d.tap('添加到桌面 '+name,scroll=True)
        wait(lambda: '.launcher3' in d.foreground() or (allow_existing and
             d.shell('logcat','-d','-s','FloatingFocus:*').count('PIN confirmed id=')>confirmations), 'No system pin confirmation')
        if '.launcher3' in d.foreground():
            d.shot('shortcut-confirm-'+('accept' if accept else 'cancel'))
            tap_any(['Add automatically','Add to Home screen','Add to home screen','ADD','Add','添加','添加到主屏幕'] if accept else ['Cancel','CANCEL','取消'])
            wait(lambda: '.launcher3' not in d.foreground() or 'Add to Home screen' not in texts(), 'Pin dialog did not finish')
        if not accept:
            wait(lambda: any('尚未收到桌面确认' in text for text in texts()), 'Cancelled request remained busy')
            assert '查看应用权限设置' in texts()
            d.shot('shortcut-unconfirmed-help')
        d.start()
        if '管理预设方案' in texts(): d.tap('完成')
    def click_icon(name):
        home()
        # Launcher may place new icons on an additional workspace page.
        for direction in (None,'left','left','right','right','right'):
            if direction:
                a,b=(.85,.15) if direction=='left' else (.15,.85)
                d.shell('input','swipe',int(d.width*a),int(d.height*.5),int(d.width*b),int(d.height*.5),450)
            tree=d.dump()
            nodes=[n for n in tree.iter('node') if n.get('text')==name and n.get('package')=='com.android.launcher3']
            if nodes:
                d.shot('desktop-'+name.lower().replace(' ','-'))
                x,y,r,b=d.bounds(nodes[0]); d.tapxy((x+r)/2,(y+b)/2); return
        raise AssertionError('Pinned icon missing from actual desktop: '+name)
    def started(pkg='com.android.gallery3d', planned=90000):
        wait(lambda: pkg in d.foreground() and d.overlay() is not None, 'Target App/overlay missing')
        wait(lambda: len(d.records())>record_count[0], 'No App-open record')
        record_count[0]=len(d.records())
        row=d.records()[-1]
        assert row['packageName']==pkg and row['plannedMillis']==planned, row
        assert '.PresetShortcutActivity' not in d.foreground()
        d.shot('shortcut-running-'+row['id'])
        return row
    def seed(items, appearance=None):
        d.stop(); d.write_prefs('timer_presets',{'items':json.dumps(items)})
        if appearance is not None: d.write_prefs('reminder_settings',appearance)
        d.start()
    def rename(old,new):
        d.start(); d.tap('管理',scroll=True); d.tap('重命名 '+old,scroll=True)
        field=next(n for n in d.dump().iter('node') if n.get('class')=='android.widget.EditText')
        x,y,r,b=d.bounds(field); d.tapxy((x+r)/2,(y+b)/2)
        d.shell('input','keyevent',123); d.shell('input','keyevent',*([67]*45)); d.shell('input','text',new.replace(' ','%s'))
        d.tap('保存')
        def saved_name():
            raw=d.adb('shell','run-as',PACKAGE,'cat','shared_prefs/timer_presets.xml')
            rows=json.loads(ET.fromstring(raw).find("string[@name='items']").text)
            return any(row['name']==new for row in rows)
        wait(saved_name,'Rename did not save through existing management flow')
        d.shot('shortcut-renamed')

    before_pinned=pinned()
    require_empty_pins(before_pinned)  # Before any seed or foreground sync can disable a user's entries.
    no_original_pins='ShortcutInfo' not in before_pinned
    (d.output/'shortcuts-before.txt').write_text(before_pinned,encoding='utf-8')
    gallery=dict(package='com.android.gallery3d',label='Gallery')
    files=dict(package='com.android.documentsui',label='Files')
    appearance=dict(shape='ROUNDED_RECTANGLE',color='BLUE',baseSize=1.0,opacity=.7,colorEnabled=False,sizeEnabled=False)
    one=dict(id='desktop-test-one',name='FF Desktop One',minutes=2,durationSeconds=90,mode='COUNTDOWN',settings=appearance,
             targetPackage=gallery['package'],targetLabel=gallery['label'])
    two=dict(one,id='desktop-test-two',name='FF Desktop Two',durationSeconds=120)
    items=[one,two]
    record_count=[len(d.records())]
    try:
        d.stop(); d.write_prefs('onboarding',dict(completed=True)); d.write_prefs('c4_settings',dict(enabled=False,sound=False,noticeAcknowledged=True))
        d.write_prefs('quick_launch',{'targets':json.dumps([gallery,files])}); seed(items,appearance)
        base=record_count[0]
        print('Desktop shortcuts: cancel, confirm and actual icons...',flush=True)
        pin(one['name'],accept=False); idle(); assert len(d.records())==base
        assert 'ff.preset.desktop-test-one' not in pinned(), 'Rejected shortcut pinned'
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','deny')
        pin(one['name']); wait(lambda: 'ff.preset.desktop-test-one' in pinned(),'Shortcut not pinned')
        idle(); assert len(d.records())==base
        pin(one['name'],allow_existing=True)
        assert set(re.findall(r'id=(ff\.preset\.[^,\s}]+)',pinned()))=={'ff.preset.desktop-test-one'}
        pin(two['name']); wait(lambda: 'ff.preset.desktop-test-two' in pinned(),'Second shortcut not pinned')
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','allow')
        d.stop(); click_icon(one['name']); original=started(planned=90000)
        # Another fresh click is consumed, but cannot replace this same or another active preset.
        entry(two['id']); wait(lambda: '已有计时正在运行，请先结束。' in texts(),'Active session conflict missing')
        assert d.records()[-1]['id']==original['id'] and len(d.records())==record_count[0]
        d.tap('取消'); d.finish()
        click_icon(two['name']); started(planned=120000); d.finish()

        print('Desktop shortcuts: rename, latest snapshot, invalid inputs and store errors...',flush=True)
        rename(one['name'],'FF Renamed')
        wait(lambda: 'FF Renamed' in pinned(),'Pinned label not updated')
        items[0]=dict(one,name='FF Renamed',mode='STOPWATCH',durationSeconds=33,targetPackage=files['package'],targetLabel=files['label'],
                      settings=dict(appearance,color='PURPLE',shape='CIRCLE',baseSize=1.2),positionX=.1,positionY=.2)
        seed(items); click_icon('FF Renamed'); row=started(files['package'],0)
        assert row['mode']=='STOPWATCH'
        d.finish()
        # External mutable configuration must be ignored, including a bogus package and duration.
        entry(two['id'],app_package='invalid.external',duration='1',mode='STOPWATCH'); started(planned=120000); d.finish()
        entry('not-a-real-id'); wait(lambda: '该预设已删除，请在 FF 中重新添加。' in texts(),'Missing ID fell back')
        idle(); d.tap('取消')
        seed([dict(items[0],id='desktop-test-other',name=two['name']),items[1]])
        entry('desktop-test-other'); started(files['package'],0); d.finish()
        d.stop(); d.write_prefs('timer_presets',{'items':'not-json'}); entry(two['id'])
        wait(lambda: '暂时无法读取预设，请重试。' in texts(),'Read failure confused with missing')
        assert 'ff.preset.desktop-test-two' in pinned(); idle(); d.tap('取消')
        seed(items)

        print('Desktop shortcuts: permission return and recreation do not auto-launch...',flush=True)
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','deny'); click_icon(two['name'])
        wait(lambda: '授予悬浮窗权限' in texts(),'Missing permission gate')
        before=len(d.records()); idle()
        d.tap('授予悬浮窗权限'); assert 'settings' in d.foreground()
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','allow')
        d.shell('input','keyevent','KEYCODE_BACK')
        wait(lambda: '.PresetShortcutActivity' in d.foreground(),'Did not return to shortcut gate')
        assert len(d.records())==before and d.overlay() is None
        auto_rotation=d.shell('settings','get','system','accelerometer_rotation').strip()
        d.shell('settings','put','system','accelerometer_rotation','0')
        rotation=d.shell('settings','get','system','user_rotation').strip()
        try:
            d.shell('settings','put','system','user_rotation','1'); time.sleep(.7)
            assert d.overlay() is None and len(d.records())==before
            d.shell('settings','put','system','user_rotation','0'); time.sleep(.7)
            d.tap('继续启动'); started(planned=120000); d.finish()
        finally:
            d.shell('settings','put','system','user_rotation',rotation)
            d.shell('settings','put','system','accelerometer_rotation',auto_rotation)

        print('Desktop shortcuts: unavailable App, deletion and pending confirmation edits...',flush=True)
        broken=dict(two,targetPackage='missing.example.app',targetLabel='Missing')
        seed([items[0],broken]); entry(two['id']); wait(lambda: '修复预设' in texts(),'No binding repair')
        idle(); d.tap('取消')

        print('Desktop shortcuts: overtime and C4 cannot be replaced...',flush=True)
        seed([items[0],dict(two,durationSeconds=1)])
        entry(two['id']); overtime=started(planned=1000); time.sleep(1.3)
        entry(one['id']); wait(lambda: '已有计时正在运行，请先结束。' in texts(),'Overtime was replaced')
        assert d.records()[-1]['id']==overtime['id']; d.tap('取消'); d.finish()
        d.stop(); d.write_prefs('c4_settings',dict(enabled=True,sound=False,noticeAcknowledged=True))
        seed([items[0],dict(two,durationSeconds=40)])
        entry(two['id']); c4=started(planned=40000)
        entry(one['id']); wait(lambda: '已有计时正在运行，请先结束。' in texts(),'C4 shortcut bypass')
        assert d.overlay() and d.records()[-1]['id']==c4['id']
        d.shot('shortcut-c4-conflict'); d.tap('取消')
        d.stop(); d.write_prefs('c4_settings',dict(enabled=False,sound=False,noticeAcknowledged=True))

        seed(items)
        d.start(); d.tap('管理',scroll=True); d.tap('删除 '+two['name'],scroll=True); d.tap('删除')
        wait(lambda: '该预设已删除' in pinned(),'Deleted preset shortcut not disabled')
        entry(two['id']); wait(lambda: '该预设已删除，请在 FF 中重新添加。' in texts(),'Deleted ID executed another preset')
        idle(); d.tap('取消')
        # A normal in-app draft task must survive an independent desktop launch.
        seed(items); d.tap('管理',scroll=True); d.tap('编辑 FF Renamed',scroll=True)
        activities=d.shell('dumpsys','activity','activities')
        draft_tokens=set(re.findall(r'ActivityRecord\{(\w+) u0 app\.floatingfocus/\.SettingsActivity',activities))
        assert draft_tokens, 'No original draft activity'
        click_icon('FF Renamed'); started(files['package'],0)
        after=d.shell('dumpsys','activity','activities')
        assert all(token in after for token in draft_tokens), 'Desktop launch cleared original FF draft task'
        d.shot('shortcut-draft-task-preserved')
        (d.output/'shortcuts-after.txt').write_text(pinned(),encoding='utf-8')
        d.shot('shortcut-deleted')
        return dict(systemConfirmation=True,cancelDoesNotLaunch=True,unconfirmedHelp=True,repeatedIdReused=True,twoDesktopIcons=True,coldAndHotLaunch=True,
                    activeConflictPreserved=True,latestSnapshot=True,renamedLabel=True,sameNameDistinctId=True,
                    injectedExtrasIgnored=True,readFailureDistinct=True,permissionReturnExplicit=True,
                    recreationDoesNotReplay=True,unavailableRepair=True,deletedIdDisabled=True,
                    overtimeConflictPreserved=True,c4ConflictPreserved=True,draftTaskPreserved=True,
                    launcher='com.android.launcher3',noOriginalPins=no_original_pins,
                    notExecuted=['Unsupported launcher hardware','Physical phone launcher','Pin waiting edit/delete interaction race'])
    finally:
        d.stop(); d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','allow')
        # This package had no pinned entries before the suite: remove only the suite-created namespace.
        # Existing non-test pinned state is never cleared.
        if no_original_pins: d.shell('cmd','shortcut','clear-shortcuts','--user','0',PACKAGE)
