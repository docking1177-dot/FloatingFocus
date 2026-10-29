package app.floatingfocus

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    var pinMessage by remember { mutableStateOf<String?>(null) }
    val pinFeedback by remember { PresetDesktopShortcuts.get(context) }.feedback.collectAsStateWithLifecycle()
    var pinBusy by remember { mutableStateOf(false) }
    var pinAfterBinding by remember { mutableStateOf<String?>(null) }
    var renameError by remember { mutableStateOf<String?>(null) }

    val addToDesktop: (String) -> Unit = { id ->
        if (!pinBusy) {
            pinBusy = true
            scope.launch {
                try {
                    val result = PresetDesktopShortcuts.get(context).requestPin(id)
                    pinMessage = result.message
                    result.needsBinding?.let { bindingApp = it; pinAfterBinding = it.id }
                } finally { pinBusy = false }
            }
        }
    }
    val pinNotice: @Composable () -> Unit = {
        (if (pinBusy) "正在检查桌面…" else pinFeedback?.message ?: pinMessage)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (pinFeedback?.canOpenSettings == true) TextButton(onClick = {
            try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:" + context.packageName).toUri()))
            } catch (_: RuntimeException) { Toast.makeText(context, "请在系统设置中打开 FF 应用信息，检查快捷方式权限。", Toast.LENGTH_LONG).show() }
        }) { Text("查看应用权限设置") }
    }

    val launch: (TimerPreset) -> Unit = { preset ->
        if (enabled && !busy) {
            error = null
            val check = checkPresetLaunch(context, preset.id)
            launchingPreset = check.preset
            if (check.issue == PresetLaunchIssue.BINDING || check.issue == PresetLaunchIssue.UNAVAILABLE) bindingApp = check.preset
            else if (check.issue == PresetLaunchIssue.PERMISSION) onPermissionRequired()
            else if (check.issue != null) error = check.message
            else {
                busy = true
                onBusyChange(true)
                scope.launch {
                    try {
                        error = launchPresetWithTimer(context, lifecycle, preset.id)
                        if (error == null) check.preset?.let { onApplied(it.durationSeconds, it.mode) }
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
        PresetItems(presets, enabled && !busy, createPreset, launch)
        if (busy) Text("正在启动…", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        pinNotice()
    }
    error?.takeIf { it.contains("提醒策略") }?.let { message -> StrategyConflictDialog(message, launchingPreset?.durationSeconds ?: seconds, launchingPreset?.id, { error = null }) }
    saving?.let { preset -> PresetSaveDialog(preset, { saving = null }) { saved ->
        if (store.save(saved)) saving = null else error = "暂时无法读取预设，保存未完成，请重试。"
    } }
    renaming?.let { preset -> PresetNameDialog("重命名方案", preset.name, { renaming = null }, renameError) { name ->
        val latest = store.snapshot()
        val saved = latest?.firstOrNull { it.id == preset.id }
        if (latest == null) renameError = "暂时无法读取预设，请重试。"
        else if (saved == null) { error = "该预设已删除。"; renaming = null }
        else if (store.save(saved.copy(name = name))) renaming = null
        else renameError = "保存未完成，请重试。"
    } }
    deleting?.let { preset ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除「${preset.name}」？") },
            confirmButton = { TextButton(onClick = { if (!store.delete(preset.id)) error = "暂时无法读取预设，删除未完成，请重试。"; deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    if (managing) AlertDialog(onDismissRequest = { managing = false }, title = { Text("管理预设方案") }, text = {
        Column {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(presets, key = { it.id }) { preset ->
                Column {
                    Text(preset.name, style = MaterialTheme.typography.titleSmall)
                    Text(preset.targetApp?.label ?: "未绑定 App", style = MaterialTheme.typography.bodySmall)
                    Text("${if (preset.mode == TimerMode.STOPWATCH) "正计时" else durationLabel(preset.durationSeconds)} · ${preset.settings.shape.label} · ${preset.settings.color.label}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { renaming = preset; renameError = null; managing = false },
                            modifier = Modifier.semantics { contentDescription = "重命名 ${preset.name}" }) { Text("重命名") }
                        TextButton(onClick = {
                            managing = false
                            context.startActivity(Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PRESET_ID, preset.id))
                        }, modifier = Modifier.semantics { contentDescription = "编辑 ${preset.name}" }) { Text("编辑") }
                        TextButton(onClick = { deleting = preset },
                            modifier = Modifier.semantics { contentDescription = "删除 ${preset.name}" }) { Text("删除") }
                    }
                    TextButton(enabled = !pinBusy, onClick = { addToDesktop(preset.id) },
                        modifier = Modifier.semantics { contentDescription = "添加到桌面 ${preset.name}" }) { Text("添加到桌面") }
                }
            }
        }
        pinNotice()
        }
    }, confirmButton = { TextButton(onClick = { managing = false }) { Text("完成") } })
    bindingApp?.let { preset ->
        PresetSaveDialog(preset, { bindingApp = null; pinAfterBinding = null }, title = "绑定快捷 App") { saved ->
            if (store.save(saved)) {
                bindingApp = null
                if (pinAfterBinding == saved.id) { pinAfterBinding = null; addToDesktop(saved.id) }
            } else error = "暂时无法读取预设，保存未完成，请重试。"
        }
    }
}

@Composable
private fun PresetNameDialog(title: String, initial: String, onDismiss: () -> Unit, error: String? = null, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("方案名称") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
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
