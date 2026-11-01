package app.floatingfocus

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun rememberAppQuotas(): AppQuotaView {
    val store = AppQuotaStore.get(LocalContext.current)
    val view by store.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { store.refreshSoon() }
    return view
}

internal fun presetTimeLabel(preset: TimerPreset, quotas: AppQuotaView? = null): String {
    if (preset.timeSource == PresetTimeSource.FIXED) return if (preset.mode == TimerMode.STOPWATCH) "正计时" else durationLabel(preset.durationSeconds)
    if (quotas == null || quotas.loading) return "今日 App 额度"
    if (quotas.error != null) return "额度读取失败"
    val quota = quotas.summary(preset.targetApp?.packageName.orEmpty())
    if (quota?.enabled != true) return "每日额度未启用"
    return "今日剩余 ${formatRemaining(quota.remainingMillis)}"
}

@Composable
internal fun PresetTimeSourceSetting(source: PresetTimeSource, onChange: (PresetTimeSource) -> Unit) {
    Text("计时来源", style = MaterialTheme.typography.titleSmall)
    PresetTimeSource.entries.forEach { option ->
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(source == option, { onChange(option) })
            TextButton(onClick = { onChange(option) }) {
                Text(if (option == PresetTimeSource.FIXED) "固定时长" else "今日 App 剩余额度")
            }
        }
    }
}

@Composable
internal fun AppQuotaEditor(app: LaunchableApp, onDismiss: () -> Unit) {
    val store = AppQuotaStore.get(LocalContext.current)
    val view = rememberAppQuotas()
    val scope = rememberCoroutineScope()
    if (view.loading || view.ledger == null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("${app.label} · 每日额度") },
            text = { Text(if (view.loading) "正在读取…" else view.error ?: "额度读取失败。") },
            confirmButton = { TextButton(onClick = { store.refreshSoon() }) { Text("重试") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } })
        return
    }
    val quota = view.summary(app.packageName)
    var seconds by rememberSaveable(app.packageName) { mutableIntStateOf((quota?.limitMillis?.div(1000)?.toInt() ?: 7200)) }
    var enabled by rememberSaveable(app.packageName) { mutableStateOf(quota?.enabled ?: true) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("${app.label} · 每日额度") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EffectSwitch("启用每日额度", enabled) { if (!busy) enabled = it }
            DurationEntry("每日总时长", seconds, { seconds = it }, enabled = !busy, minSeconds = 1)
            val used = quota?.usedMillis ?: 0
            Text("已用 ${formatElapsed(used)} · 剩余 ${formatRemaining((seconds * 1000L - used).coerceAtLeast(0))}")
            if (used > seconds * 1000L) Text("额外使用 ${formatElapsed(used - seconds * 1000L)}")
            val ledger = view.ledger
            val date = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of(ledger.zoneId))
            Text("本统计日 ${date.format(Instant.ofEpochMilli(ledger.day.startMillis))}—${date.format(Instant.ofEpochMilli(ledger.nextResetMillis))}",
                style = MaterialTheme.typography.bodySmall)
            Text("仅记录 FF 绑定此 App 的运行时间，暂停不计；首次启用不补扣此前记录。停用或修改额度不清空已用。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            view.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { store.refreshSoon() }) { Text("重试") } }
            ledger.clockWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !busy && view.error == null, onClick = {
            busy = true
            val savedSeconds = seconds; val savedEnabled = enabled
            scope.launch { val saved = store.configure(app.packageName, savedSeconds, savedEnabled); busy = false; if (saved) onDismiss() }
        }) { Text(if (busy) "保存中…" else "保存") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun QuotaResetEntry() {
    val context = LocalContext.current
    val store = AppQuotaStore.get(context)
    val view = rememberAppQuotas()
    val scope = rememberCoroutineScope()
    var choosing by rememberSaveable { mutableStateOf(false) }
    val ledger = view.ledger
    val reset = ledger?.pendingReset ?: ledger?.resetMinute ?: 0
    TextButton(enabled = ledger != null && view.error == null, onClick = { choosing = true }) {
        Text("每日额度重置时间：${String.format(Locale.ROOT, "%02d:%02d", reset / 60, reset % 60)}  >")
    }
    ledger?.let {
        Text("固定记账时区：${it.zoneId}" + if (it.pendingReset != null) " · 新时刻下次重置起生效" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    view.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { store.refreshSoon() }) { Text("重试额度读取") } }
    if (choosing) DisposableEffect(context) {
        val dialog = TimePickerDialog(context, { _, hour, minute ->
            choosing = false
            scope.launch { store.changeReset(hour * 60 + minute) }
        }, reset / 60, reset % 60, true).apply {
            setTitle("每日重置时间")
            setOnCancelListener { choosing = false }
            show()
        }
        onDispose { dialog.dismiss() }
    }
}
