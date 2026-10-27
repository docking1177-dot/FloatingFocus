package app.floatingfocus

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun TimerPresetsCard(seconds: Int, mode: TimerMode = TimerMode.COUNTDOWN, enabled: Boolean, onBusyChange: (Boolean) -> Unit,
                     onPermissionRequired: () -> Unit, onApplied: (Int, TimerMode) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val store = remember { TimerPresetStore.get(context) }
    val presets by store.presets.collectAsStateWithLifecycle()
    var saving by remember { mutableStateOf<TimerPreset?>(null) }
    var renaming by remember { mutableStateOf<TimerPreset?>(null) }
    var deleting by remember { mutableStateOf<TimerPreset?>(null) }
    var bindingApp by remember { mutableStateOf<TimerPreset?>(null) }
    var managing by rememberSaveable { mutableStateOf(false) }
    var launchingPreset by remember { mutableStateOf<TimerPreset?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val launch: (TimerPreset) -> Unit = { preset ->
        if (enabled && !busy) {
            error = null
            launchingPreset = preset
            val app = preset.targetApp
            if (!context.isSavedShortcut(app)) bindingApp = preset
            else if (!Settings.canDrawOverlays(context)) onPermissionRequired()
            else {
                busy = true
                onBusyChange(true)
                scope.launch {
                    try {
                        error = launchAppWithTimer(context, lifecycle, app!!, preset.durationSeconds, preset.settings, preset.mode, preset.id)
                        if (error == null) onApplied(preset.durationSeconds, preset.mode)
                    } finally { busy = false; onBusyChange(false) }
                }
            }
        }
    }

    val createPreset: () -> Unit = {
        saving = TimerPreset(UUID.randomUUID().toString(), if (mode == TimerMode.STOPWATCH) "正计时" else durationLabel(seconds),
            (seconds / 60).coerceAtLeast(1), ReminderSettingsStore.get(context).settings.value, mode, durationSeconds = seconds)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("我的预设", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(enabled = enabled && !busy, onClick = createPreset) { Text("保存当前", style = MaterialTheme.typography.labelMedium) }
            if (presets.isNotEmpty()) TextButton(enabled = enabled && !busy, onClick = { managing = true }) { Text("管理", style = MaterialTheme.typography.labelMedium) }
        }
        (presets + listOf<TimerPreset?>(null)).chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { preset ->
                    key(preset?.id ?: "new-preset") {
                        OutlinedButton(enabled = enabled && !busy, onClick = { if (preset == null) createPreset() else launch(preset) },
                            shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(12.dp),
                            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 108.dp)
                                .semantics { contentDescription = if (preset == null) "新建预设" else "启动预设 ${preset.name}" }) {
                            if (preset == null) Column(horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("+", style = MaterialTheme.typography.headlineSmall)
                                Text("新建预设", style = MaterialTheme.typography.bodySmall)
                            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(preset.name, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (preset.targetApp?.label != preset.name) Text(preset.targetApp?.label ?: "选择 App",
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (preset.mode == TimerMode.STOPWATCH) "正计时" else durationLabel(preset.durationSeconds),
                                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("点击启动", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (busy) Text("正在启动…", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    error?.takeIf { it.contains("提醒策略") }?.let { message -> StrategyConflictDialog(message, launchingPreset?.durationSeconds ?: seconds, launchingPreset?.id, { error = null }) }
    saving?.let { preset -> PresetSaveDialog(preset, { saving = null }) { saved ->
        store.save(saved); saving = null
    } }
    renaming?.let { preset -> PresetNameDialog("重命名方案", preset.name, { renaming = null }) { name ->
        presets.firstOrNull { it.id == preset.id }?.let { store.save(it.copy(name = name)) }
        renaming = null
    } }
    deleting?.let { preset ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除「${preset.name}」？") },
            confirmButton = { TextButton(onClick = { store.delete(preset.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    if (managing) AlertDialog(onDismissRequest = { managing = false }, title = { Text("管理预设方案") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(presets, key = { it.id }) { preset ->
                Column {
                    Text(preset.name, style = MaterialTheme.typography.titleSmall)
                    Text(preset.targetApp?.label ?: "未绑定 App", style = MaterialTheme.typography.bodySmall)
                    Text("${if (preset.mode == TimerMode.STOPWATCH) "正计时" else durationLabel(preset.durationSeconds)} · ${preset.settings.shape.label} · ${preset.settings.color.label}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { renaming = preset; managing = false },
                            modifier = Modifier.semantics { contentDescription = "重命名 ${preset.name}" }) { Text("重命名") }
                        TextButton(onClick = {
                            managing = false
                            context.startActivity(Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PRESET_ID, preset.id))
                        }, modifier = Modifier.semantics { contentDescription = "编辑 ${preset.name}" }) { Text("编辑") }
                        TextButton(onClick = { deleting = preset },
                            modifier = Modifier.semantics { contentDescription = "删除 ${preset.name}" }) { Text("删除") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { managing = false }) { Text("完成") } })
    bindingApp?.let { preset ->
        PresetSaveDialog(preset, { bindingApp = null }, title = "绑定快捷 App") { saved ->
            store.save(saved); bindingApp = null
        }
    }
}

@Composable
private fun PresetNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("方案名称") }, singleLine = true)
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun PresetSaveDialog(preset: TimerPreset, onDismiss: () -> Unit,
                             title: String = "保存预设方案", onSave: (TimerPreset) -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable(preset.id) { mutableStateOf(preset.name) }
    var targetPackage by rememberSaveable(preset.id) { mutableStateOf(preset.targetApp?.packageName.orEmpty()) }
    var targetLabel by rememberSaveable(preset.id) { mutableStateOf(preset.targetApp?.label.orEmpty()) }
    val target = targetPackage.takeIf { it.isNotBlank() }?.let { LaunchableApp(it, targetLabel) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("方案名称") }, singleLine = true)
            ShortcutAppPicker(target) { targetPackage = it.packageName; targetLabel = it.label }
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank() && context.isSavedShortcut(target), onClick = {
        if (context.isSavedShortcut(target)) onSave(preset.copy(name = name.trim(), targetApp = target))
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
