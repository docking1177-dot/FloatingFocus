package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class TimerStartAdmissionTest {
    @Test fun `manual pending prevents desktop from claiming success and stale cleanup cannot release it`() {
        val admission = TimerStartAdmission()
        assertTrue(admission.reserve("manual", false))
        assertFalse(admission.reserve("desktop", false))
        admission.complete("desktop")
        assertTrue(admission.owns("manual"))
        assertFalse(admission.reserve("third", false))
        admission.complete("manual")
        assertTrue(admission.reserve("desktop", false))
    }
    @Test fun `all active modes reject starts regardless of who requested them`() {
        val admission = TimerStartAdmission()
        assertFalse(admission.reserve("desktop", true))
        assertNull(admission.pendingId)
        assertTrue(admission.reserve("desktop", false))
        admission.complete("desktop")
        assertFalse(admission.owns("desktop"))
    }
}
