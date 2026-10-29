package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class TimerStartupDefaultsTest {
    @Test fun `first use retains countdown fifteen minute startup`() {
        assertEquals(TimerStartupDefaults(TimerMode.COUNTDOWN, 900), TimerStartupDefaults.decode(null, null))
    }

    @Test fun `startup accepts second precision independently of quick slots`() {
        for (seconds in listOf(1, 59, 65, 10800)) {
            assertEquals(TimerStartupDefaults(TimerMode.STOPWATCH, seconds),
                TimerStartupDefaults.decode("STOPWATCH", seconds))
        }
        assertFalse(QuickDurations.validSeconds(listOf(1, 120, 900, 1800)))
        assertTrue(QuickDurations.validSeconds(listOf(60, 120, 900, 1800)))
    }

    @Test fun `corrupt fields fall back independently without losing valid countdown duration`() {
        assertEquals(TimerStartupDefaults(TimerMode.COUNTDOWN, 65), TimerStartupDefaults.decode("unknown", 65))
        for (seconds in listOf(null, 0, -1, 10801, Int.MAX_VALUE)) {
            assertEquals(TimerStartupDefaults(TimerMode.STOPWATCH, 900),
                TimerStartupDefaults.decode("STOPWATCH", seconds))
        }
    }

    @Test fun `idle fresh home uses configured defaults`() {
        val defaults = TimerStartupDefaults(TimerMode.STOPWATCH, 271)
        assertEquals(defaults, TimerStartupDefaults.initialHomeSelection(defaults, SessionState()))
    }

    @Test fun `active countdown home uses original duration rather than time remaining`() {
        val session = SessionState(active = true, mode = TimerMode.COUNTDOWN, durationMillis = 65_000,
            remainingMillis = 8_000, elapsedMillis = 57_000)
        assertEquals(TimerStartupDefaults(TimerMode.COUNTDOWN, 65),
            TimerStartupDefaults.initialHomeSelection(TimerStartupDefaults(TimerMode.STOPWATCH, 900), session))
    }

    @Test fun `paused and overtime fresh homes retain active countdown configuration`() {
        val session = SessionState(active = true, mode = TimerMode.COUNTDOWN, durationMillis = 123_000,
            remainingMillis = 0, elapsedMillis = 130_000, overtimeMillis = 7_000, stage = ReminderStage.FINISHED)
        for (active in listOf(session, session.copy(paused = true), session.copy(c4Locked = true))) {
            assertEquals(TimerStartupDefaults(TimerMode.COUNTDOWN, 123),
                TimerStartupDefaults.initialHomeSelection(TimerStartupDefaults(), active))
        }
    }

    @Test fun `active stopwatch home preserves saved countdown value for a later mode switch`() {
        val defaults = TimerStartupDefaults(TimerMode.COUNTDOWN, 271)
        for (paused in listOf(false, true)) {
            assertEquals(TimerStartupDefaults(TimerMode.STOPWATCH, 271),
                TimerStartupDefaults.initialHomeSelection(defaults,
                    SessionState(active = true, mode = TimerMode.STOPWATCH, paused = paused)))
        }
    }

    @Test fun `restored home choices keep exact mode and seconds`() {
        val restored = TimerStartupDefaults.restoreSelection(listOf("COUNTDOWN", 73))!!
        assertEquals(TimerMode.COUNTDOWN, restored.mode)
        assertEquals(73, restored.durationSeconds)
    }

    @Test fun `older or invalid saved state can reinitialize without a cast failure`() {
        for (value in listOf(null, TimerMode.COUNTDOWN, 900, "COUNTDOWN", emptyList<Any>())) {
            assertNull(TimerStartupDefaults.restoreSelection(value))
        }
        assertEquals(TimerStartupDefaults(), TimerStartupDefaults.restoreSelection(listOf("bad", -1)))
    }
}
