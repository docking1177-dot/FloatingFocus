package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class TimerTileModelTest {
    private val defaults = TimerStartupDefaults(durationSeconds = 900)
    private val live = SessionState(active = true, remainingMillis = 60_000, durationMillis = 60_000,
        timerStartedAt = 1000, sampledAt = 1000, startRequestId = "one")

    @Test fun `idle uses saved defaults and busy state never starts another request`() {
        val idle = TimerTileModel.present(SessionState(), defaults, 1000)
        assertEquals(WidgetCommand.START, idle.command)
        assertEquals(TimerTileState.INACTIVE, idle.state)
        assertTrue(idle.subtitle.contains("15:00"))
        assertEquals(TimerTileState.UNAVAILABLE, TimerTileModel.present(SessionState(), defaults, 1000, busy = true).state)
        assertFalse(TimerTileModel.accepts(SessionState(), WidgetCommand.START, null, 0, busy = true))
        assertTrue(TimerTileModel.accepts(SessionState(), WidgetCommand.START, null, 0))
    }

    @Test fun `countdown and stopwatch toggle pause resume but never end from the tile`() {
        for (mode in TimerMode.entries) {
            val s = live.copy(mode = mode)
            assertEquals(WidgetCommand.PAUSE, TimerTileModel.present(s, defaults, 1000).command)
            assertEquals(WidgetCommand.RESUME, TimerTileModel.present(s.copy(paused = true), defaults, 1000).command)
            assertFalse(TimerTileModel.accepts(s, WidgetCommand.END, "one", 1000))
        }
    }

    @Test fun `overtime and all C4 phases open FF without bypassing controls`() {
        val over = live.copy(remainingMillis = 0, stage = ReminderStage.FINISHED, overtimeMillis = 5000)
        for (s in listOf(over, live.copy(c4Active = true, c4Locked = true), over.copy(c4Active = true))) {
            assertEquals(WidgetCommand.OPEN, TimerTileModel.present(s, defaults, 1000).command)
            assertEquals(TimerTileState.ACTIVE, TimerTileModel.present(s, defaults, 1000).state)
        }
    }

    @Test fun `unlock delay or repeated tap cannot pause the next session or resume twice`() {
        assertTrue(TimerTileModel.accepts(live, WidgetCommand.PAUSE, "one", 1000))
        assertFalse(TimerTileModel.accepts(live.copy(paused = true), WidgetCommand.PAUSE, "one", 1000))
        assertFalse(TimerTileModel.accepts(live.copy(startRequestId = "two"), WidgetCommand.PAUSE, "one", 1000))
        assertFalse(TimerTileModel.accepts(live.copy(timerStartedAt = 2000), WidgetCommand.PAUSE, "one", 1000))
        assertFalse(TimerTileModel.accepts(live.copy(c4Active = true), WidgetCommand.PAUSE, "one", 1000))
        assertFalse(TimerTileModel.accepts(live, WidgetCommand.START, null, 0))
    }
}
