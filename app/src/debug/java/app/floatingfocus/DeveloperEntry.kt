package app.floatingfocus

import android.content.Intent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun DeveloperEntry() {
    val context = LocalContext.current
    HorizontalDivider(Modifier.padding(horizontal = 24.dp))
    SettingsEntry("开发者模式") { context.startActivity(Intent(context, ReminderLabActivity::class.java)) }
}
