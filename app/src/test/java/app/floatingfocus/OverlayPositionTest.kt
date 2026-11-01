package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class OverlayPositionTest {
    @Test fun `expansion pins the visible time after temporary clamping without overwriting preferred anchor`() {
        val position = OverlayPosition().apply { anchor(130, 640, 100, 80) }
        val saved = position.snapshot(360, 720)
        // A larger compact timer has been clamped up: actual center 660, preferred 680.
        assertEquals(OverlayOffset(130, 600), position.place(100, 120, 360, 720))
        position.pinContent(180f, 660f)
        // Restoring a preset during measurement must not replace the temporary visible anchor.
        position.restore(saved, 360, 720)
        val placed = position.placeContent(OverlayContentGeometry(280, 160, 40f, 24f, 36f, 124f), 360, 720)
        assertEquals(OverlayOffset(40, 536), placed.offset)
        assertEquals(660f, placed.offset.y + placed.headerY, 0f)
        assertEquals(saved, position.snapshot(360, 720))
        position.releaseContent()
        assertEquals(OverlayOffset(130, 600), position.place(100, 120, 360, 720))
    }

    @Test fun `consecutive drag moves use the original time anchor despite header rearrangement`() {
        val position = OverlayPosition().apply { anchor(260, 300, 100, 80) }
        val geometry = OverlayContentGeometry(280, 160, 40f, 24f, 36f, 124f)
        val first = position.placeContent(geometry, 360, 720)
        position.beginDrag(first.offset.x, first.offset.y, 280, 160, first.headerX, first.headerY)
        position.dragTo(-10f, 0f, 280, 160, 360, 720, first.headerX, first.headerY)
        val remeasured = position.placeContent(geometry, 360, 720)
        assertEquals(220f, remeasured.headerX, 0f)
        val moved = position.dragTo(-20f, 0f, 280, 160, 360, 720, remeasured.headerX, remeasured.headerY)
        assertEquals(290f, moved.x + remeasured.headerX, 0f)
        assertEquals(340f, moved.y + remeasured.headerY, 0f)
    }

    @Test fun `dragging past the upward downward boundary does not add the layout flip to movement`() {
        val position = OverlayPosition().apply { anchor(130, 640, 100, 80) }
        val geometry = OverlayContentGeometry(280, 160, 40f, 24f, 36f, 124f)
        val first = position.placeContent(geometry, 360, 720)
        assertTrue(first.controlsAbove)
        position.beginDrag(first.offset.x, first.offset.y, 280, 160, first.headerX, first.headerY)
        position.dragTo(0f, -100f, 280, 160, 360, 720, first.headerX, first.headerY)
        val remeasured = position.placeContent(geometry, 360, 720)
        assertFalse(remeasured.controlsAbove)
        val moved = position.dragTo(0f, -110f, 280, 160, 360, 720, remeasured.headerX, remeasured.headerY)
        assertEquals(570f, moved.y + remeasured.headerY, 0f)
    }

    @Test fun `frame size changes and edge clamping do not overwrite the drag origin`() {
        val position = OverlayPosition().apply { anchor(130, 300, 100, 80) }
        position.beginDrag(130, 300, 100, 80)
        assertEquals(OverlayOffset(60, 320), position.dragTo(-20f, 20f, 200, 300, 360, 720, 100f, 40f))
        position.dragTo(10000f, 10000f, 200, 300, 360, 720, 100f, 40f)
        assertEquals(OverlayOffset(80, 300), position.dragTo(0f, 0f, 200, 300, 360, 720, 100f, 40f))
    }

    @Test fun `expanding keeps the time label at its original screen position`() {
        val position = OverlayPosition().apply { anchor(130, 300, 100, 80) }
        val layout = OverlayContentGeometry(280, 160, 60f, 24f, 36f, 124f)
        val placed = position.placeContent(layout, 360, 720)
        assertEquals(OverlayOffset(40, 304), placed.offset)
        assertFalse(placed.controlsAbove)
        assertEquals(180f, placed.offset.x + placed.headerX, 0f)
        assertEquals(340f, placed.offset.y + placed.headerY, 0f)
        assertEquals(OverlayOffset(130, 300), position.place(100, 80, 360, 720))
    }

    @Test fun `bottom edge expands controls upward instead of replacing the old time with end`() {
        val position = OverlayPosition().apply { anchor(130, 640, 100, 80) }
        val placed = position.placeContent(OverlayContentGeometry(280, 160, 60f, 24f, 36f, 124f), 360, 720)
        assertTrue(placed.controlsAbove)
        assertEquals(OverlayOffset(40, 556), placed.offset)
        assertEquals(680f, placed.offset.y + placed.headerY, 0f)
    }

    @Test fun `side edge preserves the time within the enlarged body without moving the saved anchor`() {
        val position = OverlayPosition().apply { anchor(260, 300, 100, 80) }
        val saved = position.snapshot(360, 720)
        val layout = OverlayContentGeometry(280, 160, 40f, 24f, 36f, 124f)
        repeat(10) {
            val placed = position.placeContent(layout, 360, 720)
            assertEquals(OverlayOffset(80, 304), placed.offset)
            assertEquals(310f, placed.offset.x + placed.headerX, 0f)
            assertEquals(340f, placed.offset.y + placed.headerY, 0f)
            assertEquals(saved, position.snapshot(360, 720))
            assertEquals(OverlayOffset(260, 300), position.place(100, 80, 360, 720))
        }
    }

    @Test fun `dragging expanded content remembers the time position not the whole panel center`() {
        val position = OverlayPosition().apply { anchor(260, 300, 100, 80) }
        val placed = position.placeContent(OverlayContentGeometry(280, 160, 40f, 24f, 36f, 124f), 360, 720)
        position.anchor(20, 100, 280, 160, placed.headerX, placed.headerY)
        assertEquals(OverlayOffset(200, 96), position.place(100, 80, 360, 720))
    }

    @Test fun `constrained rounded boundary keeps the complete time label inside the visible shape`() {
        val position = OverlayPosition().apply { anchor(0, 0, 100, 80) }
        val placed = position.placeContent(OverlayContentGeometry(280, 160, 80f, 24f, 36f, 124f), 360, 720)
        // The full 80x24 label fits the rounded end; its top-left corner must not be clipped.
        val dx = placed.headerX - 40f - 80f
        val dy = placed.headerY - 12f - 80f
        assertTrue(dx * dx + dy * dy <= 80f * 80f + .01f)
        assertEquals(OverlayOffset(0, 0), position.place(100, 80, 360, 720))
    }

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
