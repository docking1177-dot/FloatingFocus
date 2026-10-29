package app.floatingfocus

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test

class FocusThemeTest {
    private fun contrast(a: Color, b: Color): Double {
        val one = ReminderPresenter.luminance(a.toArgb())
        val two = ReminderPresenter.luminance(b.toArgb())
        return (maxOf(one, two) + .05) / (minOf(one, two) + .05)
    }

    @Test fun `body action hint dialog and error text stay readable in both schemes`() {
        for (dark in listOf(false, true)) {
            val c = focusColorScheme(dark)
            val pairs = listOf(c.onBackground to c.background, c.onSurface to c.surface,
                c.onSurfaceVariant to c.background, c.onSurfaceVariant to c.surfaceContainerHigh,
                c.primary to c.background, c.onPrimary to c.primary,
                c.onPrimaryContainer to c.primaryContainer, c.onSecondaryContainer to c.secondaryContainer,
                c.onTertiaryContainer to c.tertiaryContainer, c.onErrorContainer to c.errorContainer,
                c.error to c.background, c.inverseOnSurface to c.inverseSurface)
            for ((ink, background) in pairs) assertTrue("dark=$dark $ink on $background",
                contrast(ink, background) >= 4.5)
        }
    }

    @Test fun `chart bars and enabled control outlines remain distinguishable`() {
        for (dark in listOf(false, true)) {
            val c = focusColorScheme(dark)
            assertTrue(contrast(c.outline, c.background) >= 3)
            assertTrue(contrast(c.primary.copy(alpha = .68f).compositeOver(c.surface), c.surface) >= 3)
        }
    }

    @Test fun `dark and light keep opposite background luminance and the green accent`() {
        val light = focusColorScheme(false)
        val dark = focusColorScheme(true)
        assertTrue(ReminderPresenter.luminance(light.background.toArgb()) > .8)
        assertTrue(ReminderPresenter.luminance(dark.background.toArgb()) < .05)
        for (c in listOf(light, dark)) {
            assertTrue(c.primary.green > c.primary.red && c.primary.green > c.primary.blue)
        }
    }
}
