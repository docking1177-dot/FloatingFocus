package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class DualOverlayPolicyTest {
    private fun row(id: String, c4: Boolean = false) = SessionState(active=true,startRequestId=id,c4Enabled=c4)
    @Test fun admitsSecondAndRejectsThirdWithoutReplacingEither() {
        assertNull(DualOverlayPolicy.rejection(listOf(row("a")), false))
        assertNotNull(DualOverlayPolicy.rejection(listOf(row("a"),row("b")), false))
    }
    @Test fun permitsOrdinaryAlongsideC4ButRejectsSecondC4() {
        assertNull(DualOverlayPolicy.rejection(listOf(row("a",true)), false))
        assertNotNull(DualOverlayPolicy.rejection(listOf(row("a",true)), true))
    }
    @Test fun primaryIsOldestActiveAndNeverAnErrorOrFinishedSlot() {
        assertEquals("a",DualOverlayPolicy.primary(listOf(row("a"),row("b")))?.startRequestId)
        assertEquals("b",DualOverlayPolicy.primary(listOf(row("a").copy(active=false),row("b")))?.startRequestId)
    }
    @Test fun boundStopRejectsOldLegEvenForOrdinaryTimers() {
        val s=row("a").copy(timerStartedAt=100)
        assertFalse(SessionControls.acceptsStop(s,"a",99))
        assertFalse(SessionControls.acceptsStop(s,"b",100))
        assertTrue(SessionControls.acceptsStop(s,"a",100))
    }
}
