package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class C4ModeTest {
    @Test fun `enabled C4 is countdown only regardless of sound preference`() {
        for (sound in listOf(false, true)) {
            val c4 = C4Settings(enabled = true, sound = sound)
            assertTrue(c4.appliesTo(TimerMode.COUNTDOWN))
            assertFalse(c4.appliesTo(TimerMode.STOPWATCH))
        }
    }

    @Test fun `disabled C4 cannot affect either timer mode`() {
        for (mode in TimerMode.entries) assertFalse(C4Settings(enabled = false).appliesTo(mode))
    }
}
