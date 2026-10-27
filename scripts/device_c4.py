"""Dedicated emulator C4 regression; original data are restored by device_regression."""
import json
import re
import time
import xml.etree.ElementTree as ET

PACKAGE = 'app.floatingfocus'


def c4(d):
    def settings():
        tree = ET.fromstring(d.adb('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/c4_settings.xml'))
        return {n.get('name'): n.get('value') == 'true' for n in tree}

    def logs():
        return d.shell('logcat', '-d', '-s', 'FloatingFocus:*')

    def start(seconds, enabled=True, sound=True, mode='COUNTDOWN', shape='ROUNDED_RECTANGLE'):
        d.stop()
        appearance = dict(shape=shape, color='BLUE', style='COLOR', baseSize=1.0,
                          opacity=.7, intensity=1.0, colorEnabled=True, sizeEnabled=False)
        preset = dict(id='c4-test', name='C4 '+str(seconds), minutes=max(1, seconds//60), durationSeconds=seconds,
                      settings=appearance, mode=mode, targetPackage='com.android.gallery3d', targetLabel='Gallery')
        d.write_prefs('c4_settings', dict(enabled=enabled, sound=sound))
        d.write_prefs('reminder_settings', appearance)
        d.write_prefs('timer_presets', {'items': json.dumps([preset])})
        d.write_prefs('quick_launch', {'targets': json.dumps([dict(package='com.android.gallery3d', label='Gallery')])})
        d.start()
        d.shell('logcat', '-c')
        d.tap('启动预设 C4 '+str(seconds), scroll=True)
        deadline = time.monotonic()+8
        while time.monotonic() < deadline:
            if d.overlay() and 'com.android.gallery3d' in d.foreground() and abs(d.frame()[2]-(d.width-round(16*d.density))) <= 2:
                break
            time.sleep(.1)
        assert d.overlay() and 'com.android.gallery3d' in d.foreground()
        # No C4 fields may enter a preset snapshot.
        assert 'c4' not in preset['settings']
        return d.overlay()[0], d.frame()

    def end():
        d.start()
        d.tap('结束计时')
        d.wait_stopped()

    def completed(seconds, enabled=True, sound=True):
        wid, initial = start(seconds, enabled, sound)
        active_frame = None
        deadline = time.monotonic()+seconds+8
        while time.monotonic() < deadline:
            text = logs()
            if active_frame is None and 'C4 active=true' in text:
                active_frame = d.frame()
                d.shot('c4-'+str(seconds)+'-'+('sound' if sound else 'muted'))
            if 'Timer completed:' in text:
                break
            time.sleep(.15)
        else:
            raise AssertionError('No real completion for '+str(seconds))
        text = logs()
        (d.output / ('c4-'+str(seconds)+'-'+str(enabled)+'-'+str(sound)+'.log')).write_text(text, encoding='utf-8')
        assert text.count('Timer completed:') == 1
        assert d.overlay()[0] == wid and 'com.android.gallery3d' in d.foreground()
        if enabled:
            entry = int(re.search(r'C4 active=true remaining=(\d+)', text)[1])
            assert min(seconds*1000, 40000)-1500 <= entry <= min(seconds*1000, 40000), entry
            assert active_frame is not None
            if seconds > 40:
                assert active_frame[2]-active_frame[0] == initial[2]-initial[0]
                assert active_frame[3]-active_frame[1] == initial[3]-initial[1]
                assert max(abs(a-b) for a,b in zip(initial, active_frame)) <= 1
            cues = [line for line in text.splitlines() if 'C4 cue=' in line]
            assert cues and all('led=true' in line for line in cues)
            assert any('tone=FINAL' in line for line in cues)
            if seconds == 10:
                assert all('tone=FINAL' in line for line in cues)
            if sound:
                assert 'played=true' in text, 'SoundPool never accepted playback; inspect device policy/focus'
                assert text.count('C4 explosion played=true') == 1, text[-1800:]
            else:
                assert not any('played=true' in line for line in cues)
                assert 'C4 explosion' not in text
            assert 'C4 active=false remaining=0' in text
        else:
            assert 'C4 cue=' not in text and 'C4 explosion' not in text
        time.sleep(.4)
        d.shot('c4-expired-'+str(seconds)+'-'+str(sound))
        end()
        assert d.records()[-1]['plannedMillis'] == seconds*1000
        return dict(seconds=seconds, enabled=enabled, sound=sound, sameWindow=True, entryFrame=active_frame,
                    audioEvidence='SoundPool request logs only; no physical speaker/latency assertion')

    # Check real defaults, dependent sound switch and persistence through the public UI.
    d.stop()
    d.write_prefs('c4_settings', {})
    d.start()
    d.tap('设置'); d.tap('提醒')
    tree = d.dump()
    assert any(n.get('text') == 'C4 倒计时' for n in tree.iter('node'))
    assert not any(n.get('text') == '音效' for n in tree.iter('node'))
    d.tap('C4 倒计时')
    assert settings() == dict(enabled=True, sound=True)
    d.tap('音效')
    assert settings() == dict(enabled=True, sound=False)
    d.shot('c4-settings')
    d.stop(); d.start(); d.tap('设置'); d.tap('提醒')
    assert settings() == dict(enabled=True, sound=False)
    d.tap('音效')
    assert settings() == dict(enabled=True, sound=True)

    results = [completed(seconds) for seconds in (60, 40, 30, 10)]
    # Actual pause/resume in the same overlay, followed by cancellation before expiry.
    wid, _ = start(40)
    d.tapxy(*d.center())
    time.sleep(.5)
    d.overlay_tap('暂停计时', .23, .64)
    text = logs()
    assert 'C4 clock paused=true' in text
    before = text.count('C4 cue=')
    d.shot('c4-paused')
    time.sleep(2.5)
    assert logs().count('C4 cue=') == before and 'Timer completed:' not in logs()
    d.overlay_tap('继续计时', .23, .64)
    assert 'C4 clock paused=false' in logs()
    assert d.overlay()[0] == wid
    end()
    after = logs().count('C4 cue=')
    time.sleep(.5)
    assert logs().count('C4 cue=') == after and 'C4 explosion' not in logs()
    results.append(dict(pauseResumeCancel=True, sameWindow=True))
    results.append(completed(10, sound=False))
    results.append(completed(10, enabled=False))
    start(10, mode='STOPWATCH')
    time.sleep(1.5)
    assert 'C4 cue=' not in logs() and 'Timer completed:' not in logs()
    end()
    shapes = []
    for shape in ('ROUNDED_RECTANGLE', 'VERTICAL_CAPSULE', 'CIRCLE'):
        wid, initial = start(30, sound=False, shape=shape)
        d.shot('c4-shape-'+shape)
        x, y = d.center()
        d.shell('input', 'swipe', x, y, d.width//3, d.height//2, 450)
        time.sleep(.3)
        assert d.overlay()[0] == wid and d.frame() != initial
        d.tapxy(*d.center())
        time.sleep(.3)
        d.shot('c4-expanded-'+shape)
        assert d.overlay()[0] == wid
        # This dragged/expanded fixture can cover the Activity's stop button.
        # End through its own middle control instead of tapping through the overlay.
        d.overlay_tap('结束计时', .5, .60)
        d.wait_stopped()
        assert 'C4 explosion' not in logs()
        shapes.append(shape)
    return dict(settingsPersisted=True, defaultsOffSoundOn=True, scenarios=results, stopwatchExcluded=True,
                shapesAndDrag=shapes, genuineCS2Timing='not verified; configurable approximation', physicalAudio='not tested')
