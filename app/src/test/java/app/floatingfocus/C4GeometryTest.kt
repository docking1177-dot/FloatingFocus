package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class C4GeometryTest {
    @Test fun `keyboard including backplate is modestly smaller with unchanged display and body`() {
        val normal = C4Geometry(292f, 360f)
        assertEquals(C4Rect(292f * .23f, 48f, 292f * .66f, 58f), normal.display)
        assertEquals(C4Rect(292f * .14f, 10f, 292f * .80f, 342f), normal.body)
        assertTrue(normal.keyboardPanel.width in 180f..191f)
        assertTrue(normal.keyboardPanel.height in 200f..210f)
        assertEquals(134f, normal.keypad.y, .01f)
        val compact = C4Geometry(262.8f, 324f)
        assertTrue(compact.keyboardPanel.width < 177f)
        assertTrue(compact.keyboardPanel.height in 188f..200f)
        assertEquals(C4Rect(262.8f * .23f, 4f, 262.8f * .66f, 40f), compact.display)
    }

    @Test fun `all twelve targets fit their visible panel and never overlap`() {
        for ((width, height) in listOf(292f to 360f, 262.8f to 324f, 219f to 270f, 180f to 218f, 292f to 260f)) {
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
        }
    }
}
