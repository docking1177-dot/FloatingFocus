"""Real pager, normal overtime previews/C4 expiry and native SystemUI tile.

Only called by the isolated device runner. Tile cleanup removes our own addition;
it refuses a pre-existing FF tile. No secure lock credential is set by this suite.
"""
import hashlib
import json
import re
import shlex
import time
from device_c4 import c4_device_geometry

PACKAGE = 'app.floatingfocus'
TILE = PACKAGE+'/.FocusTileService'


def wait_for(predicate, message, timeout=8):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        if predicate(): return
        time.sleep(.15)
    raise AssertionError(message)


def texts(d): return [n.get('text','') for n in d.dump().iter('node')]


def top(d):
    for _ in range(6): d.shell('input','swipe',int(d.width*.04),int(d.height*.3),int(d.width*.04),int(d.height*.86),180)


def fixture(d, seconds=180, c4=False, shape='ROUNDED_RECTANGLE'):
    d.stop()
    appearance=dict(shape=shape,color='BLUE',baseSize=1.0,opacity=.7,intensity=1.0,
                    colorEnabled=False,sizeEnabled=False)
    d.write_prefs('onboarding',{'completed':True})
    d.write_prefs('home_navigation',{'order':'timer,presets,apps'})
    d.write_prefs('quick_durations',dict(seconds='300,600,900,1800',startup_mode='COUNTDOWN',startup_seconds=seconds))
    d.write_prefs('reminder_settings',appearance)
    d.write_prefs('c4_settings',dict(enabled=c4,sound=False,noticeAcknowledged=True))
    d.write_prefs('quick_launch',{'targets':json.dumps([dict(package='com.android.gallery3d',label='Gallery')])})
    preset=dict(id='controls',name='Controls',minutes=max(1,seconds//60),durationSeconds=seconds,
                settings=appearance,mode='COUNTDOWN',c4Enabled=c4,targetPackage='com.android.gallery3d',targetLabel='Gallery')
    d.write_prefs('timer_presets',{'items':json.dumps([preset])})
    d.start()


def start_preset(d,seconds,c4=False,shape='ROUNDED_RECTANGLE'):
    fixture(d,seconds,c4,shape)
    d.home_tab('预设'); d.tap('启动预设 Controls',scroll=True)
    wait_for(lambda:d.overlay() and 'com.android.gallery3d' in d.foreground(),'Preset did not start')
    time.sleep(.4)
    return d.overlay()[0]


def logs(d): return d.shell('logcat','-d','-s','FloatingFocus:*')


def key_point(d,char):
    g=c4_device_geometry(d); i='123456789*0#'.index(char)
    return (int(g['x']+(2+g['left']+i%3*(g['kw']+g['gap'])+g['kw']/2)*d.density),
            int(g['y']+(2+g['top']+i//3*(g['kh']+g['gap'])+g['kh']/2)*d.density))


def home_swipe(d):
    fixture(d)
    raw=d.shell('run-as',PACKAGE,'cat','shared_prefs/timer_presets.xml')
    import xml.etree.ElementTree as ET
    sample=json.loads(ET.fromstring(raw).find("string[@name='items']").text)[0]
    d.stop(); d.write_prefs('timer_presets',{'items':json.dumps([dict(sample,id='swipe-'+str(i),name='Swipe '+str(i)) for i in range(21)])}); d.start()
    def selected(label):
        assert d.home_tab_node(label).get('selected')=='true', 'Wrong selected page: '+label
        expected={'计时':'倒计时','预设':'我的预设'}
        seen=texts(d)
        assert all(h not in seen for key,h in expected.items() if key!=label), 'Other page exposed as visible'
        assert not any(n.get('content-desc')=='首页入口 App' for n in d.dump().iter('node')), 'Legacy App tab remains'
    def swipe(left=True):
        a,b=(.85,.15) if left else (.15,.85)
        d.shell('input','swipe',int(d.width*a),int(d.height*.65),int(d.width*b),int(d.height*.65),350); time.sleep(.4)
    d.tap('10分'); swipe(); selected('预设')
    d.shell('input','swipe',int(d.width*.6),int(d.height*.82),int(d.width*.6),int(d.height*.38),400)
    tree=d.dump()
    before=[(n.get('content-desc'),d.bounds(n)) for n in tree.iter('node') if n.get('content-desc','').startswith('启动预设 ') and d.bounds(n)[3]>d.bounds(n)[1]]
    assert before and before[0][0]!='启动预设 Swipe 0', 'Preset list did not scroll'
    swipe(); selected('预设')  # Last page has no phantom third entrance.
    swipe(False); selected('计时')
    swipe(); selected('预设')
    after=[(n.get('content-desc'),d.bounds(n)) for n in d.dump().iter('node') if n.get('content-desc','').startswith('启动预设 ') and d.bounds(n)[3]>d.bounds(n)[1]]
    assert before==after, 'Swipe discarded preset scroll'
    swipe(False); selected('计时')
    a=d.bounds(d.home_tab_node('预设')); b=d.bounds(d.home_tab_node('计时'))
    print('Home swipe reorder points:',a,b,flush=True)
    d.shell('input','draganddrop',(a[0]+a[2])//2,(a[1]+a[3])//2,(b[0]+b[2])//2,(b[1]+b[3])//2,700); time.sleep(.4)
    saved=ET.fromstring(d.adb('shell','run-as',PACKAGE,'cat','shared_prefs/home_navigation.xml')).find("string[@name='order']").text
    assert saved=='presets,timer', ('Reorder destination',saved,a,b)
    selected('计时'); swipe(False); selected('预设')
    d.tap('设置'); d.shell('input','keyevent','KEYCODE_BACK'); selected('预设')
    d.shot('home-swipe-reordered-presets')
    d.stop(); d.start(); selected('预设')
    d.shot('home-swipe-cold-first-entry')
    return dict(realSwipesAndClickSync=True,customOrderRespected=True,scrollAndSelectionRetained=True,
                verticalScrollDoesNotSwitch=True,coldFirstEntry=True)


def overtime(d):
    fixture(d)
    before=d.records()
    protected={n:hashlib.sha256(d.adb('shell','run-as',PACKAGE,'cat','shared_prefs/'+n+'.xml')).hexdigest()
               for n in ('timer_presets','reminder_settings','c4_settings')}
    previews=[]
    for value,label in [('OVERTIME_FIRST','超时主显'),('TOTAL_FIRST','累计主显'),('SIDE_BY_SIDE','并列读数')]:
        d.tap('设置'); d.tap('开发者模式',scroll=True)
        d.tap('选择超时布局 '+label,scroll=True)
        d.tap('折叠预览',scroll=True)
        for _ in range(3): d.shell('input','swipe',int(d.width*.04),int(d.height*.83),int(d.width*.04),int(d.height*.3),220)
        d.shot('overtime-preview-'+value.lower()); previews.append(value)
        assert value in d.shell('run-as',PACKAGE,'cat','shared_prefs/debug_overtime_style.xml')
        assert d.overlay() is None, 'Preview started an overlay'
        d.shell('input','keyevent','KEYCODE_BACK'); d.shell('input','keyevent','KEYCODE_BACK')
    assert d.records()==before, 'Preview wrote a timer record'
    for n,v in protected.items(): assert hashlib.sha256(d.adb('shell','run-as',PACKAGE,'cat','shared_prefs/'+n+'.xml')).hexdigest()==v
    d.stop(); d.start()
    assert 'SIDE_BY_SIDE' in d.shell('run-as',PACKAGE,'cat','shared_prefs/debug_overtime_style.xml')
    for shape in ('ROUNDED_RECTANGLE','VERTICAL_CAPSULE','CIRCLE'):
        wid=start_preset(d,1,shape=shape)
        wait_for(lambda:d.records()[-1].get('overtimeMillis',0)>0,'Normal timer did not expire',5)
        d.shot('normal-overtime-'+shape)
        old=d.frame(); x,y,r,b=old
        # Time header remains at the top of the two-row expanded content.
        d.tapxy((x+r)/2,y+(b-y)*.3); folded=d.frame()
        assert d.overlay()[0]==wid and folded!=old, 'Overtime did not fold in the same window'
        d.tapxy(*d.center()); assert d.overlay()[0]==wid
        d.finish()
    # # down before zero must not turn into End on its post-zero release.
    d.shell('logcat','-c'); wid=start_preset(d,9,c4=True)
    d.tapxy(*d.center()); time.sleep(.3)
    kx,ky=key_point(d,'#')
    d.shell('input','swipe',kx,ky,kx,ky,10000)
    assert d.overlay() and d.overlay()[0]==wid, 'Pre-zero # became a post-zero End'
    wait_for(lambda:'C4 phase=OVERTIME' in logs(d),'C4 did not persist beyond explosion tail',10)
    assert logs(d).count('C4 explosion consumed')==1
    full=d.frame(); d.shot('c4-overtime-full')
    x,y,r,b=full; d.tapxy(x+(r-x)*.6,y+(b-y)*.2)
    compact=d.frame(); assert compact!=full and d.overlay()[0]==wid
    d.shot('c4-overtime-compact')
    d.shell('input','swipe',*d.center(),int(d.width*.35),int(d.height*.65),450)
    assert d.overlay()[0]==wid and d.frame()!=compact
    d.tapxy(*d.center()); d.tapxy(*key_point(d,'#')); d.wait_stopped()
    assert d.records()[-1]['status']=='MANUAL'
    # End is available during the tail too; no defuse voice or extra explosion.
    d.shell('logcat','-c'); start_preset(d,1,c4=True)
    wait_for(lambda:'C4 phase=EXPLODING' in logs(d),'No C4 explosion',4)
    d.tapxy(*d.center()); d.tapxy(*key_point(d,'#')); d.wait_stopped()
    assert 'C4 phase=OVERTIME' not in logs(d) and 'start=defused' not in logs(d)
    return dict(previews=previews,previewInert=True,styleRestored=True,normalShapesFoldExpand=True,
                c4PersistsAfterTail=True,c4OvertimeToggleDragEnd=True,zeroCrossingPressCancelled=True,
                endDuringTail=True,audioEvidence='muted; no listening assertion')


def overtime_preview(d):
    font=d.shell('settings','get','system','font_scale').strip()
    fixture(d);before=d.records()
    try:
        d.tap('设置');d.tap('开发者模式',scroll=True)
        d.tap('选择超时布局 并列读数',scroll=True)
        d.tap('长时长',scroll=True);d.tap('展开预览')
        for scale in ('1.0','2.0'):
            d.shell('settings','put','system','font_scale',scale);time.sleep(.8)
            for shape in ('横向胶囊','纵向胶囊','圆形'):
                top(d);d.tap(shape,scroll=True)
                for _ in range(3):d.shell('input','swipe',int(d.width*.04),int(d.height*.83),int(d.width*.04),int(d.height*.3),200)
                tree=d.dump();labels=' '.join(n.get('text','')+' '+n.get('content-desc','') for n in tree.iter('node'))
                assert '8:00:00' in labels and '+5:00:00' in labels, 'Long readouts missing from real preview semantics'
                d.shot('overtime-long-'+str(('横向胶囊','纵向胶囊','圆形').index(shape))+'-font-'+scale)
                assert d.overlay() is None and d.records()==before, 'Inert preview started a real session'
        return dict(longTextsExposed=True,allShapesAtTwoFontScales=True,previewNoSession=True,
                    visualEvidence='Raw screenshots require separate inspection; semantics alone do not prove no clipping')
    finally:
        if font=='null':d.shell('settings','delete','system','font_scale')
        else:d.shell('settings','put','system','font_scale',font)


def tile(d):
    original=d.shell('settings','get','secure','sysui_qs_tiles').strip()
    assert 'FocusTileService' not in original, 'Refusing to change a pre-existing FF tile'
    owned=False
    cached_point=None
    click_count=0
    def collapse(): d.shell('cmd','statusbar','collapse'); time.sleep(.3)
    def system_ui(): return d.shell('dumpsys','activity','service','com.android.systemui/.SystemUIService')
    def tile_state():
        raw=system_ui()
        (d.output/'tile-system-ui.txt').write_text(raw,encoding='utf-8')
        lines=[line for line in raw.splitlines() if 'QSTile$' in line and 'spec=custom(app.floatingfocus/' in line]
        assert lines, 'Native SystemUI has no FF tile state'
        return lines[0]
    def tile_node():
        for _ in range(4):
            tree=d.dump(); parents={c:p for p in tree.iter('node') for c in p}
            node=next((n for n in tree.iter('node') if n.get('package')=='com.android.systemui' and
                       ('FF计时' in n.get('text','') or 'FF计时' in n.get('content-desc',''))),None)
            if node is not None:
                while node.get('clickable')!='true' and node in parents: node=parents[node]
                assert node.get('clickable')=='true', 'FF tile has no clickable control'
                return node
            # A newly added tile is normally on a later real QS page. This is
            # navigation only: never mutate the user's tile order to find it.
            d.shell('input','swipe',int(d.width*.85),int(d.height*.43),int(d.width*.15),int(d.height*.43),350)
        raise AssertionError('Native SystemUI FF tile not found on its QS pages')
    def panel(live=False):
        for _ in range(3):
            if live:
                if re.search(r'(?m)^\s+mQsExpanded: true\s*$',system_ui()): return
            elif any(n.get('resource-id','').endswith('/footer_page_indicator') for n in d.dump().iter('node')): return
            # Begin at the actual system edge, not inside an underlying app
            # while startActivityAndCollapse is still completing its transition.
            d.shell('input','swipe',int(d.width*.76),1,int(d.width*.76),int(d.height*.82),450)
            time.sleep(.6)
        # After a rotation this API36 SystemUI can retain its compact/split
        # shade. Use its public shell navigation, then require real QS state;
        # confirmation and tile clicks still go through actual SystemUI controls.
        d.shell('cmd','statusbar','expand-settings')
        wait_for(lambda:re.search(r'(?m)^\s+mQsExpanded: true\s*$',system_ui()),
                 'SystemUI did not open QS after expand-settings',5)
        return
    def click(live=False):
        nonlocal cached_point,click_count
        panel(live)
        if live:
            assert cached_point is not None
            # Ticking labels prevent UIAutomator idle; cached coordinates alone
            # cannot prove the pager reached the tile's page. Route through the
            # documented SystemUI click command, never an app-private Intent.
            assert d.shell('settings','get','secure','sysui_qs_tiles').strip().endswith('FocusTileService)')
            assert 'label=FF计时' in tile_state()
            click_count+=1
            d.shot('tile-before-click-'+str(click_count))
            d.shell('cmd','statusbar','click-tile',TILE)
            return
        else:
            n=tile_node(); x,y,r,b=d.bounds(n); cached_point=((x+r)/2,(y+b)/2)
        click_count+=1
        d.shot('tile-before-click-'+str(click_count))
        d.tapxy(*cached_point)
    def finish_from_settings_if_needed():
        # The idle tile's controlled start correctly restores the existing FF
        # settings task. Return normally, never clear its back stack to end.
        collapse();d.start()
        if '.SettingsActivity' in d.foreground(): d.shell('input','keyevent','KEYCODE_BACK')
        assert '.MainActivity' in d.foreground()
        d.tap('结束计时');d.wait_stopped();d.home_tab('计时')
    try:
        fixture(d,180); d.tap('设置'); d.tap('控制中心快捷开关',scroll=True)
        tree=d.dump()
        add=next((n for n in tree.iter('node') if n.get('package')=='com.android.systemui' and
                  n.get('text','').lower() in ('add','add tile','添加','添加磁贴')),None)
        assert add is not None, 'Native add-tile confirmation not shown'
        d.shot('tile-system-confirmation')
        x,y,r,b=d.bounds(add); d.tapxy((x+r)/2,(y+b)/2); owned=True
        wait_for(lambda:'FocusTileService' in d.shell('settings','get','secure','sysui_qs_tiles'),'Tile not actually added')
        panel(); tile_node(); d.shot('tile-idle')
        before=len(d.records()); click(); wait_for(lambda:d.overlay() is not None,'Tile idle start failed')
        wait_for(lambda:'.WidgetStartActivity' not in d.foreground(),'Default-start entry did not close')
        record=d.records()[-1]; assert len(d.records())==before+1 and record['plannedMillis']==180000 and record['mode']=='COUNTDOWN'
        click(live=True); panel(live=True); wait_for(lambda:'继续' in tile_state(), 'Tile did not offer resume after pause',5)
        d.shot('tile-paused'); collapse()
        time.sleep(1)
        # Persisted history may use a different checkpoint cadence; the tile's
        # clock/capability and JVM snapshot tests establish pause semantics.
        click(live=True); panel(live=True); wait_for(lambda:'暂停' in tile_state(), 'Tile did not offer pause after resume',5)
        assert d.records()[-1]['id']==record['id'] and len(d.records())==before+1
        d.shot('tile-running'); finish_from_settings_if_needed()
        # The immutable session flag applies before the final 44.439 seconds too.
        start_preset(d,120,c4=True)
        record=d.records()[-1]
        panel(live=True)
        assert '查看' in tile_state() and '暂停' not in tile_state(), 'Pre-stage C4 tile offers pause'
        click(live=True);collapse()
        assert '.MainActivity' in d.foreground() and d.records()[-1]['id']==record['id']
        # Global settings changing mid-session must not restore a Pause command.
        d.shell('input','swipe',*d.center(),int(d.width*.8),int(d.height*.78),450)
        d.tap('设置');d.tap('提醒');d.tap('C4 倒计时')
        panel(live=True)
        assert '查看' in tile_state() and '暂停' not in tile_state(), 'Global switch changed active C4 policy'
        click(live=True);collapse()
        d.tap('结束计时');d.wait_stopped()
        start_preset(d,1); wait_for(lambda:d.records()[-1].get('overtimeMillis',0)>0,'No overtime')
        d.shell('input','swipe',*d.center(),int(d.width*.8),int(d.height*.78),450)
        d.start();d.tap('设置')
        rid=d.records()[-1]['id']; click(live=True); collapse()
        assert '.MainActivity' in d.foreground() and d.overlay() and d.records()[-1]['id']==rid
        d.finish()
        d.shell('logcat','-c'); start_preset(d,30,c4=True)
        click(live=True); collapse()
        assert '.MainActivity' in d.foreground() and d.overlay()
        notice=d.shell('dumpsys','notification','--noredact')
        entries=notice.split('NotificationRecord(')
        current=next(p for p in entries if 'pkg=app.floatingfocus' in p and 'id=1 ' in p)
        assert '结束计时' not in current, 'Pre-zero C4 notification advertises invalid End'
        (d.output/'tile-c4-notification.txt').write_text(current,encoding='utf-8')
        d.shot('tile-c4-open-only')
        d.tapxy(*d.center())
        for c in '7355608': d.shell('input','tap',*key_point(d,c))
        d.wait_stopped()
        return dict(nativeConfirmationAndActualTile=True,idleUsesSavedDefaults=True,pauseResumeSameSession=True,
                    overtimeOpenWithoutEnding=True,c4OpenOnly=True,shortC4NotificationLocked=True,
                    preStageC4OpenOnly=True,globalChangeKeepsSessionPausePolicy=True,
                    liveTouchEvidence='Real SystemUI add confirmation and idle tile touch; live commands use public cmd statusbar click-tile, native tile state and actual session checked; live physical paging not accepted',
                    lockscreen='not tested; no secure credential configured',launcher='SystemUI AOSP API36')
    finally:
        (d.output/'tile-actions.log').write_text(logs(d),encoding='utf-8')
        collapse()
        if owned:
            d.shell('cmd','statusbar','remove-tile',TILE)
            if original=='null': d.shell('settings','delete','secure','sysui_qs_tiles')
            else: d.shell('settings','put','secure','sysui_qs_tiles',shlex.quote(original))
            assert d.shell('settings','get','secure','sysui_qs_tiles').strip()==original, 'Tile layout restore failed'
