package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class OverlayPositionTest {
    @Test fun `saved center restores after restart rotation and temporary expansion`() {
        val first = OverlayPosition().apply { anchor(260, 20, 100, 80) }
        val saved = first.snapshot(360, 720)
        val restarted = OverlayPosition().apply { restore(saved, 360, 720) }
        assertEquals(OverlayOffset(260, 20), restarted.place(100, 80, 360, 720))
        restarted.place(300, 600, 360, 720)
        assertEquals(saved, restarted.snapshot(360, 720))
        restarted.restore(saved, 720, 360)
        assertEquals(OverlayOffset(570, 0), restarted.place(100, 80, 720, 360))
        restarted.restore(saved, 360, 720)
        assertEquals(OverlayOffset(260, 20), restarted.place(100, 80, 360, 720))
    }
    @Test fun `expansion at every edge restores collapsed position without accumulating drift`() {
        for (x in listOf(0, 130, 260)) for (y in listOf(0, 300, 640)) {
            val position = OverlayPosition().apply { anchor(x, y, 100, 80) }
            repeat(10) {
                val expanded = position.place(280, 300, 360, 720)
                assertTrue(expanded.x in 0..80 && expanded.y in 0..420)
                assertEquals(OverlayOffset(x, y), position.place(100, 80, 360, 720))
            }
        }
    }

    @Test fun `dragging an expanded overlay establishes the new preferred center`() {
        val position = OverlayPosition().apply { anchor(260, 640, 100, 80) }
        position.place(280, 300, 360, 720)
        position.anchor(20, 100, 280, 300)
        assertEquals(OverlayOffset(110, 210), position.place(100, 80, 360, 720))
    }

    @Test fun `temporary constrained screen does not overwrite anchor`() {
        val position = OverlayPosition().apply { anchor(240, 600, 100, 80) }
        assertEquals(OverlayOffset(0, 0), position.place(400, 400, 300, 300))
        assertEquals(OverlayOffset(240, 600), position.place(100, 80, 360, 720))
    }

    @Test fun `extension resets to configured base size and retains the configured growth curve`() {
        val timer = Countdown(60_000, 0).extendAt(80_000, 60_000)!!
        for (shape in OverlayShape.entries) for (base in listOf(.75f, 1f, 1.5f)) {
            val settings = ReminderSettings(style = ReminderStyle.SIZE, baseSize = base, shape = shape)
            fun size(now: Long) = ReminderPresenter.present(ReminderSnapshot.at(timer, now), settings).size
            assertEquals(base, size(80_000), .0001f)
            assertEquals(base, size(110_000), .0001f)
            assertEquals(base * 1.25f, size(125_000), .0001f)
            assertEquals(base * 1.5f, size(140_000), .0001f)
            val fixed = settings.copy(sizeEnabled = false, colorEnabled = true)
            assertEquals(base, ReminderPresenter.present(ReminderSnapshot.at(timer, 139_000), fixed).size, .0001f)
        }
    }
}
