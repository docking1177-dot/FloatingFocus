package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class OverlayGeometryTest {
    @Test fun `shapes keep their orientation and honor base size immediately`() {
        OverlayShape.entries.forEach { shape ->
            val base = OverlayGeometry.frame(shape, 90f, 30f, 1f)
            val large = OverlayGeometry.frame(shape, 90f, 30f, 1.5f)
            assertEquals(base.width * 1.5f, large.width, .001f)
            assertEquals(base.height * 1.5f, large.height, .001f)
            assertEquals(base, OverlayGeometry.frame(shape, 90f, 30f, 1f))
            when (shape) {
                OverlayShape.ROUNDED_RECTANGLE -> assertTrue(base.width > base.height)
                OverlayShape.VERTICAL_CAPSULE -> assertTrue(base.height > base.width)
                OverlayShape.CIRCLE -> assertEquals(base.width, base.height, 0f)
            }
        }
    }

    @Test fun `size effect grows at policy nodes while color mode retains base geometry`() {
        OverlayShape.entries.forEach { shape ->
            ChangeMode.entries.forEach { mode ->
                val settings = ReminderSettings(style = ReminderStyle.SIZE, sizeMode = mode, baseSize = 1.2f, shape = shape)
                val widths = listOf(0f, .49f, .5f, .7f, .8f, .95f, 1f).map { progress ->
                    val presentation = ReminderPresenter.present(ReminderSnapshot(1000, progress), settings)
                    OverlayGeometry.frame(shape, 90f, 30f, presentation.size).width
                }
                assertEquals(widths[0], widths[1])
                assertTrue(widths.zipWithNext().all { (a, b) -> b >= a })
                assertEquals(widths.first() * 1.5f, widths.last(), .001f)
                val fixed = settings.copy(sizeEnabled = false, colorEnabled = true)
                assertEquals(1.2f, ReminderPresenter.present(ReminderSnapshot(0, 1f), fixed).size, 0f)
            }
        }
    }

    @Test fun `small available screen scales the whole content without distorting shape`() {
        val original = OverlayGeometry.frame(OverlayShape.VERTICAL_CAPSULE, 90f, 140f, 2.25f)
        val fitted = OverlayGeometry.frame(OverlayShape.VERTICAL_CAPSULE, 90f, 140f, 2.25f, 80f, 100f)
        assertTrue(fitted.width <= 80f && fitted.height <= 100f)
        assertEquals(original.width / original.height, fitted.width / fitted.height, .001f)
        assertTrue(fitted.scale < original.scale)
    }
}
