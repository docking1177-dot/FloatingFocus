package app.floatingfocus

import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun DeveloperEntry() {
    val context = LocalContext.current
    TextButton(onClick = { context.startActivity(Intent(context, ReminderLabActivity::class.java)) }) {
        Text("提醒实验室 · Debug")
    }
}
