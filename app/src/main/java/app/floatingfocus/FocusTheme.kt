package app.floatingfocus

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun FocusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF426956), onPrimary = Color.White,
        background = Color(0xFFF5F4EF), surface = Color(0xFFFFFEFA),
        onBackground = Color(0xFF202923), onSurface = Color(0xFF202923),
        secondaryContainer = Color(0xFFE2EBDD), onSecondaryContainer = Color(0xFF284633)
    ), content = content)
}
