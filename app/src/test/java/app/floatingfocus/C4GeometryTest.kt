package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class C4GeometryTest {
    @Test fun `v1 structure keeps full LCD and smaller keyboard inside the body`() {
        val normal = C4Geometry(292f, 360f)
        assertEquals(48f, normal.display.y, .01f)
        assertTrue(normal.display.height >= 60f)
        assertEquals(C4Rect(292f * .14f, 10f, 292f * .80f, 342f), normal.body)
        assertTrue(normal.keyboardPanel.width in 180f..191f)
        assertTrue(normal.keyboardPanel.height in 200f..210f)
        assertTrue(normal.display.y + normal.display.height + 5 <= normal.keyboardPanel.y)
    }

    @Test fun `all twelve targets fit their visible panel and never overlap`() {
        for ((width, height) in listOf(292f to 360f, 292f to 320f, 260f to 300f, 210f to 280f, 180f to 280f) +
            listOf(302f, 303f, 304f, 309f, 310f, 315f, 316f).map { 292f to it }) {
            val g = C4Geometry(width, height)
            val panel = g.keyboardPanel
            val keys = (0..11).map(g::key)
            assertTrue(keys.all { it.width >= 44f && it.height >= 44f && it.x >= panel.x && it.y >= panel.y &&
                it.x + it.width <= panel.x + panel.width + .01f && it.y + it.height <= panel.y + panel.height + .01f })
            for (a in keys.indices) for (b in a + 1 until keys.size) {
                val x = keys[a]; val y = keys[b]
                assertFalse(x.x < y.x + y.width && y.x < x.x + x.width && x.y < y.y + y.height && y.y < x.y + x.height)
            }
            assertTrue(g.key(0).x + g.key(0).width < g.key(1).x)
            assertTrue(g.key(0).y + g.key(0).height < g.key(3).y)
            assertEquals(g.gap, g.rowGap, .01f)
            assertTrue(g.keypad.height <= g.keypadViewportHeight + .01f)
            assertInside(g.body, panel)
            assertInside(g.body, C4Rect(g.display.x - 6, g.display.y - 5, g.display.width + 12, g.display.height + 10))
            assertTrue(g.display.height >= 48f)
            assertTrue(g.display.y + g.display.height + 5 <= panel.y)
        }
    }

    private fun assertInside(outer: C4Rect, inner: C4Rect) {
        assertTrue("$inner outside $outer", inner.x >= outer.x - .01f && inner.y >= outer.y - .01f &&
            inner.x + inner.width <= outer.x + outer.width + .01f && inner.y + inner.height <= outer.y + outer.height + .01f)
    }

    @Test fun `physically short windows keep LCD intact and scroll only the keyboard`() {
        val g = C4Geometry(180f, 218f)
        assertTrue(g.display.height >= C4Theme.TIME_LINE_HEIGHT + C4Theme.INPUT_LINE_HEIGHT + C4Theme.DISPLAY_LINE_GAP)
        assertInside(g.body, g.displayFrame)
        assertInside(g.body, g.keyboardPanel)
        assertTrue(g.keypad.height > g.keypadViewportHeight)
        assertTrue(g.keypadViewportHeight >= 44f)
    }

    @Test fun `military grey key legends remain readable on light and dark backdrops`() {
        fun composite(color: Long, backdrop: Int): Int {
            fun channel(shift: Int) = (((color.toInt() ushr shift and 255) * C4Theme.OPACITY +
                (backdrop ushr shift and 255) * (1 - C4Theme.OPACITY)).toInt() shl shift)
            return 0xFF000000.toInt() or channel(16) or channel(8) or channel(0)
        }
        for (backdrop in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            val background = ReminderPresenter.luminance(composite(C4Theme.KEY_COLOR, backdrop))
            val ink = ReminderPresenter.luminance(composite(C4Theme.KEY_INK, backdrop))
            assertTrue((background + .05) / (ink + .05) >= 3) // 21dp bold numerals.
        }
    }
}
