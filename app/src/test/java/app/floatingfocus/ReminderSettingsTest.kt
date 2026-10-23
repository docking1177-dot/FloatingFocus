package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class ReminderSettingsTest {
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
        assertEquals(base.color.argb, present(1f, base.copy(style = ReminderStyle.SIZE)).colorArgb)
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

    @Test fun `preset and interpolated opaque text backing meets white text contrast`() {
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
                assertTrue("$color at $p", 1.05 / (luminance(output.colorArgb) + 0.05) >= 4.5)
            }
        }
    }
}
