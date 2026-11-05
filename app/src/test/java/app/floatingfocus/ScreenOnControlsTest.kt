package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class ScreenOnControlsTest {
    private fun session(paused: Boolean = false) = SessionState(active = true, paused = paused,
        mode = TimerMode.STOPWATCH, screenDriven = true, startRequestId = "screen-session", timerStartedAt = 50,
        elapsedMillis = 2000, sampledAt = 2050)

    @Test fun screenOwnsPauseWhileUserCanStillEnd() {
        for (paused in listOf(false, true)) {
            val s = session(paused)
            assertFalse(SessionControls.canPause(s))
            assertFalse(SessionControls.canResume(s))
            assertTrue(SessionControls.canEnd(s))
            assertFalse(SessionControls.canExtend(s))
        }
    }

    @Test fun staleAndEvenCurrentManualPauseRequestsAreRejected() {
        for (paused in listOf(false, true)) {
            val s = session(paused)
            assertFalse(TimerWidgetModel.accepts(s, WidgetCommand.PAUSE, "screen-session", 50))
            assertFalse(TimerWidgetModel.accepts(s, WidgetCommand.RESUME, "screen-session", 50))
            assertTrue(TimerWidgetModel.accepts(s, WidgetCommand.END, "screen-session", 50))
            assertFalse(TimerWidgetModel.accepts(s, WidgetCommand.END, "old-session", 50))
        }
    }

    @Test fun oldUnboundStopCannotEndANewScreenSession() {
        val s = session()
        assertFalse(SessionControls.acceptsStop(s, null, -1))
        assertFalse(SessionControls.acceptsStop(s, "previous-session", 50))
        assertFalse(SessionControls.acceptsStop(s, "screen-session", 49))
        assertTrue(SessionControls.acceptsStop(s, "screen-session", 50))
        assertTrue(SessionControls.acceptsStop(s.copy(screenDriven = false), null, -1))
    }

    @Test fun widgetAndTileOfferViewInsteadOfConflictingPauseOrResume() {
        val defaults = TimerStartupDefaults()
        for (paused in listOf(false, true)) {
            assertEquals(WidgetCommand.OPEN, TimerWidgetModel.present(session(paused), defaults, 3050).primary)
            assertEquals(WidgetCommand.OPEN, TimerTileModel.present(session(paused), defaults, 3050).command)
        }
    }

    @Test fun pausedScreenClockIsFrozenAcrossDevicePresentationUpdates() {
        val clock = TimerWidgetModel.present(session(true), TimerStartupDefaults(), 105_000)
        assertEquals("00:02", clock.time)
        assertFalse(clock.running)
        val running = TimerWidgetModel.present(session(), TimerStartupDefaults(), 3050)
        assertEquals("00:03", running.time)
    }

    @Test fun ordinaryStopwatchPauseAndResumeRemainAvailable() {
        assertTrue(SessionControls.canPause(session().copy(screenDriven = false)))
        assertTrue(SessionControls.canResume(session(true).copy(screenDriven = false)))
    }

    @Test fun activeScreenStopwatchRestoresItsActualHomeMode() {
        val defaults = TimerStartupDefaults(TimerMode.COUNTDOWN, 600)
        assertEquals(defaults.copy(mode=TimerMode.STOPWATCH), TimerStartupDefaults.initialHomeSelection(defaults, session()))
    }
}
