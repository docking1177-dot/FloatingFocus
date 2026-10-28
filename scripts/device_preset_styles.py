"""Debug preset layout comparison using real data and the existing launch controller."""
import hashlib
import json
import re
import time

PACKAGE='app.floatingfocus'
STYLES=[('ICON_CARD','图标方案卡'),('ICON_TILE','图标启动块'),('COMPACT_ROW','紧凑快捷行')]


def preset_styles(d, focus=None):
    def texts(): return [n.get('text','') for n in d.dump().iter('node')]
    def scroll(down=True):
        a,b=(.70,.45) if down else (.45,.70)
        d.shell('input','swipe',int(d.width*.05),int(d.height*a),int(d.width*.05),int(d.height*b),800)
    def top():
        for _ in range(6): scroll(False)
    def find(label):
        for _ in range(16):
            tree=d.dump()
            parents={child:parent for parent in tree.iter('node') for child in parent}
            matches=[n for n in tree.iter('node') if label in (n.get('text'),n.get('content-desc')) and d.bounds(n)[3]>d.bounds(n)[1]]
            for node in matches:
                # Compose exposes the descriptive label below the Surface's
                # clickable semantics. Enabled belongs to that actual control.
                if node.get('content-desc')==label:
                    parent=parents.get(node)
                    while parent is not None:
                        if parent.get('clickable')=='true' or parent.get('checkable')=='true':
                            node=parent
                            break
                        parent=parents.get(parent)
                ancestor=parents.get(node)
                viewport=(0,0,d.width,d.height-48*d.density)
                while ancestor is not None:
                    if ancestor.get('scrollable')=='true':
                        viewport=d.bounds(ancestor)
                        break
                    ancestor=parents.get(ancestor)
                x,y,r,b=d.bounds(node)
                # A 1px clipped semantics label at the fold is not a tappable
                # control; tapping its full parent would hit system navigation.
                visible=min(b,viewport[3])-max(y,viewport[1])
                if visible>=min(b-y,96*d.density): return node
            scroll()
        raise AssertionError('Control missing: '+label)
    def tap(label):
        n=find(label); x,y,r,b=d.bounds(n)
        d.tapxy((x+r)/2,(max(y,72*d.density)+min(b,d.height-48*d.density))/2)
    def style_value():
        raw=d.shell('run-as',PACKAGE,'cat','shared_prefs/debug_preset_style.xml')
        return re.search(r'<string name="style">(.*?)</string>',raw)[1]
    def choose(value,label,expect_default=False):
        top(); d.tap('设置'); d.tap('开发者模式',scroll=True)
        if expect_default:
            option=find('选择预设样式 '+label)
            assert option.get('checked')=='true', 'First Debug use did not select the icon card'
            assert '<string name="style">' not in d.shell('run-as',PACKAGE,'cat','shared_prefs/debug_preset_style.xml')
        tap('选择预设样式 '+label)
        assert style_value()==value
        assert '.ReminderLabActivity' in d.foreground()
    def back_home():
        d.shell('input','keyevent','KEYCODE_BACK')
        assert '.SettingsActivity' in d.foreground()
        d.shell('input','keyevent','KEYCODE_BACK')
        assert '.MainActivity' in d.foreground()
    def no_session():
        assert d.overlay() is None
        assert 'ServiceRecord{' not in d.shell('dumpsys','activity','services',PACKAGE)
    def protected():
        return {p:hashlib.sha256(d.adb('shell','run-as',PACKAGE,'cat','shared_prefs/'+p+'.xml')).hexdigest()
                for p in ('timer_presets','reminder_settings','quick_launch')}
    def arrange():
        top()
        target=round(88*d.density)
        for _ in range(10):
            tree=d.dump()
            header=next((n for n in tree.iter('node') if n.get('text')=='我的预设'),None)
            y=d.bounds(header)[1] if header is not None else 0
            if 68*d.density<=y<=target+40*d.density: return tree
            shift=min(max(y-target,40*d.density),120*d.density) if y>0 else -120*d.density
            start=int(d.height*.65)
            d.shell('input','swipe',int(d.width*.05),start,int(d.width*.05),int(start-shift),1000)
        raise AssertionError('Could not frame the preset section heading')
    def controls(tree):
        return [n for n in tree.iter('node') if n.get('content-desc','').startswith('启动预设 ') and d.bounds(n)[3]>d.bounds(n)[1]]
    def seed(items,style=None):
        d.stop()
        d.write_prefs('timer_presets',{'items':json.dumps(items)})
        if style is not None: d.write_prefs('debug_preset_style',{'style':style})
        d.start()

    apps=[dict(package=p,label=n) for p,n in [('com.android.gallery3d','Gallery'),('com.android.music','Music'),
          ('com.android.messaging','Messaging'),('com.android.deskclock','Clock'),('app.unavailable.fixture','Unavailable')]]
    appearance=dict(shape='ROUNDED_RECTANGLE',color='BLUE',baseSize=1.0,opacity=.7,intensity=1.0,colorEnabled=True,sizeEnabled=True)
    names=['短视频 1分30秒','同一应用的长名称预设：阅读与休息交替，保留完整名称','随手正计时','已移除的应用预设','长一点的休息']
    items=[dict(id='style-'+str(i),name=name,minutes=1,durationSeconds=90+i*30,settings=appearance,
                mode='STOPWATCH' if i==2 else 'COUNTDOWN',targetPackage=apps[4 if i==3 else 0]['package'],
                targetLabel=apps[4 if i==3 else 0]['label']) for i,name in enumerate(names)]
    font=d.shell('settings','get','system','font_scale').strip()
    wm=d.shell('wm','size')
    override=re.search(r'Override size: (\d+x\d+)',wm)
    original_size=override[1] if override else 'reset'
    original_width,original_height=d.width,d.height
    try:
        assert focus in (None,'large_font')
        d.stop(); d.write_prefs('onboarding',{'completed':True})
        d.write_prefs('c4_settings',dict(enabled=False,sound=False,noticeAcknowledged=True))
        d.write_prefs('reminder_settings',appearance)
        d.write_prefs('quick_launch',{'targets':json.dumps(apps)})
        d.write_prefs('debug_preset_style',{})
        seed(items)
        original=protected(); before=len(d.records())
        print('Preset styles: selection, inert preview and same-data home layouts...',flush=True)
        for value,label in ([] if focus else STYLES):
            choose(value,label,expect_default=value=='ICON_CARD')
            preview=find('启动预设 '+names[0])
            assert preview.get('enabled')=='false', 'Preview item is actionable'
            x,y,r,b=d.bounds(preview); d.tapxy((x+r)/2,(y+b)/2)
            tap('新建预设')
            assert '.ReminderLabActivity' in d.foreground() and '保存预设方案' not in texts()
            no_session(); assert protected()==original and len(d.records())==before
            back_home()
            tree=arrange(); visible=controls(tree)
            assert len(visible)>=2
            first,second=map(d.bounds,visible[:2])
            if value=='COMPACT_ROW': assert first[0]==second[0] and second[1]>=first[3]
            else: assert first[0]!=second[0] and first[1]==second[1]
            d.shot('presets-'+value.lower())
            assert all(n.get('text')!='点击启动' for n in tree.iter('node'))
            # The quick launcher remains four columns with its own current-config summary.
            find('打开 Gallery 并计时')
            tree=d.dump()
            quick=[next(n for n in tree.iter('node') if n.get('content-desc')=='打开 '+app['label']+' 并计时') for app in apps[:4]]
            boxes=list(map(d.bounds,quick))
            assert len({b[0] for b in boxes})==4 and len({b[1] for b in boxes})==1
            assert protected()==original and len(d.records())==before
            d.stop(); d.start(); assert style_value()==value
            tree=arrange(); first,second=map(d.bounds,controls(tree)[:2])
            assert (first[0]==second[0])==(value=='COMPACT_ROW')

        print('Preset styles: empty/single/odd/many datasets...',flush=True)
        for value,label in ([] if focus else STYLES):
            for count in (0,1,3,12):
                data=[dict(items[i%5],id='count-'+str(i),name='浏览方案 '+str(i+1)) for i in range(count)]
                seed(data,value)
                seen=[]
                for _ in range(16):
                    tree=d.dump()
                    for n in controls(tree):
                        name=n.get('content-desc').removeprefix('启动预设 ')
                        if name not in seen: seen.append(name)
                    if any(n.get('content-desc')=='新建预设' and d.bounds(n)[3]>d.bounds(n)[1] for n in tree.iter('node')): break
                    scroll()
                assert seen==[p['name'] for p in data],(value,count,seen)
                tap('新建预设'); assert '保存预设方案' in texts(); d.tap('取消')

        print('Preset styles: small viewport and 2x font...',flush=True)
        d.shell('wm','size','640x1200'); d.width,d.height=640,1200
        d.shell('settings','put','system','font_scale','2.0')
        for value,label in STYLES:
            seed(items,value)
            tree=arrange(); d.shot('presets-large-font-'+value.lower())
            if value=='ICON_CARD':
                title=next(n for n in tree.iter('node') if n.get('text')==names[1])
                x,y,r,b=d.bounds(title)
                assert r-x>=96*d.density, 'Large name is squeezed beside its icon instead of receiving readable width'
            # Scroll to the last item without any horizontal scrolling or font resizing.
            n=find('启动预设 '+names[-1]); x,y,r,b=d.bounds(n)
            assert 0<=x<r<=d.width and b>y
        d.shell('wm','size',original_size); d.width,d.height=original_width,original_height
        d.shell('settings','put','system','font_scale',font if font!='null' else '1.0')
        if focus: return dict(scope='large-font-only diagnostic',largeNameReadableWidth=True)

        print('Preset styles: shared permission/failure/start/disabled behavior...',flush=True)
        for value,label in STYLES:
            seed(items,value)
            before=len(d.records())
            d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','deny')
            tap('启动预设 '+names[0]); assert '悬浮窗权限' in texts(); no_session()
            assert len(d.records())==before; d.tap('稍后')
            d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','allow')
            tap('启动预设 '+names[3])
            # Error is below the items; use the existing page's real error text.
            find('该 App 已卸载、停用或无法打开，请重新选择。')
            no_session(); assert len(d.records())==before
            top(); n=find('启动预设 '+names[0]); x,y,r,b=d.bounds(n)
            px,py=int((x+r)/2),int((y+b)/2)
            d.shell('sh','-c',f"'input tap {px} {py}; input tap {px} {py}'")
            deadline=time.monotonic()+6
            while time.monotonic()<deadline:
                if d.overlay() and 'com.android.gallery3d' in d.foreground() and len(d.records())==before+1: break
                time.sleep(.1)
            assert d.overlay() and 'com.android.gallery3d' in d.foreground()
            assert len(d.records())==before+1 and d.records()[-1]['plannedMillis']==90_000
            d.start(); n=find('启动预设 '+names[0]); assert n.get('enabled')=='false'
            # A style may still be changed during the session; the new layout stays disabled.
            top(); choose(*STYLES[(STYLES.index((value,label))+1)%3]); back_home()
            n=find('启动预设 '+names[0]); assert n.get('enabled')=='false'
            assert len(d.records())==before+1
            d.finish()
            deadline=time.monotonic()+3
            while d.records()[-1]['status']!='MANUAL' and time.monotonic()<deadline: time.sleep(.1)
            assert d.records()[-1]['status']=='MANUAL'
        return dict(threeRealLayouts=True,sameOrderAndData=True,quickGridUnchanged=True,previewsInert=True,
                    selectionRestoredAfterRestart=True,emptySingleOddMany=True,longNameAndStopwatch=True,
                    missingIconAndUnavailableApp=True,smallScreenAndLargeFont=True,sharedLaunchGuards=True,
                    activeSessionCanSwitchButNotLaunch=True,duplicateTapSingleSession=True,
                    screenshots=[f'presets-{v.lower()}.png' for v,_ in STYLES])
    finally:
        d.shell('appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','allow')
        d.shell('wm','size',original_size)
        d.shell('settings','delete' if font=='null' else 'put','system','font_scale',*([] if font=='null' else [font]))
        d.width,d.height=original_width,original_height
