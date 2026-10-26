package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class StrategyEditingTest {
    private val mixed = ReminderStrategy(TriggerMode.MIXED, ReminderNode(50, 60), ReminderNode(80, 20, false, true))

    @Test fun `validate actual duration before every countdown leg`() {
        assertNotNull(mixed.validationError(30_000)) // 15s vs 10s
        assertNotNull(mixed.validationError(40_000)) // simultaneous is also invalid
        assertNull(mixed.validationError(60_000))
        assertNotNull(mixed.validationError(10_000)) // remaining exceeds total
        assertNull(mixed.validationError(0, TimerMode.STOPWATCH))
        assertNull(ReminderStrategy().validationError(30_000))
    }

    @Test fun `positions follow duration without changing configured seconds`() {
        assertEquals(1.0 / 3, mixed.position(mixed.urgent, 30_000), .00001)
        assertEquals(2.0 / 3, mixed.position(mixed.urgent, 60_000), .00001)
        assertEquals(20, mixed.urgent.seconds)
    }

    @Test fun `equal mixed triggers are rejected without floating point cancellation`() {
        for (percent in 0..99) {
            val progress = ReminderNode(percent, 1, true, false)
            val remaining = ReminderNode(0, 100 - percent, false, true)
            assertNotNull(ReminderStrategy(TriggerMode.MIXED, progress, remaining).validationError(100_000))
            assertNotNull(ReminderStrategy(TriggerMode.MIXED, remaining, progress).validationError(100_000))
        }
        assertNull(ReminderStrategy(TriggerMode.MIXED, ReminderNode(57, 1),
            ReminderNode(0, 42, false, true)).validationError(100_000))
    }

    @Test fun `drag respects node identity type ordering and rounding`() {
        assertEquals(mixed, mixed.moved(true, .9, 60_000))
        assertEquals(mixed, mixed.moved(false, .2, 60_000))
        val changed = mixed.moved(false, .75, 60_000)
        assertEquals(15, changed.urgent.seconds)
        assertFalse(changed.urgent.useProgress)
        assertTrue(changed.urgent.useRemaining)
        assertEquals(40, mixed.moved(true, .4, 60_000).warm.percent)
        assertNull(changed.validationError(60_000))
    }

    @Test fun `legacy single modes preserve semantics ambiguous mixed nodes require user choice`() {
        val remaining = ReminderStrategy(TriggerMode.REMAINING)
        assertFalse(remaining.singleNode(remaining.warm).useProgress)
        assertTrue(remaining.singleNode(remaining.warm).useRemaining)
        assertNotNull(mixed.copy(warm = mixed.warm.copy(useRemaining = true)).validationError(900_000))
        assertNotNull(mixed.copy(warm = mixed.warm.copy(useProgress = false)).validationError(900_000))
        assertEquals(mixed, ReminderSettingsCodec.decode(ReminderSettingsCodec.encode(ReminderSettings(strategy = mixed))).strategy)
    }

    @Test fun `second precision survives presets including edits and legacy migration`() {
        val preset = TimerPreset("seconds", "seconds", 1, ReminderSettings(), durationSeconds = 95)
        assertEquals(preset, TimerPresetCodec.decode(TimerPresetCodec.encode(preset)))
        assertEquals(95, preset.copy(name = "renamed").durationSeconds)
        val old = TimerPresetCodec.encode(preset) - "durationSeconds"
        assertEquals(60, TimerPresetCodec.decode(old)!!.durationSeconds)
        assertEquals(listOf(60, 120, 180, 240), QuickDurations.decodeSeconds(null, "1,2,3,4"))
        assertEquals(listOf(65, 120, 195, 10800), QuickDurations.decodeSeconds("65,120,195,10800", "1,2,3,4"))
        assertEquals(QuickDurations.defaultSeconds, QuickDurations.decodeSeconds("65,x,120,195,10800", null))
        assertFalse(QuickDurations.validSeconds(listOf(59, 120, 180, 240)))
    }
}
