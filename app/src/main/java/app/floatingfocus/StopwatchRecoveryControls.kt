package app.floatingfocus

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Always available in the shared home area, including while another session is running. */
@Composable
internal fun HomeStopwatchRecovery() {
    val context = LocalContext.current
    val store = remember(context) { TimerRecordStore.get(context) }
    val records by store.records.collectAsStateWithLifecycle()
    val loading by store.isLoading.collectAsStateWithLifecycle()
    val error by store.error.collectAsStateWithLifecycle()
    val candidates = remember(records) { StopwatchRecovery.candidates(records) }
    var showRecovery by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { showRecovery = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                loading -> "继续计时 · 正在读取"
                error != null -> "继续计时 · 读取失败"
                else -> "继续计时 · ${candidates.size}项"
            }, modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.titleMedium)
        }
    }
    if (showRecovery) StopwatchRecoveryDialog(records, loading, error, store::reload) { showRecovery = false }
}

/** The same unfiltered candidate list/details is used on home and in history. Selection alone never starts. */
@Composable
internal fun StopwatchRecoveryDialog(records: List<TimerRecord>, loading: Boolean, storageError: String?,
                                     onRetry: () -> Unit, onDismiss: () -> Unit) {
    val candidates = remember(records) { StopwatchRecovery.candidates(records) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = StopwatchRecoveryPresentation.selected(records, selectedId, !loading && storageError == null)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("继续计时") }, text = {
        when {
            loading -> Text("正在读取已保存的进度…")
            storageError != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("进度读取失败，暂不能继续计时。", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("重试读取") }
            }
            candidates.isEmpty() -> Text("暂无可续计的正计时。正常结束或缺少可靠检查点的记录不能续计。")
            else -> LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(candidates, key = { it.id }) { row ->
                    Column {
                        Row(Modifier.fillMaxWidth().selectable(selected = selectedId == row.id,
                            role = Role.RadioButton, onClick = { selectedId = row.id }).heightIn(min = 64.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedId == row.id, onClick = null)
                            Column(Modifier.weight(1f)) {
                                Text(StopwatchRecoveryPresentation.name(row), style = MaterialTheme.typography.titleSmall)
                                Text("${StopwatchRecoveryPresentation.origin(row).label} · 累计约 ${formatElapsed(row.displayElapsedMillis)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${row.stopwatchBehavior.label} · ${recoveryCheckpointTime(row)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        selected?.takeIf { it.id == row.id }?.let { chosen ->
                            Column(Modifier.padding(start = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                StopwatchRecoveryAction(chosen)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

/** Both entry points use the same ID-only service admission; permission return never auto-starts. */
@Composable
internal fun StopwatchRecoveryAction(record: TimerRecord, readable: Boolean = true) {
    val context=LocalContext.current
    val session by FloatingService.session.collectAsStateWithLifecycle()
    val pending by FloatingService.pendingStarts.collectAsStateWithLifecycle()
    val allowed by rememberOverlayPermission()
    var message by remember(record.id) { mutableStateOf<String?>(null) }
    Text("来源 · ${StopwatchRecoveryPresentation.origin(record).label}${if (record.stopwatch?.continuationOf != null) " · 续计段" else ""}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("累计约 ${formatElapsed(record.displayElapsedMillis)} · ${record.stopwatchBehavior.label}",
        style=MaterialTheme.typography.bodyMedium)
    record.stopwatch?.takeIf { it.carriedMillis > 0 }?.let {
        Text("其中承接 ${formatElapsed(it.carriedMillis)}；新一段只记录新增时长。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("最后保存 ${recoveryCheckpointTime(record)}",
        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Text("约计：停机时间不计，最后未保存的进度可能少记。",style=MaterialTheme.typography.bodySmall,
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

private fun recoveryCheckpointTime(record: TimerRecord): String = java.time.format.DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault())
    .format(java.time.Instant.ofEpochMilli(record.endedWallMillis))
