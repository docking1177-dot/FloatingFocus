package app.floatingfocus

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ScreenOnTimerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val session by FloatingService.session.collectAsStateWithLifecycle()
    val pending by FloatingService.pendingStarts.collectAsStateWithLifecycle()
    val allowed by rememberOverlayPermission()
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val enabled = session.active && session.screenDriven
    val conflict = session.active && !session.screenDriven
    PreferencePage("随亮屏计时", onBack) {
        EffectSwitch("随亮屏计时", enabled, available = pending == null && !conflict) { checked ->
            problem = null
            if (checked) {
                if (!OverlayPermission.granted(context)) problem = "请先允许悬浮窗权限，返回后再次开启。"
                else if (!FloatingService.startScreenTimer(context))
                    problem = FloatingService.session.value.error ?: "已有计时或启动请求，请先结束后再开启。"
            } else if (session.screenDriven && session.active) {
                try { context.startService(FloatingService.widgetControlIntent(context, WidgetCommand.END, session)) }
                catch (_: RuntimeException) { problem = "暂时无法结束，请从计时通知或首页重试。" }
            }
        }
        Text(when {
            pending != null -> "正在开始…"
            enabled && session.paused -> "息屏暂停，等待亮屏"
            enabled -> "亮屏计时中"
            conflict -> "已有计时，请先结束当前会话。"
            else -> "未开启"
        }, style = MaterialTheme.typography.titleMedium)
        if (enabled) Text("亮屏累计 ${formatUsageTime(session.elapsedMillis)}", style = MaterialTheme.typography.headlineSmall)
        Text("统计本轮启用期间的亮屏时间，锁屏亮起也计入；息屏显示不计入。",
            style = MaterialTheme.typography.bodyMedium)
        Text("息屏暂停，亮屏继续同一次计时。结束后需重新开启；不统计具体 App，也不扣 App 额度。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!allowed) {
            Text("需要已有的悬浮窗权限。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                if (context is Activity && !OverlayPermission.request(context))
                    problem = "无法打开系统设置，请在应用信息中允许显示悬浮窗。"
            }) { Text("授予悬浮窗权限") }
        }
        (problem ?: session.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        Text("保持计时通知。系统结束进程后本轮停止，仅保留最后保存的亮屏进度，不补算停机时间。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("系统显示状态暂不可用时暂停，等待状态恢复。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
