package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class TimerWidgetTest {
    private val defaults = TimerStartupDefaults(durationSeconds = 125)
    private fun live() = SessionState(active = true, remainingMillis = 60_000, elapsedMillis = 5000,
        durationMillis = 65_000, timerStartedAt = 1000, startRequestId = "current", sampledAt = 6000)

    @Test fun `the minimum declared 2 by 2 size still contains presets and end controls`() {
        assertEquals(WidgetLayout.SHORT_PRESETS, TimerWidgetModel.layoutForHeight(110, 1f))
        assertEquals(WidgetLayout.COMPACT, TimerWidgetModel.layoutForHeight(109, 1f))
        assertEquals(WidgetLayout.TALL_PRESETS, TimerWidgetModel.layoutForHeight(156, 1f))
        assertEquals(WidgetLayout.SHORT_PRESETS, TimerWidgetModel.layoutForHeight(156, 1.5f))
        assertEquals(WidgetLayout.TALL_PRESETS, TimerWidgetModel.layoutForHeight(180, 1.5f))
    }

    @Test fun `idle uses saved startup defaults without running a clock`() {
        val p = TimerWidgetModel.present(SessionState(), defaults, 20_000)
        assertEquals("02:05", p.time)
        assertFalse(p.running)
        assertEquals(WidgetCommand.START, p.primary)
        assertEquals("00:00", TimerWidgetModel.present(SessionState(), defaults.copy(mode = TimerMode.STOPWATCH), 20_000).time)
    }

    @Test fun `countdown bases use monotonic sample not delayed widget render time`() {
        val p = TimerWidgetModel.present(live(), defaults, 8500)
        assertEquals(66_000L, p.base)
        assertEquals("00:58", p.time)
        assertTrue(p.countDown && p.running)
        assertEquals(WidgetCommand.PAUSE, p.primary)
    }

    @Test fun `pause freezes display and resume uses an explicit command`() {
        val p = TimerWidgetModel.present(live().copy(paused = true), defaults, 90_000)
        assertEquals("01:00", p.time)
        assertFalse(p.running)
        assertEquals(WidgetCommand.RESUME, p.primary)
    }

    @Test fun `stopwatch and overtime count upwards without double counting`() {
        val up = TimerWidgetModel.present(live().copy(mode = TimerMode.STOPWATCH), defaults, 8500)
        assertEquals(1000L, up.base)
        assertEquals("00:07", up.time)
        assertFalse(up.countDown)
        val over = TimerWidgetModel.present(live().copy(remainingMillis = 0, overtimeMillis = 2000, stage = ReminderStage.FINISHED), defaults, 8500)
        assertEquals(4000L, over.base)
        assertEquals("+00:04", over.time)
        assertEquals(WidgetCommand.OPEN, over.primary)
    }

    @Test fun `C4 controls cannot pause end or replace a session`() {
        val s = live().copy(c4Locked = true)
        val p = TimerWidgetModel.present(s, defaults, 6000)
        assertEquals(WidgetCommand.OPEN, p.primary)
        assertFalse(p.canEnd)
        for (c in listOf(WidgetCommand.PAUSE, WidgetCommand.RESUME, WidgetCommand.END))
            assertFalse(TimerWidgetModel.accepts(s, c, "current", 1000))
    }

    @Test fun `duplicate stale and post-extension controls are rejected`() {
        val s = live()
        assertTrue(TimerWidgetModel.accepts(s, WidgetCommand.PAUSE, "current", 1000))
        assertFalse(TimerWidgetModel.accepts(s.copy(paused = true), WidgetCommand.PAUSE, "current", 1000))
        assertTrue(TimerWidgetModel.accepts(s.copy(paused = true), WidgetCommand.RESUME, "current", 1000))
        assertFalse(TimerWidgetModel.accepts(s, WidgetCommand.END, "previous", 1000))
        assertFalse(TimerWidgetModel.accepts(s, WidgetCommand.END, "current", 999))
        assertFalse(TimerWidgetModel.accepts(SessionState(), WidgetCommand.END, "current", 1000))
        assertFalse(TimerWidgetModel.accepts(s.copy(automatic = true), WidgetCommand.PAUSE, "current", 1000))
        assertFalse(TimerWidgetModel.accepts(s, WidgetCommand.RESUME, "current", 1000))
    }
}
