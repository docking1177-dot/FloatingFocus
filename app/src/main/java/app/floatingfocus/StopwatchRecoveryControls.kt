package app.floatingfocus

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun HomeStopwatchRecovery() {
    val context=LocalContext.current
    val store=remember(context) { TimerRecordStore.get(context) }
    val records by store.records.collectAsStateWithLifecycle()
    val loading by store.isLoading.collectAsStateWithLifecycle()
    val error by store.error.collectAsStateWithLifecycle()
    val source=remember(records) { StopwatchRecovery.candidates(records).firstOrNull() }
    if (!loading && source!=null) {
        OutlinedCard(Modifier.fillMaxWidth().padding(top=8.dp)) {
            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("可继续 · ${source.appName}",style=MaterialTheme.typography.titleSmall)
                StopwatchRecoveryAction(source, readable=error==null)
                if (error!=null) TextButton(onClick=store::reload) { Text("重试读取进度") }
            }
        }
    } else if (!loading && error!=null) {
        TextButton(onClick=store::reload) { Text("进度读取失败，重试") }
    }
}

/** Both entry points use the same ID-only service admission; permission return never auto-starts. */
@Composable
internal fun StopwatchRecoveryAction(record: TimerRecord, readable: Boolean = true) {
    val context=LocalContext.current
    val session by FloatingService.session.collectAsStateWithLifecycle()
    val pending by FloatingService.pendingStarts.collectAsStateWithLifecycle()
    val allowed by rememberOverlayPermission()
    var message by remember(record.id) { mutableStateOf<String?>(null) }
    Text("已保存 ${formatElapsed(record.displayElapsedMillis)} · ${record.stopwatchBehavior.label}",
        style=MaterialTheme.typography.bodyMedium)
    Text("最后保存 ${java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(record.endedWallMillis))}",
        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Text("停机时间不计，最后未保存的进度可能少记。",style=MaterialTheme.typography.bodySmall,
        color=MaterialTheme.colorScheme.onSurfaceVariant)
    if (!allowed) TextButton(onClick={
        if (context is Activity && !OverlayPermission.request(context)) message="请在系统应用信息中允许显示悬浮窗。"
    }) { Text("授予悬浮窗权限") }
    TextButton(enabled=readable && !session.active && pending==null && allowed, onClick={
        message=null
        if (!FloatingService.resumeStopwatch(context,record.id))
            message=FloatingService.session.value.error ?: "已有计时或启动请求，请先结束。"
    }) { Text(if (pending!=null) "正在开始…" else "继续计时") }
    if (session.active) Text("请先结束当前计时。",style=MaterialTheme.typography.bodySmall)
    (message ?: session.error)?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
}
