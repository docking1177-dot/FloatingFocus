package app.floatingfocus

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal fun focusColorScheme(darkTheme: Boolean): ColorScheme = if (darkTheme) darkColorScheme(
        primary = Color(0xFFA8CEB4), onPrimary = Color(0xFF163A27),
        primaryContainer = Color(0xFF304F3D), onPrimaryContainer = Color(0xFFD0ECD7),
        secondary = Color(0xFFBDCDBF), onSecondary = Color(0xFF28352C),
        background = Color(0xFF141B17), surface = Color(0xFF202923),
        onBackground = Color(0xFFEDF1E9), onSurface = Color(0xFFEDF1E9),
        secondaryContainer = Color(0xFF304839), onSecondaryContainer = Color(0xFFD9EBD6),
        tertiary = Color(0xFFB4CCD1), onTertiary = Color(0xFF20343A),
        tertiaryContainer = Color(0xFF374B52), onTertiaryContainer = Color(0xFFD0E8ED),
        surfaceVariant = Color(0xFF354039), onSurfaceVariant = Color(0xFFBDCABF),
        surfaceDim = Color(0xFF141B17), surfaceBright = Color(0xFF354039),
        surfaceContainerLowest = Color(0xFF101612), surfaceContainerLow = Color(0xFF19221C),
        surfaceContainer = Color(0xFF202923), surfaceContainerHigh = Color(0xFF2A342D),
        surfaceContainerHighest = Color(0xFF354039),
        outline = Color(0xFF8A998E), outlineVariant = Color(0xFF455249),
        inverseSurface = Color(0xFFE2EBDD), inverseOnSurface = Color(0xFF28352C),
        inversePrimary = Color(0xFF426956), surfaceTint = Color(0xFFA8CEB4)
    ) else lightColorScheme(
        primary = Color(0xFF426956), onPrimary = Color.White,
        primaryContainer = Color(0xFFC5E8D0), onPrimaryContainer = Color(0xFF153B28),
        secondary = Color(0xFF526556), onSecondary = Color.White,
        background = Color(0xFFF5F4EF), surface = Color(0xFFFFFEFA),
        onBackground = Color(0xFF202923), onSurface = Color(0xFF202923),
        secondaryContainer = Color(0xFFE2EBDD), onSecondaryContainer = Color(0xFF284633),
        tertiary = Color(0xFF50666D), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFD3E8EC), onTertiaryContainer = Color(0xFF243E45),
        surfaceVariant = Color(0xFFE2E8DF), onSurfaceVariant = Color(0xFF4B594F),
        surfaceDim = Color(0xFFDADFD5), surfaceBright = Color(0xFFFFFEFA),
        surfaceContainerLowest = Color(0xFFFFFEFA), surfaceContainerLow = Color(0xFFF1F3EC),
        surfaceContainer = Color(0xFFECEFE6), surfaceContainerHigh = Color(0xFFE7EBE1),
        surfaceContainerHighest = Color(0xFFE2E8DF),
        outline = Color(0xFF727E73), outlineVariant = Color(0xFFC2CCC0),
        inverseSurface = Color(0xFF2B352E), inverseOnSurface = Color(0xFFE9EEE5),
        inversePrimary = Color(0xFFA8CEB4), surfaceTint = Color(0xFF426956)
    )

/** Activity content and dialogs share the system mode; overlay colors remain explicit. */
@Composable
fun FocusTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = focusColorScheme(darkTheme)
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}

internal object FocusSpacing {
    val small = 8.dp
    val medium = 12.dp
    val section = 16.dp
    val page = 24.dp
}
