package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class CountdownTest {
    @Test fun `fifteen minute boundaries are based on elapsed time`() {
        val timer = Countdown(900_000, 42_000)
        assertEquals(ReminderStage.QUIET, ReminderPolicy.stage(timer.progressAt(491_999)))
        assertEquals(ReminderStage.WARM, ReminderPolicy.stage(timer.progressAt(492_000)))
        assertEquals(ReminderStage.URGENT, ReminderPolicy.stage(timer.progressAt(762_000)))
        assertEquals(ReminderStage.FINISHED, ReminderPolicy.stage(timer.progressAt(942_000)))
    }
    @Test fun `sleep and skipped callbacks do not lose elapsed time`() {
        val timer = Countdown(900_000, 100)
        assertEquals(900_000L, timer.remainingAt(100))
        assertEquals(120_000L, timer.remainingAt(780_100))
        assertEquals(0L, timer.remainingAt(9_000_000))
        assertEquals(1f, timer.progressAt(9_000_000), 0f)
    }
    @Test fun `clock model clamps early reads and prevents invalid durations`() {
        assertEquals(10L, Countdown(10, 100).remainingAt(90))
        listOf(0L, -1L, Countdown.MAX_DURATION_MILLIS + 1).forEach {
            assertThrows(IllegalArgumentException::class.java) { Countdown(it, 0) }
        }
    }
    @Test fun `display rounds up so zero only appears at completion`() {
        assertEquals("15:00", formatRemaining(900_000))
        assertEquals("15:00", formatRemaining(899_999))
        assertEquals("00:01", formatRemaining(1))
        assertEquals("00:00", formatRemaining(0))
        assertEquals("180:00", formatRemaining(Countdown.MAX_DURATION_MILLIS))
    }
}
