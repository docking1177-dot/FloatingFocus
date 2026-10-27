package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class C4TimelineTest {
    @Test fun `sixty second timer crosses boundary without resetting the clock`() {
        val timer = Countdown(60_000, 1000)
        val effect = C4EffectState()
        assertFalse(effect.update(1000, timer.mode, timer.remainingAt(20_999), true, false).frame.active)
        val first = effect.update(1000, timer.mode, timer.remainingAt(21_000), true, false)
        assertTrue(first.frame.active); assertTrue(first.frame.led); assertEquals(0, first.cue!!.index)
        assertEquals(40_000, timer.remainingAt(21_000))
    }
    @Test fun `forty thirty and ten second starts share one absolute timeline`() {
        val timeline = C4Timeline()
        for (remaining in listOf(40_000L, 30_000L, 10_000L)) {
            val cursor = C4Cursor(timeline)
            val first = cursor.sample(remaining)
            assertTrue(first.frame.active)
            assertEquals(remaining <= 10_000, first.frame.finalPhase)
            first.cue?.let { assertTrue(it.remainingMillis - remaining <= timeline.profile.maxLateMillis) }
            if (remaining < 40_000) assertTrue(timeline.indexAt(remaining) > 0)
            var left = remaining
            val sounds = mutableListOf<C4Cue>()
            while (left > 0) {
                val sample = cursor.sample(left)
                sample.cue?.let { sounds.add(it); assertTrue(sample.frame.led) }
                left -= sample.nextDelayMillis ?: break
            }
            assertTrue(sounds.map { it.index }.distinct().size == sounds.size)
            assertTrue(sounds.filter { it.remainingMillis <= 10_000 }.all { it.tone == C4Tone.FINAL })
            assertFalse(cursor.sample(0).frame.active)
        }
    }
    @Test fun `continuous acceleration is not a three fixed rate approximation`() {
        val profile = C4Profile()
        val intervals = (0L..40_000 step 1000L).map(profile::interval)
        assertTrue(intervals.distinct().size > 30)
        assertEquals(1000L, profile.interval(40_000))
        assertEquals(325L, profile.interval(10_000))
        assertEquals(150L, profile.interval(0))
        assertTrue(C4Timeline().cues.zipWithNext().all { (a, b) -> a.remainingMillis - b.remainingMillis >= 150 })
    }
    @Test fun `measured replacement timeline supports its own offsets and pulse boundaries`() {
        val t = C4Timeline(C4Profile(measuredRemainingMillis = listOf(40_000, 30_000, 10_000, 1000)))
        val c = C4Cursor(t)
        assertEquals(C4Tone.NORMAL, c.sample(30_000).cue!!.tone)
        assertTrue(c.sample(29_921).frame.led)
        assertFalse(c.sample(29_920).frame.led)
        assertEquals(C4Tone.FINAL, c.sample(10_000).cue!!.tone)
        assertTrue(c.sample(9881).frame.led)
        assertFalse(c.sample(9880).frame.led)
    }
    @Test fun `late callbacks skip missed cues and never burst or duplicate`() {
        val c = C4Cursor()
        val first = c.sample(40_000).cue!!
        assertNull(c.sample(40_000).cue)
        assertNull(c.sample(38_800).cue) // 200ms after the second cue, beyond permitted latency
        val current = c.sample(10_000)
        assertTrue(current.cue == null || current.cue.index > first.index)
        assertNull(c.sample(10_000).cue)
    }
    @Test fun `pause freezes countdown and led then resumes phase without replay`() {
        val clock = SessionClock()
        val timer = Countdown(40_000, 1000)
        val cursor = C4Cursor()
        assertNotNull(cursor.sample(timer.remainingAt(clock.at(1000))).cue)
        clock.pause(1040)
        val frozen = timer.remainingAt(clock.at(50_000))
        assertEquals(39_960, frozen)
        assertTrue(cursor.sample(frozen, true).frame.led)
        assertNull(cursor.sample(frozen, true).nextDelayMillis)
        clock.pause(20_000) // duplicate pause cannot move the anchor
        clock.resume(51_000)
        assertEquals(frozen, timer.remainingAt(clock.at(51_000)))
        assertNull(cursor.sample(frozen).cue)
        assertFalse(cursor.sample(timer.remainingAt(clock.at(51_040))).frame.led)
        assertEquals(1000, timer.startedAt)
    }
    @Test fun `mute disabling cancel stopwatch and repeated completion never replay explosion`() {
        val state = C4EffectState()
        state.update(1, TimerMode.COUNTDOWN, 10_000, true, false)
        assertFalse(state.complete(false)); assertFalse(state.complete(true))
        state.update(2, TimerMode.COUNTDOWN, 10_000, true, false)
        state.cancel(); assertFalse(state.complete(true))
        state.update(3, TimerMode.STOPWATCH, 0, true, false)
        assertFalse(state.complete(true))
        state.update(4, TimerMode.COUNTDOWN, 10_000, true, false)
        state.update(4, TimerMode.COUNTDOWN, 5000, false, false)
        assertFalse(state.complete(true))
        state.update(5, TimerMode.COUNTDOWN, 10_000, true, false)
        assertTrue(state.complete(true)); assertFalse(state.complete(true))
        // Re-enabling after completion cannot arm a past window.
        state.update(5, TimerMode.COUNTDOWN, 0, true, false)
        assertFalse(state.complete(true))
    }
    @Test fun `audio loading may consume only a current cue within allowed latency`() {
        val c = C4Cursor()
        assertNull(c.sample(40_000, consumeCues = false).cue)
        assertNotNull(c.sample(39_980).cue)
        assertNull(c.sample(39_980).cue)
        val slow = C4Cursor()
        slow.sample(40_000, consumeCues = false)
        assertNull(slow.sample(39_800).cue)
    }
    @Test fun `late accepted audio and led start together and remain on for the clip duration`() {
        val cursor = C4Cursor()
        val late = cursor.sample(39_950)
        assertNotNull(late.cue); assertTrue(late.frame.led)
        assertTrue(cursor.sample(39_871).frame.led)
        assertFalse(cursor.sample(39_870).frame.led)
        // At very short intervals, skip a late clip that would overrun the next cue.
        val t = C4Timeline(C4Profile(measuredRemainingMillis = listOf(300L, 150L)))
        assertNull(C4Cursor(t).sample(250).cue)
    }
    @Test fun `short presets preserve existing storage structure without c4 fields`() {
        for (seconds in listOf(1, 10, 30, 40, 60)) {
            val preset = TimerPreset("short", "short", 1, ReminderSettings(), durationSeconds = seconds)
            val encoded = TimerPresetCodec.encode(preset)
            assertEquals(preset, TimerPresetCodec.decode(encoded))
            assertFalse(encoded.keys.any { it.contains("c4", true) })
            assertFalse(ReminderSettingsCodec.encode(preset.settings).keys.any { it.contains("c4", true) })
        }
    }
}
