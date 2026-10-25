package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class ReminderSettingsTest {
    @Test fun `independent switches migrate legacy styles and round trip every combination`() {
        for (style in listOf(ReminderStyle.COLOR, ReminderStyle.SIZE)) {
            val migrated = ReminderSettingsCodec.decode(mapOf("style" to style.name))
            assertEquals(style == ReminderStyle.COLOR, migrated.colorEnabled)
            assertEquals(style == ReminderStyle.SIZE, migrated.sizeEnabled)
            for (color in listOf(false, true)) for (size in listOf(false, true)) {
                val settings = migrated.copy(colorEnabled = color, sizeEnabled = size)
                assertEquals(settings, ReminderSettingsCodec.decode(ReminderSettingsCodec.encode(settings)))
            }
        }
        val malformed = ReminderSettingsCodec.decode(mapOf("style" to "SIZE", "colorEnabled" to "true", "sizeEnabled" to 0))
        assertFalse(malformed.colorEnabled)
        assertTrue(malformed.sizeEnabled)
    }

    @Test fun `color and size compose independently at all important time nodes`() {
        for (mode in ChangeMode.entries) for (shape in OverlayShape.entries) {
            val both = ReminderSettings(colorEnabled = true, sizeEnabled = true, colorMode = mode, sizeMode = mode, shape = shape)
            for (p in listOf(0f, .499f, .5f, .65f, .8f, .9f, 1f)) {
                val frame = ReminderSnapshot(0, p)
                val combined = ReminderPresenter.present(frame, both)
                val colorOnly = ReminderPresenter.present(frame, both.copy(sizeEnabled = false))
                val sizeOnly = ReminderPresenter.present(frame, both.copy(colorEnabled = false))
                val neither = ReminderPresenter.present(frame, both.copy(colorEnabled = false, sizeEnabled = false))
                assertEquals(colorOnly.colorArgb, combined.colorArgb)
                assertEquals(sizeOnly.size, combined.size, 0f)
                assertEquals(both.color.argb, neither.colorArgb)
                assertEquals(both.baseSize, neither.size, 0f)
                assertEquals(combined.opacity, neither.opacity, 0f)
            }
            assertEquals(ReminderPresenter.RED, ReminderPresenter.present(ReminderSnapshot(0, 1f), both).colorArgb)
            assertEquals(1.5f, ReminderPresenter.present(ReminderSnapshot(0, 1f), both).size, 0f)
        }
    }
    @Test fun `missing keys retain original defaults and each field round trips`() {
        assertEquals(ReminderSettings(), ReminderSettingsCodec.decode(emptyMap<String, Any>()))
        val settings = ReminderSettings(ReminderStyle.SIZE, ChangeMode.STEPS, ChangeMode.STEPS,
            0.4f, 1.5f, 0.3f, OverlayColor.PURPLE, OverlayShape.CIRCLE)
        assertEquals(settings, ReminderSettingsCodec.decode(ReminderSettingsCodec.encode(settings)))
        assertEquals(settings.copy(shape = OverlayShape.ROUNDED_RECTANGLE),
            ReminderSettingsCodec.decode(ReminderSettingsCodec.encode(settings) - "shape"))
    }

    @Test fun `bad values cannot crash startup or expose experimental confirmation`() {
        val settings = ReminderSettingsCodec.decode(mapOf("style" to "CONFIRM", "shape" to "future_shape",
            "intensity" to Float.NaN, "baseSize" to 100f, "opacity" to -1f, "colorMode" to 1,
            "color" to "BLUE"))
        assertEquals(ReminderStyle.COLOR, settings.style)
        assertEquals(OverlayShape.ROUNDED_RECTANGLE, settings.shape)
        assertEquals(1f, settings.intensity, 0f)
        assertEquals(1.5f, settings.baseSize, 0f)
        assertEquals(0.3f, settings.opacity, 0f)
        assertEquals(ChangeMode.CONTINUOUS, settings.colorMode)
        assertEquals(OverlayColor.BLUE, settings.color)
    }

    @Test fun `chosen color is dynamic starting point and fixed color for size strategy`() {
        val base = ReminderSettings(color = OverlayColor.BLUE)
        fun present(p: Float, s: ReminderSettings) = ReminderPresenter.present(ReminderSnapshot(0, p), s)
        assertEquals(base.color.argb, present(0f, base).colorArgb)
        assertEquals(ReminderPresenter.ORANGE, present(0.8f, base).colorArgb)
        assertEquals(ReminderPresenter.RED, present(1f, base).colorArgb)
        assertEquals(base.color.argb, present(1f, base.copy(intensity = 0f)).colorArgb)
        assertEquals(base.color.argb, present(1f, base.copy(colorEnabled = false, sizeEnabled = true)).colorArgb)
    }

    @Test fun `all shapes retain identical time effects across policy boundaries`() {
        ReminderStyle.entries.forEach { style ->
            ChangeMode.entries.forEach { mode ->
                listOf(0f, 0.499f, 0.5f, 0.799f, 0.8f, 1f).forEach { progress ->
                    val settings = ReminderSettings(style = style, colorMode = mode, sizeMode = mode,
                        baseSize = 1.5f, opacity = 0.3f, color = OverlayColor.GREEN)
                    val frame = ReminderSnapshot(1000, progress)
                    val rect = ReminderPresenter.present(frame, settings)
                    val circle = ReminderPresenter.present(frame, settings.copy(shape = OverlayShape.CIRCLE))
                    assertEquals(rect.copy(shape = OverlayShape.CIRCLE), circle)
                }
            }
        }
    }

    @Test fun `preset and interpolated shape paint meets selected foreground contrast`() {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val s = ((color ushr shift) and 255) / 255.0
                return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        OverlayColor.entries.forEach { color ->
            (0..100).forEach { p ->
                val output = ReminderPresenter.present(ReminderSnapshot(0, p / 100f), ReminderSettings(color = color))
                val background = luminance(output.colorArgb)
                val foreground = luminance(ReminderPresenter.contentColor(output.colorArgb))
                assertTrue("$color at $p", (maxOf(background, foreground) + 0.05) / (minOf(background, foreground) + 0.05) >= 4.5)
            }
        }
    }
}
