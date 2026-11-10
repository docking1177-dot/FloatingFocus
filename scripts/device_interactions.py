"""Focused interaction regressions; use only through the isolated runner.

No standalone entry point: Device backup/restore, emulator identity, APK identity,
and source fingerprint checks belong to device_regression.main(). Screenshots
are retained for review; assertions use UI state, saved data, native windows,
and sampled framebuffer colors. Physical sound/vibration remains unverified.
"""
import collections
import json
import re
import struct
import shutil
import subprocess
import time

from device_controls import fixture, wait_for
from device_preferences import pref_values
from device_stopwatch_reminders import tap_setting
from device_timer_lifecycle import home, end, screen_environment, record

PACKAGE = 'app.floatingfocus'
THRESHOLD_KEY = 'maximum_obscuring_opacity_for_touch'


def texts(d, tree=None):
    return [n.get('text', '') for n in (tree if tree is not None else d.dump()).iter('node')]


def control(d, label, tree=None, prefix=False):
    """Find the clickable semantics ancestor, never a dialog title with equal text."""
    tree = d.dump() if tree is None else tree
    parents = {child: parent for parent in tree.iter('node') for child in parent}
    for leaf in tree.iter('node'):
        values = (leaf.get('text', ''), leaf.get('content-desc', ''))
        if not (any(v.startswith(label) for v in values) if prefix else label in values):
            continue
        node = leaf
        while node is not None:
            if node.get('clickable') == 'true':
                x, y, r, b = d.bounds(node)
                if r > x and b > y:
                    return node
            node = parents.get(node)
    raise AssertionError('Actual clickable control missing: ' + label)


def tap_control(d, label, prefix=False):
    node = control(d, label, prefix=prefix)
    assert node.get('enabled') == 'true', 'Control disabled: ' + label
    x, y, r, b = d.bounds(node)
    d.tapxy((x+r)//2, (y+b)//2)


def selected(d, label):
    node = control(d, label)
    return node.get('selected') == 'true' or node.get('checked') == 'true'


def ids(d):
    return {r['id'] for r in d.records()}


def no_session(d, expected_ids):
    assert d.overlay() is None, 'Read/selection/preview created a real overlay'
    assert ids(d) == expected_ids, 'Read/selection/preview created a timer record'
    assert not any(r['status'] == 'RUNNING' for r in d.records()), 'Unexpected running record'


def disabled_rules():
    return {key: dict(enabled=False, intervalSeconds=seconds, effects='',
        color=dict(mode='NONE', a=0xff4285f4-2**32, b=0xffffc857-2**32))
        for key, seconds in [('primary', 300), ('middle', 900), ('high', 1800)]}


def setup(d):
    fixture(d, seconds=600, c4=False)
    d.stop()
    d.write_prefs('stopwatch_behavior', dict(behavior='CONTINUOUS'))
    d.write_prefs('stopwatch_reminders', dict(rules=json.dumps(disabled_rules()),
        allow_sound=False, allow_vibration=False))
    d.write_prefs('overlay_positions', {})
    d.start()
    d.home_tab('计时')


def mode_memory(d):
    setup(d)
    before = ids(d)
    assert selected(d, '倒计时')
    tap_control(d, '正计时')
    wait_for(lambda: pref_values(d, 'quick_durations').get('home_last_mode') == 'STOPWATCH',
        'Explicit home mode was not persisted')
    saved = pref_values(d, 'quick_durations')
    assert saved['startup_mode'] == 'COUNTDOWN' and saved['startup_seconds'] == 600
    d.stop(); d.start(); d.home_tab('计时')
    assert selected(d, '正计时'), 'Cold home lost the explicitly selected mode'
    no_session(d, before)
    tap_control(d, '倒计时')
    d.stop(); d.start(); d.home_tab('计时')
    assert selected(d, '倒计时')
    assert pref_values(d, 'quick_durations')['startup_mode'] == 'COUNTDOWN'
    no_session(d, before)
    d.shot('interaction-home-last-mode')
    return dict(coldExplicitMode=True, defaultsIndependent=True, noAutomaticStart=True)


def seed_recovery(d):
    """All writes occur while stopped, inside the runner's app-data backup."""
    d.stop()
    now = int(d.shell('date', '+%s').strip())*1000
    def row(id_, name, elapsed, started, package='', carried=0, parent=None, preset=None):
        checkpoint = dict(version=1, behavior='CONTINUOUS', carriedMillis=carried,
            activity=dict(spanMillis=elapsed, intervals=[[0, elapsed]]), positions={},
            stopwatchReminders=disabled_rules())
        if parent:
            checkpoint.update(continuationOf=parent, rootId='interaction-parent')
        if preset:
            checkpoint['presetId'] = preset
        return dict(id=id_, appName=name, packageName=package, mode='STOPWATCH',
            plannedMillis=0, startedWallMillis=started, elapsedMillis=elapsed,
            status='INTERRUPTED', source='STOPWATCH', stopwatch=checkpoint)
    rows = [
        row('interaction-parent', 'Interaction consumed parent', 60000, now-180000),
        row('interaction-child', 'Interaction preset', 10000, now-30000,
            'com.android.gallery3d', 60000, 'interaction-parent', 'controls'),
        row('interaction-old', 'Interaction older manual', 12000, now-14*86400000)]
    for item in rows:
        d.adb('shell', 'run-as', PACKAGE, 'tee', 'files/timer_records/'+item['id']+'.json',
            data=json.dumps(item).encode())
    d.start(); d.home_tab('计时')
    return rows


def recovery_selection(d):
    setup(d)
    seed = seed_recovery(d)
    before = ids(d)
    tap_control(d, '继续计时 · ', prefix=True)
    # Both reliable records are available despite their different dates, and
    # the consumed parent is filtered by the real shared Recovery model.
    assert 'Interaction consumed parent' not in texts(d)
    tap_control(d, 'Interaction preset')
    seen = texts(d)
    assert any('来源 · 预设计时 · 续计段' in t for t in seen), seen
    assert any('其中承接 01:00' in t for t in seen), seen
    assert any('累计约 01:10' in t for t in seen), seen
    assert any('约计：停机时间不计' in t for t in seen), seen
    no_session(d, before)
    d.shot('interaction-recovery-source-detail')
    tap_control(d, '继续计时')
    wait_for(lambda: d.overlay() is not None and any(
        r.get('stopwatch', {}).get('continuationOf') == 'interaction-child' for r in d.records()),
        'Explicit recovery did not create one linked segment')
    children = [r for r in d.records() if r.get('stopwatch', {}).get('continuationOf') == 'interaction-child']
    assert len(children) == 1, 'Duplicate recovery segments'
    child = children[0]
    assert child['stopwatch']['carriedMillis'] == 70000
    assert child['appName'] == 'Interaction preset' and child['packageName'] == 'com.android.gallery3d'
    assert child['stopwatch']['stopwatchReminders'] == disabled_rules()
    assert '.MainActivity' in d.foreground(), 'Recovery opened the bound App'
    for original in seed:
        assert record(d, original['id']) == original, 'Browsing/recovery rewrote the source record'
    tap_control(d, '关闭')
    # Existing session permits browsing, but selecting an independent older
    # candidate must expose a disabled action rather than replacing the session.
    if d.overlay():
        d.shell('input', 'swipe', *d.center(), int(d.width*.86), int(d.height*.80), 450)
    tap_control(d, '继续计时 · ', prefix=True)
    d.tap('Interaction older manual', scroll=True)
    assert any('请先结束当前计时' in t for t in texts(d))
    resume = control(d, '继续计时')
    assert resume.get('enabled') == 'false', 'Active session allowed a second recovery'
    assert ids(d)-before == {child['id']}
    d.shot('interaction-recovery-active-conflict')
    tap_control(d, '关闭'); end(d)
    return dict(allDatesVisible=True, sourceDetails=True, selectionDoesNotStart=True,
        explicitLinkedSegment=True, carriedMillis=70000, sourceUnchanged=True,
        doesNotOpenApp=True, activeConflictDisabled=True)


def window_state(d):
    current = d.overlay()
    assert current, 'Native overlay absent'
    raw = d.shell('dumpsys', 'window', 'windows')
    chunks = re.split(r'(?=\n\s*Window #\d+)', raw)
    block = next((chunk for chunk in chunks if re.search(
        r'Window\{'+re.escape(current[0])+r' u0 app\.floatingfocus\}', chunk)), None)
    assert block, 'Current native window block not found'
    alpha = re.search(r'\balpha=([\d.]+)', block)
    assert alpha, 'Native window alpha was not reported; do not infer it from pixels'
    return dict(id=current[0], frame=current[1], alpha=float(alpha[1]),
        notTouchable=bool(re.search(r'\bNOT_TOUCHABLE\b', block)), raw=block)


def settings_target(d):
    # Gallery, unlike system Settings, does not hide overlays on this image.
    # Verify the actual foreign UID and menu; never use a hidden FF window as
    # proof of touch-through.
    d.shell('am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c',
        'android.intent.category.LAUNCHER', '-n', 'com.android.gallery3d/.app.GalleryActivity')
    wait_for(lambda: 'com.android.gallery3d' in d.foreground(), 'Gallery did not open')
    packages=d.shell('pm','list','packages','-U')
    uid=lambda name: re.search(r'package:'+re.escape(name)+r' uid:(\d+)',packages)[1]
    assert uid(PACKAGE)!=uid('com.android.gallery3d')
    node=control(d,'More options')
    x,y,r,b=d.bounds(node)
    return 'Select album',((x+r)//2,(y+b)//2),d.foreground()


def lock_touch_through(d):
    setup(d)
    old_threshold = d.shell('settings', 'get', 'global', THRESHOLD_KEY).strip()
    try:
        d.stop()
        d.write_prefs('reminder_settings', dict(shape='ROUNDED_RECTANGLE', color='BLUE', baseSize=1.0,
            opacity=.95, intensity=1.0, colorEnabled=False, sizeEnabled=False))
        # Exercise the runtime system limit, not an assumed permanent 0.8.
        d.shell('settings', 'put', 'global', THRESHOLD_KEY, '.55')
        limit = float(d.shell('settings', 'get', 'global', THRESHOLD_KEY).strip())
        d.start(); d.home_tab('计时'); tap_control(d, '正计时'); tap_control(d, '开始正计时')
        wait_for(lambda: d.overlay() is not None and any(r['status']=='RUNNING' for r in d.records()),
            'Real ordinary stopwatch did not start')
        timer_id = next(r['id'] for r in reversed(d.records()) if r['status']=='RUNNING')
        native_before = window_state(d)
        assert not native_before['notTouchable'] and abs(native_before['alpha']-.95)<.001
        title, point, settings_home = settings_target(d)
        # Move an unlocked real floating window to cover the actual Settings row.
        d.shell('input', 'swipe', *d.center(), *point, 450)
        wait_for(lambda: d.frame()[0]<point[0]<d.frame()[2] and d.frame()[1]<point[1]<d.frame()[3],
            'Could not position actual floating window over Gallery menu')
        covered = d.frame()
        assert covered[0] < point[0] < covered[2] and covered[1] < point[1] < covered[3]
        home(d); tap_control(d, '锁定浮窗')
        wait_for(lambda: window_state(d)['notTouchable'], 'Main lock action did not change native touchability')
        locked = window_state(d)
        assert locked['id'] == native_before['id'] and locked['alpha'] <= limit+.001
        assert abs(locked['alpha']-limit)<.001, (locked['alpha'], limit)
        assert pref_values(d)['opacity'] == .95, 'Lock changed the saved opacity'
        before_elapsed = record(d, timer_id)['elapsedMillis']
        # Frame must remain unchanged when an ordinary drag is attempted locked.
        d.shell('input', 'swipe', *d.center(), max(40, point[0]-140), point[1]+80, 450)
        assert d.frame() == locked['frame'], 'Locked overlay responded to drag'
        title_again, actual_point, settings_home_again = settings_target(d)
        assert title_again == title, 'Gallery target changed; refusing an unverified coordinate'
        # Navigation starts a fresh Settings homepage, so require the actual row
        # is still covered before using the tap as pass-through evidence.
        x, y, r, b = d.frame()
        assert x < actual_point[0] < r and y < actual_point[1] < b, 'Target not under the locked window'
        (d.output/'interaction-lock-before-window.txt').write_text(locked['raw'], encoding='utf-8')
        d.shot('interaction-locked-above-settings-row')
        d.tapxy(*actual_point)
        wait_for(lambda: title in texts(d), 'Tap under locked window did not open actual foreign-UID Gallery menu')
        assert 'com.android.gallery3d' in d.foreground() and d.overlay() is not None
        (d.output/'interaction-touch-through-activity.txt').write_text(
            settings_home_again+'\n'+d.foreground()+'\nObserved Select album popup in Gallery', encoding='utf-8')
        d.shot('interaction-settings-through-locked-overlay')
        wait_for(lambda: record(d, timer_id)['elapsedMillis'] > before_elapsed,
            'Timer did not continue checkpointing while locked', timeout=20)
        assert d.frame() == locked['frame'] and window_state(d)['notTouchable']
        home(d); tap_control(d, '解锁浮窗')
        wait_for(lambda: not window_state(d)['notTouchable'], 'Main unlock action failed')
        restored = window_state(d)
        assert abs(restored['alpha']-.95)<.001 and restored['id']==locked['id']
        assert pref_values(d)['opacity']==.95
        compact = d.frame(); anchor = d.center()
        d.tapxy(*anchor)
        wait_for(lambda: d.frame()[3]-d.frame()[1] > compact[3]-compact[1],
            'Unlocked floating window did not respond to expansion tap')
        d.tapxy(*anchor)
        wait_for(lambda: d.frame()[3]-d.frame()[1] == compact[3]-compact[1],
            'Unlocked floating window did not collapse at the original time anchor')
        previous = d.frame()
        cx, cy = d.center()
        d.shell('input', 'swipe', cx, cy, max(60,cx-160), min(d.height-100,cy+100), 450)
        wait_for(lambda: d.frame()!=previous, 'Unlocked floating window did not resume dragging')
        d.shot('interaction-unlocked-touch-drag-restored')
        end(d)
        return dict(realMainLockUnlock=True, nativeNotTouchable=True, configuredSystemLimit=limit,
            nativeLockedAlpha=locked['alpha'], savedOpacityPreserved=True, realGalleryMenu=True,
            lockedDragIgnored=True, lockedTimerContinues=True, unlockedTapDragRestored=True)
    finally:
        if old_threshold == 'null':
            d.shell('settings', 'delete', 'global', THRESHOLD_KEY)
        else:
            d.shell('settings', 'put', 'global', THRESHOLD_KEY, old_threshold)


def preview_rect(d):
    clocks = [n for n in d.dump().iter('node') if re.fullmatch(r'\d{2,}:\d{2}(?::\d{2})?', n.get('text',''))]
    assert len(clocks)==1, 'Expected one actual preview time element, not a guessed screen region'
    return d.bounds(clocks[0])


def crop_color(d, rect):
    # Android's native raw framebuffer avoids adding an imaging dependency.
    raw = d.adb('exec-out', 'screencap')
    width, height, format_ = struct.unpack_from('<III', raw)
    assert format_==1, ('Expected RGBA_8888', format_)
    offset=len(raw)-width*height*4
    assert offset in (12,16), ('Unknown framebuffer header', offset)
    x,y,r,b = rect
    assert 0<=x<r<=width and 0<=y<b<=height
    pixels=collections.Counter(tuple(raw[offset+(py*width+px)*4:offset+(py*width+px)*4+3])
        for py in range(y,b,3) for px in range(x,r,3))
    assert pixels, 'Empty actual preview text bounds'
    return pixels.most_common(1)[0][0]


def automatic_color_event(d, label):
    # Scroll to the real option first; the real preview remains pinned above it.
    for _ in range(18):
        try:
            control(d,label)
            break
        except AssertionError:
            d.shell('input','swipe',d.width//2,int(d.height*.85),d.width//2,int(d.height*.65),300)
    else:
        raise AssertionError('Color option not visible: '+label)
    rect=preview_rect(d)
    baseline=crop_color(d,rect)
    samples=[]; changed=False; returned=0
    # Sample actual frames until a changed color and two restored frames have
    # been observed. The timeout is a failure bound, not presumed completion.
    tap_control(d,label)
    began=time.monotonic(); deadline=began+6
    while time.monotonic()<deadline:
        rgb=crop_color(d,rect); difference=sum(abs(a-b) for a,b in zip(rgb,baseline))
        samples.append(dict(milliseconds=round((time.monotonic()-began)*1000),rgb=rgb))
        if difference>=24:
            if not changed: d.shot('interaction-color-'+str(len(label))+'-transient')
            changed=True; returned=0
        elif changed and difference<=6:
            returned+=1
            if returned>=2: break
        time.sleep(.06)
    assert changed and returned>=2, ('No observed automatic color event and return',label,baseline,samples)
    return dict(mode=label,baseline=baseline,samples=samples)


def color_after_heading(d, heading, color):
    for _ in range(18):
        tree=d.dump(); headings=[n for n in tree.iter('node') if n.get('text')==heading]
        if headings:
            bottom=d.bounds(headings[0])[3]
            matches=[n for n in tree.iter('node') if n.get('text')==color and d.bounds(n)[1]>=bottom]
            if matches:
                node=min(matches,key=lambda n:d.bounds(n)[1]);x,y,r,b=d.bounds(node)
                d.tapxy((x+r)//2,(y+b)//2);return
        d.shell('input','swipe',d.width//2,int(d.height*.85),d.width//2,int(d.height*.65),300)
    raise AssertionError('Palette color not visible after heading: '+heading+'/'+color)


def color_preview(d):
    setup(d)
    before=ids(d); appearance=pref_values(d)
    d.tap('设置');d.tap('正计时提醒',scroll=True)
    captures=[]
    for label,mode in [('A→B','TRANSITION'),('A/B 交替','ALTERNATING'),('随机颜色','RANDOM')]:
        captures.append(automatic_color_event(d,label))
        saved=json.loads(pref_values(d,'stopwatch_reminders')['rules'])
        assert saved['primary']['color']['mode']==mode
        no_session(d,before)
    # A and B are edited in their own visible section and checked in the actual
    # shared preference snapshot; duplicate palette labels are not ambiguous.
    tap_setting(d,'A→B')
    color_after_heading(d,'颜色 A','红')
    color_after_heading(d,'颜色 B','青')
    saved=json.loads(pref_values(d,'stopwatch_reminders')['rules'])
    assert saved['primary']['color']==dict(mode='TRANSITION',a=0xffdc6378-2**32,b=0xff36b9c5-2**32),saved
    tap_setting(d,'试听提示音')
    assert any('提示音未请求：总许可关闭' in t for t in texts(d))
    tap_setting(d,'测试震动')
    assert any('震动未请求：总许可关闭' in t for t in texts(d))
    tap_setting(d,'允许短提示音');tap_setting(d,'允许周期震动')
    perms=pref_values(d,'stopwatch_reminders')
    assert perms['allow_sound'] and perms['allow_vibration']
    tap_setting(d,'试听提示音')
    assert any(t.startswith(('提示音已请求','提示音未请求','提示音不可用')) for t in texts(d))
    tap_setting(d,'测试震动')
    assert any(t.startswith(('震动已请求','震动不可用','震动未请求','未提交震动','震动调用失败'))
        for t in texts(d)),texts(d)
    no_session(d,before)
    assert pref_values(d)==appearance, 'Preview changed ordinary appearance/opacity'
    d.shot('interaction-preview-permission-feedback')
    (d.output/'interaction-color-samples.json').write_text(json.dumps(captures,ensure_ascii=False,indent=2),encoding='utf-8')
    # Leaving/backgrounding the editor releases local resources; the suite
    # makes no claim that an accepted API request was heard or felt.
    d.shell('input','keyevent','KEYCODE_HOME');home(d)
    no_session(d,before)
    return dict(actualAutoColorFrames=True, actualReturnToBase=True, colorsPersisted=True,
        previewNoSessionOrRecord=True, ordinaryAppearanceUnchanged=True, permissionFeedback=True,
        note='Sound/vibration requests or suppression observed; physical output not verified')


def scaled_preview(d):
    setup(d)
    before=ids(d)
    d.tap('设置');d.tap('正计时提醒',scroll=True)
    for _ in range(16):
        try:
            node=control(d,'A→B');break
        except AssertionError:
            d.shell('input','swipe',d.width//2,int(d.height*.85),d.width//2,int(d.height*.65),300)
    else:raise AssertionError('Color option unavailable')
    x,y,r,b=d.bounds(node);point=((x+r)//2,(y+b)//2)
    rect=preview_rect(d)
    key='animator_duration_scale';old=d.shell('settings','get','global',key).strip()
    results=[]
    try:
        for scale in ('1','2','0'):
            d.shell('settings','put','global',key,scale)
            time.sleep(.2)
            base=crop_color(d,rect);d.shell('logcat','-c')
            d.shell('input','tap',*point)
            began=time.monotonic();samples=[]
            while time.monotonic()-began<2.3:
                samples.append(dict(ms=round((time.monotonic()-began)*1000),rgb=crop_color(d,rect)))
                time.sleep(.04)
            trace=d.shell('logcat','-d','-s','FloatingFocus:*')
            points=[float(p) for p in re.findall(r'Reminder effect kind=color id=\d+ progress=([\d.]+)',trace)]
            active=next((i for i,p in enumerate(points) if p<.9),None)
            assert active is not None and any(p>=.99 for p in points[active+1:]),('Incomplete real renderer progress',scale,points)
            assert any(sum(abs(a-b) for a,b in zip(v['rgb'],base))>=24 for v in samples),('No rendered color change',scale,samples)
            assert sum(abs(a-b) for a,b in zip(samples[-1]['rgb'],base))<=6,('No rendered base restoration',scale,samples)
            results.append(dict(scale=scale,frames=samples,progress=points))
            no_session(d,before)
        # Rapid replay must replace the old cue; a prior expiry cannot clear the
        # final event early. Look for a full trajectory of the final actual ID.
        d.shell('settings','put','global',key,'2');d.shell('logcat','-c')
        for _ in range(3):d.shell('input','tap',*point);time.sleep(.12)
        time.sleep(1.9)
        trace=d.shell('logcat','-d','-s','FloatingFocus:*')
        grouped=collections.defaultdict(list)
        for id_,p in re.findall(r'Reminder effect kind=color id=(\d+) progress=([\d.]+)',trace):grouped[int(id_)].append(float(p))
        assert len(grouped)>=3,('Rapid requests did not generate independent preview cues',grouped)
        points=grouped[max(grouped)];active=next((i for i,p in enumerate(points) if p<.9),None)
        assert active is not None and any(p>=.99 for p in points[active+1:]),('Final replay truncated',points)
        assert crop_color(d,rect)==base
        no_session(d,before)
        (d.output/'interaction-scaled-preview.json').write_text(json.dumps(dict(cases=results,rapid=grouped),indent=2),encoding='utf-8')
        d.shot('interaction-scaled-preview-restored')
        return dict(realFramesAt1x2xDisabled=True,renderProgressCompleted=True,rapidFinalCueCompleted=True,noSession=True)
    finally:
        if old=='null':d.shell('settings','delete','global',key)
        else:d.shell('settings','put','global',key,old)


def interactions(d):
    with screen_environment(d):
        d.shell('cmd','uimode','night','no')
        d.shell('settings','put','system','font_scale','1.0')
        return dict(modeMemory=mode_memory(d),recovery=recovery_selection(d),
            overlayLock=lock_touch_through(d),colorPreview=color_preview(d),scaledPreview=scaled_preview(d),
            limits='Dedicated AOSP emulator only; HyperOS touch policies and actual sound/vibration need physical-device review')


def video_color_event(d,label,tag):
    ffmpeg=shutil.which('ffmpeg')
    assert ffmpeg,'This video-frame assertion requires the existing host ffmpeg; do not install app dependencies'
    for _ in range(18):
        try:node=control(d,label);break
        except AssertionError:d.shell('input','swipe',d.width//2,int(d.height*.85),d.width//2,int(d.height*.65),300)
    else:raise AssertionError('Actual color option unavailable')
    x,y,r,b=d.bounds(node);rect=preview_rect(d)
    baseline_before=crop_color(d,rect)
    remote='/sdcard/ff-interaction-preview.mp4';path=d.output/(tag+'.mp4')
    movie=subprocess.Popen([d.exe,'-s',d.serial,'shell','screenrecord','--time-limit','4','--size','540x1170',remote],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    try:
        time.sleep(.3);d.shell('input','tap',(x+r)//2,(y+b)//2)
        _,error=movie.communicate(timeout=12);assert movie.returncode==0,error
        path.write_bytes(d.adb('exec-out','cat',remote))
    finally:
        if movie.poll() is None:movie.terminate();movie.communicate(timeout=5)
        d.shell('rm','-f',remote)
    l,t,r,b=rect;left,top=int(l/2),int(t/2);width,height=max(2,int((r-l)/2)),max(2,int((b-t)/2))
    decode=subprocess.run([ffmpeg,'-v','error','-i',str(path),'-vf',f'crop={width}:{height}:{left}:{top}:exact=1,fps=12','-f','rawvideo','-pix_fmt','rgb24','pipe:1'],capture_output=True,check=True).stdout
    stride=width*height*3;assert len(decode)>=stride*8 and len(decode)%stride==0,'Too few actual captured frames'
    samples=[]
    for offset in range(0,len(decode),stride):
        raw=decode[offset:offset+stride]
        rgb=collections.Counter(tuple(raw[i:i+3]) for i in range(0,len(raw),9)).most_common(1)[0][0]
        samples.append(rgb)
    # screenrecord emits variable-rate frames; after the UI becomes idle it
    # may store only one restored frame. Confirm that frame against separate
    # real framebuffer reads before and after, not invented duplicate frames.
    baseline_after=crop_color(d,rect)
    assert sum(abs(a-b) for a,b in zip(baseline_before,baseline_after))<=6,'Live preview base changed'
    baseline=baseline_after
    changed=[i for i,rgb in enumerate(samples) if sum(abs(a-b) for a,b in zip(rgb,baseline))>=30]
    assert len(changed)>=2,('No actual recorded color frames',tag,samples)
    assert sum(abs(a-b) for a,b in zip(samples[-1],baseline))<=24,'Recorded preview did not restore base'
    moment=changed[len(changed)//2]/12
    subprocess.run([ffmpeg,'-v','error','-y','-i',str(path),'-ss',str(moment),'-frames:v','1',str(d.output/(tag+'-effect.png'))],capture_output=True,check=True)
    return dict(actualVideo=path.name,decodedFrameColors=samples,restoredBase=True,changedFrame=moment)


def interaction_theme(d):
    with screen_environment(d):
        setup(d)
        baseline=ids(d);appearance=pref_values(d);cases=[]
        for night,font in [('yes','1.0'),('no','2.0')]:
            d.shell('cmd','uimode','night',night);d.shell('settings','put','system','font_scale',font)
            home(d);d.tap('设置');d.tap('正计时提醒',scroll=True)
            tag='interaction-theme-'+night+'-font-'+font
            event=video_color_event(d,'A/B 交替',tag)
            d.shot(tag)
            no_session(d,baseline)
            assert pref_values(d)==appearance,'Theme preview changed real appearance settings'
            cases.append(dict(night=night,font=font,actualFrames=event))
            d.tap('返回');d.tap('返回')
        (d.output/'interaction-theme-cases.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2),encoding='utf-8')
        return dict(darkAndLargeFontFrames=True,noSessionOrRecord=True,appearancePreserved=True,
            note='Actual video frames, not a simulated animation; physical sound/vibration not verified')
