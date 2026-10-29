"""Real preset/current C4 ownership, draft persistence and same-session extension."""
import json
import time
from device_preferences import pref_values
from device_preset_shortcuts import require_empty_pins, PINNED_MATCH_FLAGS

PACKAGE = 'app.floatingfocus'


def preset_c4(d):
    def texts(): return [n.get('text', '') for n in d.dump().iter('node')]
    def logs(): return d.shell('logcat', '-d', '-s', 'FloatingFocus:*')
    def wait(check, message, seconds=10):
        until = time.monotonic()+seconds
        while time.monotonic() < until:
            if check(): return
            time.sleep(.2)
        raise AssertionError(message+'\n'+logs()[-1500:])
    def items(): return json.loads(pref_values(d, 'timer_presets')['items'])
    def current(): return pref_values(d, 'c4_settings')
    def park():
        if d.overlay():
            x,y=d.center(); d.shell('input','swipe',x,y,int(d.width*.8),int(d.height*.86),350)
    def top():
        for _ in range(3): d.shell('input','swipe',int(d.width*.03),int(d.height*.25),int(d.width*.03),int(d.height*.85),200)
    def setup(global_enabled, saved=None, seconds=30, mode='COUNTDOWN', sound=False):
        d.stop(); d.write_prefs('onboarding',dict(completed=True))
        d.write_prefs('c4_settings',dict(enabled=global_enabled,sound=sound,noticeAcknowledged=True))
        d.write_prefs('quick_durations',dict(seconds='300,600,900,1800',startup_mode=mode,startup_seconds=seconds))
        d.write_prefs('quick_launch',{'targets':json.dumps([dict(package='com.android.gallery3d',label='Gallery')])})
        appearance=dict(shape='ROUNDED_RECTANGLE',color='BLUE',baseSize=1.0,opacity=.7,intensity=0.0,
                        colorEnabled=False,sizeEnabled=False)
        d.write_prefs('reminder_settings',appearance)
        row=dict(id='c4-source',name='Source',minutes=1,durationSeconds=seconds,mode=mode,settings=appearance,
                 targetPackage='com.android.gallery3d',targetLabel='Gallery',positionX=.8,positionY=.2)
        if saved is not None: row['c4Enabled']=saved
        d.write_prefs('timer_presets',{'items':json.dumps([row,dict(row,id='c4-other',name='Other',c4Enabled=True)])})
        d.start()
    def launch(kind, enabled, mode='COUNTDOWN'):
        before=current(); count=len(d.records()); d.shell('logcat','-c')
        if kind=='home': d.tap('启动预设 Source',scroll=True)
        elif kind=='desktop':
            d.shell('input','keyevent','KEYCODE_HOME')
            for direction in (None, 'left', 'left', 'right', 'right', 'right'):
                if direction:
                    a,b=(.85,.15) if direction=='left' else (.15,.85)
                    d.shell('input','swipe',int(d.width*a),int(d.height*.5),int(d.width*b),int(d.height*.5),450)
                nodes=[n for n in d.dump().iter('node') if n.get('text')=='Source' and n.get('package')=='com.android.launcher3']
                if nodes:
                    d.shot('preset-c4-desktop-icon'); x,y,r,b=d.bounds(nodes[0]); d.tapxy((x+r)/2,(y+b)/2); break
            else: raise AssertionError('Actual desktop icon missing')
        elif kind=='quick': d.tap('打开 Gallery 并计时',scroll=True)
        else: top(); d.tap('开始正计时' if mode=='STOPWATCH' else '开始倒计时')
        wait(lambda: d.overlay() is not None and len(d.records())>count,'No timer/record')
        expected=f'C4 session enabled={str(enabled).lower()} mode={mode}'
        wait(lambda: expected in logs(),'Wrong session C4 source')
        if kind!='manual': assert 'com.android.gallery3d' in d.foreground()
        if enabled: wait(lambda: 'C4 phase=' in logs(),'C4 never entered the countdown',seconds=25)
        else: assert 'C4 phase=' not in logs()
        assert current()==before, 'Launch changed current enable or sound'
        assert 'C4 media start=' not in logs(), 'Muted preset restored sound'
        row=d.records()[-1]; assert row['mode']==mode
        d.shot('preset-c4-'+kind+'-'+str(enabled)+'-'+mode)
        return row
    def editor(save):
        before=current(); d.start(); d.tap('管理',scroll=True); d.tap('编辑 Source',scroll=True)
        d.tap('C4 倒计时',scroll=True); d.shot('preset-c4-edit-'+str(save))
        d.tap('保存' if save else '取消')
        assert current()==before
    def flip_current():
        # am start can bring the existing FF task forward with its settings page
        # still on top. Reuse that actual page for consecutive toggle checks.
        if '.SettingsActivity' in d.foreground() and 'C4 倒计时' in texts():
            d.tap('C4 倒计时'); return
        d.start(); park(); top(); d.tap('设置'); d.tap('提醒'); d.tap('C4 倒计时')
    def pin():
        before=len(d.records()); d.start(); d.tap('管理',scroll=True); d.tap('添加到桌面 Source',scroll=True)
        wait(lambda: '.launcher3' in d.foreground(),'No official pin confirmation')
        tree=d.dump(); parents={c:p for p in tree.iter('node') for c in p}
        for node in tree.iter('node'):
            if node.get('text','').casefold() in ('add automatically','add to home screen','add','添加','添加到主屏幕'):
                while node is not None and node.get('clickable')!='true': node=parents.get(node)
                if node is not None:
                    x,y,r,b=d.bounds(node); d.tapxy((x+r)/2,(y+b)/2); break
        else: raise AssertionError('No system add action')
        wait(lambda: 'ff.preset.c4-source' in d.shell('cmd','shortcut','get-shortcuts','--user','0','--flags',PINNED_MATCH_FLAGS,PACKAGE),'Shortcut not actually pinned')
        assert d.overlay() is None and len(d.records())==before
        d.start()
        if '管理预设方案' in texts(): d.tap('完成')

    require_empty_pins(d.shell('cmd','shortcut','get-shortcuts','--user','0','--flags',PINNED_MATCH_FLAGS,PACKAGE))
    try:
        print('Preset C4: legacy off/current on, real desktop identity, draft cancel/save...',flush=True)
        setup(True)  # No field: legacy must never follow current.
        pin(); launch('home',False); d.stop(); d.start(); launch('desktop',False); d.stop(); d.start()
        original=items(); editor(False); assert items()==original
        editor(True)
        changed=items(); assert changed[0]['c4Enabled'] is True and changed[1]['c4Enabled'] is True
        assert all(changed[0][k]==original[0][k] for k in ('id','name','durationSeconds','mode','targetPackage'))
        assert abs(changed[0]['positionX']-original[0]['positionX'])<.00001 and abs(changed[0]['positionY']-original[0]['positionY'])<.00001
        flip_current(); assert not current()['enabled']
        d.stop(); d.start(); launch('home',True); d.stop(); d.start(); launch('desktop',True)
        # A longer leg leaves room for real UI edits after C4 enters at 44.439s.
        d.stop(); setup(False,True,seconds=60); launch('home',True)
        # Global enable edits during an active preset cannot remove its C4 phase.
        active=d.records()[-1]['id']; flip_current(); flip_current()
        assert not current()['enabled'] and d.records()[-1]['id']==active and 'C4 phase=OFF' not in logs()
        d.stop()

        print('Preset C4: save current, independent presets and normal/quick sources...',flush=True)
        setup(True,False); d.tap('保存当前')
        d.tap('Gallery'); d.tap('保存')
        rows=items(); assert len(rows)==3 and rows[-1]['c4Enabled'] and not rows[0]['c4Enabled']
        d.stop(); d.start(); assert items()[-1]['c4Enabled']
        for flag in (False,True):
            for kind in ('manual','quick'):
                setup(flag,not flag); launch(kind,flag); d.stop()
        setup(False,True,mode='STOPWATCH',sound=True)
        # No C4 media may even be scheduled for a saved-enabled stopwatch.
        launch('home',False,mode='STOPWATCH'); time.sleep(1)
        assert 'C4 media' not in logs() and 'C4 event=' not in logs(); d.stop()

        print('Preset C4: extension keeps both explicit session states...',flush=True)
        extensions=[]
        for saved in (False,True):
            setup(not saved,saved,seconds=1); row=launch('home',saved)
            time.sleep(8)  # Includes the unchanged explosion tail; then add time in the original window.
            d.start(); park(); top(); d.tap('加时'); time.sleep(.5)
            x,y,r,b=d.frame()
            d.shell('input','swipe',int(x+(r-x)*.35),int(y+(b-y)*.36),int(x+(r-x)*.35),int(y+(b-y)*.68),650)
            d.overlay_tap('确认加时',.65,.82)
            wait(lambda: d.records()[-1]['plannedMillis']==61000,'Extension did not apply exactly one minute')
            assert d.records()[-1]['id']==row['id']
            d.shell('logcat','-c'); time.sleep(18)
            assert ('C4 phase=' in logs())==saved, 'Extension re-read current C4 enable'
            assert current()['enabled']==(not saved) and current()['sound'] is False
            d.shot('preset-c4-extension-'+str(saved)); extensions.append(saved); d.stop()
        return dict(legacyOff=True,oppositeCurrentStates=True,homeAndActualDesktopMatch=True,desktopReadsEditedPreset=True,
                    draftCancelNoWrites=True,savedDraftSurvivesRestart=True,saveCurrentIncludesEnable=True,
                    twoPresetsIndependent=True,currentManualAndQuick=True,activeSnapshotStable=True,
                    extensionStates=extensions,globalSoundNotRestored=True,stopwatchHasNoC4Media=True,
                    realAudioListening='not performed; muted playback requests checked')
    finally:
        d.stop(); d.shell('cmd','shortcut','clear-shortcuts','--user','0',PACKAGE)
