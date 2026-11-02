package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class C4SessionControlsTest {
    private val beforeStage = SessionState(active = true, remainingMillis = 120_000,
        durationMillis = 120_000, timerStartedAt = 1000, sampledAt = 1000,
        startRequestId = "c4-session", c4Enabled = true)

    @Test fun `C4 snapshot forbids pause before the device phase without forbidding end`() {
        assertFalse(beforeStage.c4Active)
        assertFalse(beforeStage.c4Locked)
        assertFalse(SessionControls.canPause(beforeStage))
        assertFalse(SessionControls.canResume(beforeStage.copy(paused = true)))
        assertTrue(SessionControls.canEnd(beforeStage))
    }

    @Test fun `tile and widget open controls instead of pausing a pre-stage C4 session`() {
        val defaults = TimerStartupDefaults()
        for (state in listOf(beforeStage, beforeStage.copy(paused = true))) {
            assertEquals(WidgetCommand.OPEN, TimerTileModel.present(state, defaults, 1000).command)
            assertEquals(WidgetCommand.OPEN, TimerWidgetModel.present(state, defaults, 1000).primary)
            for (command in listOf(WidgetCommand.PAUSE, WidgetCommand.RESUME)) {
                assertFalse(TimerTileModel.accepts(state, command, "c4-session", 1000))
                assertFalse(TimerWidgetModel.accepts(state, command, "c4-session", 1000))
            }
        }
    }

    @Test fun `ordinary sessions retain pause resume independently of C4 phase and global settings`() {
        val ordinary = beforeStage.copy(c4Enabled = false)
        assertTrue(SessionControls.canPause(ordinary))
        assertTrue(SessionControls.canResume(ordinary.copy(paused = true)))
        assertEquals(WidgetCommand.PAUSE, TimerWidgetModel.present(ordinary, TimerStartupDefaults(), 1000).primary)
        assertEquals(WidgetCommand.RESUME, TimerTileModel.present(ordinary.copy(paused = true), TimerStartupDefaults(), 1000).command)
    }
}
