package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class OverlayDragGestureTest {
    @Test fun `a tap with small finger jitter keeps the button click`() {
        val gesture = OverlayDragGesture(8)
        gesture.start(240f, 180f)
        assertNull(gesture.move(246f, 184f))
        assertNull(gesture.move(240f, 180f))
        assertFalse(gesture.finish())
    }

    @Test fun `dragging from expanded content cancels the child only once`() {
        val gesture = OverlayDragGesture(8)
        gesture.start(320f, 240f)
        assertEquals(OverlayDragGesture.Movement(-20f, 15f, true), gesture.move(300f, 255f))
        assertEquals(OverlayDragGesture.Movement(-40f, 25f, false), gesture.move(280f, 265f))
        assertTrue(gesture.finish())
    }

    @Test fun `returning a drag to its start still cannot click a button`() {
        val gesture = OverlayDragGesture(8)
        gesture.start(100f, 100f)
        assertNotNull(gesture.move(130f, 100f))
        assertEquals(OverlayDragGesture.Movement(0f, 0f, false), gesture.move(100f, 100f))
        assertTrue(gesture.finish())
    }

    @Test fun `cancellation ignores later moves and resets for the next tap`() {
        val gesture = OverlayDragGesture(8)
        gesture.start(100f, 100f)
        gesture.cancel()
        assertNull(gesture.move(300f, 300f))
        assertTrue(gesture.finish())
        gesture.start(20f, 30f)
        assertFalse(gesture.finish())
    }

    @Test fun `large final displacement cannot become a click without prior move events`() {
        val gesture = OverlayDragGesture(8)
        gesture.start(100f, 100f)
        assertEquals(OverlayDragGesture.Movement(0f, 30f, true), gesture.move(100f, 130f))
        assertTrue(gesture.finish())
        assertNull(gesture.move(100f, 160f))
    }
}
