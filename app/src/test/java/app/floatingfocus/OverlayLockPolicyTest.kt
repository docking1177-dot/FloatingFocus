package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class OverlayLockPolicyTest {
    private fun session() = SessionState(active = true, startRequestId = "session", timerStartedAt = 500)

    @Test fun requestsAreBoundToBothSessionAndLeg() {
        val state = session()
        assertTrue(OverlayLockPolicy.accepts(state, OverlayLockRequest("session", 500, true)))
        assertTrue(OverlayLockPolicy.accepts(state.copy(overlayLocked = true), OverlayLockRequest("session", 500, false)))
        assertFalse(OverlayLockPolicy.accepts(state, OverlayLockRequest("old", 500, false)))
        assertFalse(OverlayLockPolicy.accepts(state, OverlayLockRequest("session", 499, true)))
        assertFalse(OverlayLockPolicy.accepts(state.copy(active = false), OverlayLockRequest("session", 500, true)))
        assertFalse(OverlayLockPolicy.accepts(state.copy(automatic = true), OverlayLockRequest("session", 500, true)))
        assertFalse(OverlayLockPolicy.accepts(state.copy(startRequestId = null), OverlayLockRequest("", 500, true)))
        assertNull(OverlayLockPolicy.apply(state, OverlayLockRequest("old", 500, true)))
        assertNull(OverlayLockPolicy.apply(state, OverlayLockRequest("session", 501, false)))
    }

    @Test fun lockAndUnlockDoNotGrantAnyC4SessionCapability() {
        val state = session().copy(c4Enabled = true, c4Active = true, c4Locked = true)
        for (locked in listOf(false, true)) {
            val next = OverlayLockPolicy.apply(state, OverlayLockRequest("session", 500, locked))!!
            assertTrue(OverlayLockPolicy.accepts(next, OverlayLockRequest("session", 500, !locked)))
            assertFalse(SessionControls.canEnd(next))
            assertFalse(SessionControls.canPause(next))
            assertFalse(SessionControls.canExtend(next))
        }
    }

    @Test fun lockDoesNotAlterTimingAndRepeatedDesiredStateIsIdempotent() {
        assertFalse(SessionState().overlayLocked)
        val state = session().copy(mode = TimerMode.STOPWATCH, elapsedMillis = 1000, sampledAt = 1500)
        val request = OverlayLockRequest("session", 500, true)
        val locked = OverlayLockPolicy.apply(state, request)!!
        assertTrue(locked.overlayLocked)
        assertEquals(state, locked.copy(overlayLocked = false))
        assertEquals(locked, OverlayLockPolicy.apply(locked, request))
        assertEquals(state, OverlayLockPolicy.apply(locked, request.copy(locked = false)))
    }

    @Test fun systemThresholdOnlyCapsTheLockedDisplayAndRestoresSavedOpacity() {
        assertEquals(1f, OverlayLockPolicy.displayOpacity(1f, false, .65f), 0f)
        assertEquals(.65f, OverlayLockPolicy.displayOpacity(1f, true, .65f), 0f)
        assertEquals(.4f, OverlayLockPolicy.displayOpacity(.4f, true, .65f), 0f)
        assertEquals(1f, OverlayLockPolicy.displayOpacity(1f, false, .65f), 0f)
        assertEquals(.7f, OverlayLockPolicy.displayOpacity(.7f, true, .8f), 0f)
        assertEquals(1f, OverlayLockPolicy.displayOpacity(1f, true, null), 0f)
    }

    @Test fun opacityHandlesInvalidInputsWithoutExceedingTheSystemThreshold() {
        assertEquals(.6f, OverlayLockPolicy.displayOpacity(Float.NaN, true, .6f), 0f)
        assertEquals(0f, OverlayLockPolicy.displayOpacity(1f, true, Float.NaN), 0f)
        assertEquals(0f, OverlayLockPolicy.displayOpacity(1f, true, -.1f), 0f)
        assertEquals(1f, OverlayLockPolicy.displayOpacity(2f, false, null), 0f)
    }
}
