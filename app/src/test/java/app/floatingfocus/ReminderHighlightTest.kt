package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class ReminderHighlightTest {
    @Test fun `orange and red steps remain distinct in hue and luminance`() {
        val settings = ReminderSettings(colorMode = ChangeMode.STEPS)
        val orange = ReminderPresenter.present(ReminderSnapshot(300_000, .799f), settings).colorArgb
        val red = ReminderPresenter.present(ReminderSnapshot(180_000, .8f), settings).colorArgb
        assertTrue(((orange ushr 8) and 255) - ((red ushr 8) and 255) >= 100)
        assertTrue(ReminderPresenter.luminance(orange) - ReminderPresenter.luminance(red) >= .12)
        assertEquals(orange, ReminderPresenter.present(ReminderSnapshot(450_000, .5f), settings).colorArgb)
    }

    @Test fun `highlight increases luminance without changing alpha and returns exactly to base`() {
        val colors = OverlayColor.entries.map { it.argb } + listOf(ReminderPresenter.ORANGE, ReminderPresenter.RED)
        for (base in colors) {
            val samples = (0..40).map { ReminderPresenter.highlight(base, it / 100f) }
            assertEquals(base, samples.first())
            assertTrue(samples.all { (it ushr 24) == 255 })
            assertTrue(samples.zipWithNext().all { (a, b) -> ReminderPresenter.luminance(b) >= ReminderPresenter.luminance(a) })
            assertTrue(ReminderPresenter.luminance(samples.last()) > ReminderPresenter.luminance(base) * 1.5)
            assertEquals(base, ReminderPresenter.highlight(base, 0f))
        }
    }

    @Test fun `foreground stays readable throughout highlight and continuous color changes`() {
        for (base in OverlayColor.entries) for (progress in 0..100) for (highlight in 0..40) {
            val presentation = ReminderPresenter.present(ReminderSnapshot(1000, progress / 100f), ReminderSettings(color = base))
            val background = ReminderPresenter.highlight(presentation.colorArgb, highlight / 100f)
            val a = ReminderPresenter.luminance(background)
            val b = ReminderPresenter.luminance(ReminderPresenter.contentColor(background))
            assertTrue((maxOf(a, b) + .05) / (minOf(a, b) + .05) >= 4.5)
        }
    }

    @Test fun `highlight respects intensity without modifying configured opacity or geometry`() {
        for (strength in listOf(0f, .5f, 1f)) for (shape in OverlayShape.entries) {
            val p = ReminderPresenter.present(ReminderSnapshot(1000, .9f),
                ReminderSettings(intensity = strength, opacity = .6f, baseSize = 1.3f, shape = shape))
            assertEquals(strength, p.animationStrength, 0f)
            assertEquals(ReminderPresenter.HIGHLIGHT_DEPTH * strength, p.pulseDepth, .0001f)
            assertEquals(.6f, p.opacity, 0f)
            assertEquals(1.3f, p.size, 0f)
            assertEquals(shape, p.shape)
        }
    }
}
