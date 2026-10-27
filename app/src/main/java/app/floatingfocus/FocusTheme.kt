package app.floatingfocus

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun FocusTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme(
        primary = Color(0xFFA8CEB4), onPrimary = Color(0xFF163A27),
        background = Color(0xFF141B17), surface = Color(0xFF202923),
        onBackground = Color(0xFFEDF1E9), onSurface = Color(0xFFEDF1E9),
        secondaryContainer = Color(0xFF304839), onSecondaryContainer = Color(0xFFD9EBD6),
        surfaceVariant = Color(0xFF354039), onSurfaceVariant = Color(0xFFBDCABF)
    ) else lightColorScheme(
        primary = Color(0xFF426956), onPrimary = Color.White,
        background = Color(0xFFF5F4EF), surface = Color(0xFFFFFEFA),
        onBackground = Color(0xFF202923), onSurface = Color(0xFF202923),
        secondaryContainer = Color(0xFFE2EBDD), onSecondaryContainer = Color(0xFF284633)
    ), content = content)
}
