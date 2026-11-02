package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class C4OvertimeControlsTest {
    @Test fun `only before zero requires the code and zero unlocks normal end`() {
        for (phase in listOf(C4Phase.PLANTING, C4Phase.ARMED, C4Phase.DEFUSED))
            assertTrue(C4Frame(phase).requiresCode)
        for (phase in listOf(C4Phase.EXPLODING, C4Phase.OVERTIME)) {
            val frame = C4Frame(phase, overtimeMillis = 5000)
            assertTrue(frame.active)
            assertTrue(frame.expired)
            assertFalse(frame.inputEnabled)
            assertFalse(frame.requiresCode)
        }
    }

    @Test fun `C4 at and after deadline can end but can never extend pause or resume`() {
        val defaults = TimerStartupDefaults()
        for (over in listOf(0L, 1L, 5902L, 3_600_000L)) {
            val s = SessionState(active = true, c4Active = true, remainingMillis = 0,
                overtimeMillis = over, stage = ReminderStage.FINISHED,
                startRequestId = "c4", timerStartedAt = 1000)
            assertTrue(SessionControls.canEnd(s))
            assertFalse(SessionControls.canExtend(s))
            assertFalse(SessionControls.canPause(s))
            assertTrue(TimerWidgetModel.accepts(s, WidgetCommand.END, "c4", 1000))
            for (c in listOf(WidgetCommand.PAUSE, WidgetCommand.RESUME))
                assertFalse(TimerWidgetModel.accepts(s, c, "c4", 1000))
            val p = TimerWidgetModel.present(s, defaults, 20_000)
            assertTrue(p.canEnd)
            assertEquals(WidgetCommand.OPEN, p.primary)
            assertEquals("C4 超时", p.status)
        }
    }

    @Test fun `ordinary overtime retains extension and stale C4 controls cannot end another session`() {
        val s = SessionState(active = true, stage = ReminderStage.FINISHED, startRequestId = "new", timerStartedAt = 2000)
        assertTrue(SessionControls.canExtend(s))
        assertTrue(SessionControls.canEnd(s))
        assertFalse(TimerWidgetModel.accepts(s, WidgetCommand.END, "c4", 1000))
        assertFalse(SessionControls.canExtend(s.copy(c4Locked = true)))
    }
}
