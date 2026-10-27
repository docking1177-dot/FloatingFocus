package app.floatingfocus

import android.content.Intent
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun StrategyConflictDialog(message: String, seconds: Int, presetId: String? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("无法开始计时") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = {
            onDismiss()
            context.startActivity(Intent(context, SettingsActivity::class.java).putExtra("open_strategy", true)
                .putExtra("duration_seconds", seconds).putExtra(SettingsActivity.EXTRA_PRESET_ID, presetId))
        }) { Text("修改提醒策略") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } })
}
