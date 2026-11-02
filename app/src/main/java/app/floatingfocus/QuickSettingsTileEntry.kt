package app.floatingfocus

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/** Contextual, user-confirmed native add request. Accepted is not reported as added without its callback. */
@Composable
internal fun QuickSettingsTileEntry() {
    val activity = LocalActivity.current
    var adding by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(adding) {
        if (adding) {
            delay(5000)
            adding = false
            feedback = "如未出现系统确认，可在控制中心的编辑页面添加「FF计时」。"
        }
    }
    SettingsEntry(if (adding) "等待系统确认…" else "控制中心快捷开关") {
        if (!adding) {
            if (Build.VERSION.SDK_INT < 33 || activity == null) {
                feedback = "下拉控制中心，进入编辑，将「FF计时」添加到快捷开关。"
            } else {
                adding = true
                feedback = "空闲开始；计时中暂停，暂停后继续；超时或C4点击查看。"
                try {
                    activity.getSystemService(StatusBarManager::class.java).requestAddTileService(
                        ComponentName(activity, FocusTileService::class.java), activity.getString(R.string.tile_label),
                        Icon.createWithResource(activity, R.drawable.ic_qs_timer), activity.mainExecutor) { result ->
                        adding = false
                        feedback = when (result) {
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "已添加到控制中心。"
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "控制中心已添加 FF计时。"
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "未添加，可稍后在控制中心编辑中添加。"
                            else -> "系统暂未添加（$result），可在控制中心编辑中添加「FF计时」。"
                        }
                    }
                } catch (_: RuntimeException) {
                    adding = false
                    feedback = "系统未能打开添加确认，请在控制中心编辑中添加「FF计时」。"
                }
            }
        }
    }
    feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = FocusSpacing.page)) }
}
