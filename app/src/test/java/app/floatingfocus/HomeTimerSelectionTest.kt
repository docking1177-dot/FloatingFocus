package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class HomeTimerSelectionTest {
    @Test fun `missing or corrupt home memory retains legacy shortcut mode`() {
        val defaults = TimerStartupDefaults(TimerMode.STOPWATCH, 271)
        for (raw in listOf(null, "unknown", 1, true)) {
            assertNull(HomeTimerMode.decode(raw))
            assertEquals(TimerStartupDefaults(TimerMode.STOPWATCH, 271),
                TimerStartupDefaults.initialHomeSelection(defaults, SessionState(), HomeTimerMode.decode(raw)))
        }
    }

    @Test fun `remembered home mode overrides shortcut mode and retains default countdown seconds`() {
        assertEquals(TimerMode.COUNTDOWN, HomeTimerMode.decode("COUNTDOWN"))
        assertEquals(TimerMode.STOPWATCH, HomeTimerMode.decode("STOPWATCH"))
        val shortcutDefaults = TimerStartupDefaults(TimerMode.COUNTDOWN, 73)
        assertEquals(TimerStartupDefaults(TimerMode.STOPWATCH, 73),
            TimerStartupDefaults.initialHomeSelection(shortcutDefaults, SessionState(), TimerMode.STOPWATCH))
        // Widget/tile callers continue to consume the original independent defaults.
        assertEquals(TimerStartupDefaults(TimerMode.COUNTDOWN, 73), shortcutDefaults)
    }

    @Test fun `restored page selection wins over remembered mode and new defaults`() {
        assertEquals(TimerStartupDefaults(TimerMode.COUNTDOWN, 65),
            TimerStartupDefaults.initialHomeSelection(TimerStartupDefaults(TimerMode.STOPWATCH, 900),
                SessionState(), TimerMode.STOPWATCH, TimerStartupDefaults(TimerMode.COUNTDOWN, 65)))
    }

    @Test fun `active countdown wins over restored page mode and uses original duration`() {
        val restored = TimerStartupDefaults(TimerMode.STOPWATCH, 900)
        val session = SessionState(active = true, mode = TimerMode.COUNTDOWN, durationMillis = 65_000,
            remainingMillis = 8_000, elapsedMillis = 57_000)
        assertEquals(TimerStartupDefaults(TimerMode.COUNTDOWN, 65),
            TimerStartupDefaults.initialHomeSelection(TimerStartupDefaults(), session, TimerMode.STOPWATCH, restored))
    }

    @Test fun `active stopwatch retains restored countdown seconds without changing home memory`() {
        val restored = TimerStartupDefaults(TimerMode.COUNTDOWN, 65)
        assertEquals(TimerStartupDefaults(TimerMode.STOPWATCH, 65),
            TimerStartupDefaults.initialHomeSelection(TimerStartupDefaults(),
                SessionState(active = true, mode = TimerMode.STOPWATCH), TimerMode.COUNTDOWN, restored))
        assertEquals(TimerStartupDefaults(TimerMode.COUNTDOWN, 65),
            TimerStartupDefaults.initialHomeSelection(TimerStartupDefaults(), SessionState(), TimerMode.COUNTDOWN, restored))
    }
}
