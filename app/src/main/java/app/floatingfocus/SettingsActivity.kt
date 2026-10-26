package app.floatingfocus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = ReminderSettingsStore.get(this)
        val presetStore = TimerPresetStore.get(this)
        val presetId = intent.getStringExtra(EXTRA_PRESET_ID)
        setContent { FocusTheme {
            val sharedSettings by store.settings.collectAsStateWithLifecycle()
            val presets by presetStore.presets.collectAsStateWithLifecycle()
            val preset = presets.firstOrNull { it.id == presetId }
            val editing = presetId != null
            var page by rememberSaveable { mutableStateOf(if (intent.getBooleanExtra("open_strategy", false)) "strategy" else if (editing) "appearance" else "home") }
            val back: () -> Unit = { page = if (intent.getBooleanExtra("open_strategy", false)) "strategy" else if (editing) "appearance" else "home" }
            BackHandler(enabled = page != "home" && (!editing || page != "appearance"), onBack = back)
            if (!editing && page == "home") {
                SettingsHome(onBack = { finish() }, onOverlay = { page = "appearance" },
                    onDurations = { page = "durations" }, onStrategy = { page = "strategy" })
                return@FocusTheme
            }
            if (!editing && page == "durations") {
                val durations by QuickDurationsStore.get(this).durations.collectAsStateWithLifecycle()
                QuickDurationsScreen(durations, { QuickDurationsStore.get(this).save(it) }, back)
                return@FocusTheme
            }
            if (editing && preset == null) {
                Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                    Text("方案不存在或已删除。")
                    TextButton(onClick = { finish() }) { Text("返回") }
                }
                return@FocusTheme
            }
            var draft by rememberSaveable(presetId, stateSaver = mapSaver(
                save = { ReminderSettingsCodec.encode(it) }, restore = { ReminderSettingsCodec.decode(it) })) {
                mutableStateOf(preset?.settings ?: sharedSettings)
            }
            var name by rememberSaveable(presetId) { mutableStateOf(preset?.name.orEmpty()) }
            var targetPackage by rememberSaveable(presetId) { mutableStateOf(preset?.targetApp?.packageName.orEmpty()) }
            var targetLabel by rememberSaveable(presetId) { mutableStateOf(preset?.targetApp?.label.orEmpty()) }
            val target = targetPackage.takeIf { it.isNotBlank() }?.let { LaunchableApp(it, targetLabel) }
            var mode by rememberSaveable(presetId) { mutableStateOf(preset?.mode ?: TimerMode.COUNTDOWN) }
            var seconds by rememberSaveable(presetId) { mutableIntStateOf(preset?.durationSeconds ?: intent.getIntExtra("duration_seconds", 900)) }
            val settings = if (editing) draft else sharedSettings
            val updateSettings: ((ReminderSettings) -> ReminderSettings) -> Unit = { change ->
                if (editing) draft = change(draft) else store.update(change)
            }
            if (page == "strategy") {
                StrategyScreen(settings.strategy, seconds, { strategy -> updateSettings { it.copy(strategy = strategy) } }, back)
                return@FocusTheme
            }
            Scaffold { insets ->
                BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
                    val previewHeight = minOf(184.dp, maxHeight * 0.3f)
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (editing) "编辑方案" else "悬浮窗", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { if (editing) finish() else back() }) { Text(if (editing) "取消" else "返回") }
                            if (editing) TextButton(enabled = isSavedShortcut(target) && name.isNotBlank() && (mode == TimerMode.STOPWATCH || seconds in 60..10800), onClick = {
                                presetStore.save(preset!!.copy(name = name.trim(), minutes = (seconds / 60).coerceAtLeast(1), durationSeconds = seconds,
                                    settings = draft, mode = mode, targetApp = target))
                                finish()
                            }) { Text("保存") }
                        }
                        SettingsPreview(settings, previewHeight, seconds * 1000L, if (editing) mode else TimerMode.COUNTDOWN)
                        HorizontalDivider()
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (editing) {
                                ShortcutAppPicker(target) { targetPackage = it.packageName; targetLabel = it.label }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TimerMode.entries.forEach { option ->
                                        FilterChip(mode == option, { mode = option }, label = { Text(option.label) })
                                    }
                                }
                                OutlinedTextField(name, { name = it.take(40) }, label = { Text("方案名称") }, singleLine = true)
                                if (mode == TimerMode.COUNTDOWN) DurationEntry("计时时长", seconds, { seconds = it })
                                TextButton(onClick = { page = "strategy" }) { Text("提醒策略") }
                            }
                            Text("悬浮窗形状", style = MaterialTheme.typography.titleMedium)
                            OverlayShapePicker(settings.shape) { shape -> updateSettings { it.copy(shape = shape) } }
                            Text("基础颜色", style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OverlayColor.entries.forEach { color ->
                                    FilterChip(settings.color == color, { updateSettings { it.copy(color = color) } },
                                        label = { Text(color.label) }, leadingIcon = {
                                            Box(Modifier.size(20.dp).background(Color(color.argb), CircleShape))
                                        })
                                }
                            }
                            Text("提醒效果", style = MaterialTheme.typography.titleMedium)
                            EffectSwitch("变色", settings.colorEnabled) { enabled -> updateSettings { it.copy(colorEnabled = enabled) } }
                            if (settings.colorEnabled) EffectModePicker("变色方式", settings.colorMode) { mode -> updateSettings { it.copy(colorMode = mode) } }
                            EffectSwitch("变尺寸", settings.sizeEnabled) { enabled -> updateSettings { it.copy(sizeEnabled = enabled) } }
                            if (settings.sizeEnabled) EffectModePicker("变尺寸方式", settings.sizeMode) { mode -> updateSettings { it.copy(sizeMode = mode) } }
                            EffectSlider("提醒强度", settings.intensity, 0f..1f) { v -> updateSettings { it.copy(intensity = v) } }
                            Text("强度为 0 时不变色、不放大、不震动。震动遵循系统限制。", style = MaterialTheme.typography.bodySmall)
                            EffectSlider("基础大小", settings.baseSize, 0.75f..1.5f) { v -> updateSettings { it.copy(baseSize = v) } }
                            EffectSlider("不透明度", settings.opacity, 0.3f..1f) { v -> updateSettings { it.copy(opacity = v) } }
                            Text("不透明度同时作用于背景、文字和图标。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } }
    }
    companion object { const val EXTRA_PRESET_ID = "preset_id" }
}

@Composable
private fun SettingsHome(onBack: () -> Unit, onOverlay: () -> Unit, onDurations: () -> Unit, onStrategy: () -> Unit) {
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Box(Modifier.fillMaxWidth().height(64.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = "返回", modifier = Modifier.rotate(180f))
                }
                Text("设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    listOf("悬浮窗" to onOverlay, "常用时长" to onDurations, "提醒策略" to onStrategy).forEachIndexed { index, (label, action) ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                        Row(Modifier.fillMaxWidth().clickable(onClick = action).heightIn(min = 64.dp).padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectSwitch(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = onChange)
        .heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = null)
    }
}

/** Local visual sample only: never starts a service, changes a session or requests vibration. */
@Composable
private fun SettingsPreview(settings: ReminderSettings, height: Dp, durationMillis: Long, mode: TimerMode) {
    var progress by rememberSaveable { mutableFloatStateOf(0f) }
    val snapshot = if (mode == TimerMode.STOPWATCH) ReminderSnapshot(0, 0f, mode = mode, elapsedMillis = (progress * durationMillis).toLong())
        else ReminderSnapshot(((1f - progress) * durationMillis).toLong(), progress, durationMillis = durationMillis)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        // Measure at natural height before fitting: a short landscape preview must not
        // squeeze the real overlay's text measurement into a tiny circle.
        Layout(modifier = Modifier.fillMaxWidth().height(height), content = {
            FocusOverlay(ReminderPresenter.present(snapshot, settings), interactive = true, controlsEnabled = false, onStop = {})
        }) { measurables, constraints ->
            val overlay = measurables.single().measure(Constraints(maxWidth = constraints.maxWidth))
            val width = constraints.maxWidth
            val heightPx = constraints.maxHeight
            val fit = minOf(1f, width.toFloat() / overlay.width.coerceAtLeast(1),
                heightPx.toFloat() / overlay.height.coerceAtLeast(1))
            layout(width, heightPx) {
                overlay.placeRelativeWithLayer((width - overlay.width) / 2, (heightPx - overlay.height) / 2) {
                    scaleX = fit
                    scaleY = fit
                }
            }
        }
        Text("预览进度 · ${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
        Slider(progress, { progress = it }, modifier = Modifier.semantics { contentDescription = "预览进度" })
    }
}

@Composable
private fun EffectModePicker(label: String, current: ChangeMode, onChange: (ChangeMode) -> Unit) {
    Text(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(ChangeMode.CONTINUOUS to "连续变化", ChangeMode.STEPS to "节点突变").forEach { (mode, title) ->
            FilterChip(current == mode, { onChange(mode) }, label = { Text(title) },
                modifier = Modifier.semantics { contentDescription = "$label：$title" })
        }
    }
}

@Composable
private fun EffectSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text("$label：${"%.2f".format(Locale.ROOT, value)}")
    Slider(value, onChange, valueRange = range, modifier = Modifier.semantics { contentDescription = label })
}
