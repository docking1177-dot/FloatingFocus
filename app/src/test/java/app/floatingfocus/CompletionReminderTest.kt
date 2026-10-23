package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class CompletionReminderTest {
    @Test fun `only exact expiry consumes once and not on every zero tick`() {
        val gate = CompletionReminder()
        assertFalse(gate.consume(true, false, 1))
        assertTrue(gate.consume(true, false, 0))
        repeat(10) { assertFalse(gate.consume(true, false, 0)) }
    }

    @Test fun `pause cancel and reset do not vibrate and a new session can finish`() {
        val gate = CompletionReminder()
        assertFalse(gate.consume(true, true, 0))
        assertFalse(gate.consume(false, false, 0))
        gate.reset()
        assertFalse(gate.consume(true, false, 60_000))
        assertTrue(gate.consume(true, false, 0))
        gate.reset()
        assertFalse(gate.consume(true, false, 60_000))
        assertTrue(gate.consume(true, false, 0))
    }

    @Test fun `muted expiry stays consumed when intensity later changes`() {
        val gate = CompletionReminder()
        assertTrue(gate.consume(true, false, 0))
        assertEquals(0L, ReminderVibrationPolicy.durationMillis(0f))
        assertFalse(gate.consume(true, false, 0))
        assertEquals(500L, ReminderVibrationPolicy.durationMillis(1f))
    }

    @Test fun `intensity maps to bounded duration and supports hardware without amplitude control`() {
        assertEquals(0L, ReminderVibrationPolicy.durationMillis(Float.NaN))
        assertEquals(350L, ReminderVibrationPolicy.durationMillis(0.5f))
        assertEquals(500L, ReminderVibrationPolicy.durationMillis(3f))
        assertEquals(-1, ReminderVibrationPolicy.amplitude(0.5f, false))
        assertEquals(255, ReminderVibrationPolicy.amplitude(1f, true))
        assertTrue(ReminderVibrationPolicy.amplitude(0.1f, true) < ReminderVibrationPolicy.amplitude(0.9f, true))
    }
}
