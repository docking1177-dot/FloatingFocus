package app.floatingfocus

import android.os.Bundle
import android.widget.Toast
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
            val durationsEntry = !editing && intent.getStringExtra("settings_page") == "durations"
            var page by rememberSaveable { mutableStateOf(if (intent.getBooleanExtra("open_strategy", false)) "strategy" else if (editing) "appearance" else intent.getStringExtra("settings_page")?.takeIf { it == "durations" } ?: "home") }
            val back: () -> Unit = {
                if (durationsEntry && page == "durations") finish()
                else page = if (editing) "appearance" else if (page == "strategy") "reminders" else "home"
            }
            BackHandler(enabled = page != "home" && page != "tutorial" && (!editing || page != "appearance"), onBack = back)
            if (!editing && page == "home") {
                SettingsHome(onBack = { finish() }, onOverlay = { page = "appearance" },
                    onReminders = { page = "reminders" }, onOvertime = { page = "overtime" }, onTutorial = { page = "tutorial" },
                    onHelp = { page = "help" }, onLicenses = { page = "licenses" })
                return@FocusTheme
            }
            if (!editing && page == "help") {
                PreferencePage("使用与提醒说明", back) {
                    Text("预设使用保存的配置；快捷 App 使用首页当前时长与外观。")
                    Text("离开 App 后计时仍继续。点击悬浮窗展开操作，拖动可调整位置。")
                    Text("变色、变尺寸可独立开启。普通倒计时到时后显示超时与本次累计，可加时或结束；C4 到时后保留专属外观，只可结束。")
                }
                return@FocusTheme
            }
            if (!editing && page == "licenses") {
                val notices = remember {
                    listOf("THIRD_PARTY_NOTICES.txt", "FloatingCountdownTimer-MIT.txt", "Apache-2.0.txt")
                        .joinToString("\n\n") { name -> assets.open(name).bufferedReader().use { it.readText() } }
                }
                PreferencePage("开源许可", back) { Text(notices, style = MaterialTheme.typography.bodySmall) }
                return@FocusTheme
            }
            if (!editing && page == "tutorial") {
                OnboardingScreen(onComplete = { OnboardingStore.get(this).complete(); finish() }, onExit = back,
                    onRequestPermission = { OverlayPermission.request(this) })
                return@FocusTheme
            }
            if (!editing && page == "reminders") {
                val c4 by C4SettingsStore.get(this).settings.collectAsStateWithLifecycle()
                ReminderEffectsScreen(c4, { C4SettingsStore.get(this).update(it) }, { page = "strategy" }, back)
                return@FocusTheme
            }
            if (!editing && page == "overtime") {
                val overtimeStore = remember { OvertimeReminderSettingsStore.get(this) }
                val overtimeSettings by overtimeStore.settings.collectAsStateWithLifecycle()
                OvertimeReminderScreen(overtimeSettings, overtimeStore::update, back)
                return@FocusTheme
            }
            if (!editing && page == "durations") {
                val durationsStore = QuickDurationsStore.get(this)
                val durations by durationsStore.durations.collectAsStateWithLifecycle()
                val defaults by durationsStore.startupDefaults.collectAsStateWithLifecycle()
                QuickDurationsScreen(durations, defaults, { values, startup -> durationsStore.save(values, startup) }, back)
                return@FocusTheme
            }
            if (editing && preset == null) {
                Column(Modifier.safeDrawingPadding().padding(FocusSpacing.page)) {
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
            var c4Enabled by rememberSaveable(presetId) { mutableStateOf(preset?.c4Enabled ?: false) }
            var timeSource by rememberSaveable(presetId) { mutableStateOf(preset?.timeSource ?: PresetTimeSource.FIXED) }
            var seconds by rememberSaveable(presetId) { mutableIntStateOf(preset?.durationSeconds ?: intent.getIntExtra("duration_seconds", 900)) }
            val quotas = rememberAppQuotas()
            val previewSeconds = if (editing && mode == TimerMode.COUNTDOWN && timeSource == PresetTimeSource.APP_DAILY_QUOTA)
                (quotas.summary(targetPackage)?.remainingMillis?.div(1000)?.toInt() ?: seconds).coerceAtLeast(1) else seconds
            val settings = if (editing) draft else sharedSettings
            val updateSettings: ((ReminderSettings) -> ReminderSettings) -> Unit = { change ->
                if (editing) draft = change(draft) else store.update(change)
            }
            if (page == "strategy") {
                StrategyScreen(settings.strategy, previewSeconds, { strategy -> updateSettings { it.copy(strategy = strategy) } }, back)
                return@FocusTheme
            }
            Scaffold { insets ->
                BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
                    val previewHeight = minOf(184.dp, maxHeight * 0.3f)
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = FocusSpacing.page), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (editing) "编辑方案" else "悬浮窗", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { if (editing) finish() else back() }) { Text(if (editing) "取消" else "返回") }
                            if (editing) TextButton(enabled = isSavedShortcut(target) && name.isNotBlank() && (mode == TimerMode.STOPWATCH || seconds in 1..10800), onClick = {
                                val saved = presetStore.save(preset!!.copy(name = name.trim(), minutes = (seconds / 60).coerceAtLeast(1), durationSeconds = seconds,
                                    settings = draft, mode = mode, targetApp = target, c4Enabled = c4Enabled,
                                    timeSource = if (mode == TimerMode.STOPWATCH) PresetTimeSource.FIXED else timeSource))
                                if (saved) {
                                    if (c4Enabled) C4SettingsStore.get(this@SettingsActivity).acknowledgeNotice()
                                    finish()
                                } else Toast.makeText(this@SettingsActivity, "暂时无法读取预设，保存未完成，请重试。", Toast.LENGTH_LONG).show()
                            }) { Text("保存") }
                        }
                        SettingsPreview(settings, previewHeight, previewSeconds * 1000L, if (editing) mode else TimerMode.COUNTDOWN)
                        HorizontalDivider()
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = FocusSpacing.page, vertical = FocusSpacing.medium),
                            verticalArrangement = Arrangement.spacedBy(FocusSpacing.small)) {
                            if (editing) {
                                ShortcutAppPicker(target) { targetPackage = it.packageName; targetLabel = it.label }
                                Row(horizontalArrangement = Arrangement.spacedBy(FocusSpacing.small)) {
                                    TimerMode.entries.forEach { option ->
                                        FilterChip(mode == option, { mode = option }, label = { Text(option.label) })
                                    }
                                }
                                OutlinedTextField(name, { name = it.take(40) }, label = { Text("方案名称") }, singleLine = true)
                                if (mode == TimerMode.COUNTDOWN) {
                                    PresetTimeSourceSetting(timeSource) { timeSource = it }
                                    if (timeSource == PresetTimeSource.FIXED) DurationEntry("计时时长", seconds, { seconds = it }, minSeconds = 1)
                                    else {
                                        Text(presetTimeLabel(preset!!.copy(targetApp = target, timeSource = timeSource, mode = mode), quotas))
                                        Text("启动时读取该 App 最新余额；在 App 管理中设置每日额度。", style = MaterialTheme.typography.bodySmall)
                                    }
                                } else Text("正计时也计入该 App 已启用的每日额度。", style = MaterialTheme.typography.bodySmall)
                                C4EnabledSetting(c4Enabled) { c4Enabled = it }
                                Text("仅修改此预设；音效由当前设置控制。", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { page = "strategy" }) { Text("提醒策略") }
                            }
                            Text("悬浮窗形状", style = MaterialTheme.typography.titleMedium)
                            OverlayShapePicker(settings.shape) { shape -> updateSettings { it.copy(shape = shape) } }
                            Text("基础颜色", style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(FocusSpacing.small)) {
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
private fun SettingsHome(onBack: () -> Unit, onOverlay: () -> Unit, onReminders: () -> Unit, onOvertime: () -> Unit,
                         onTutorial: () -> Unit, onHelp: () -> Unit, onLicenses: () -> Unit) {
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(64.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = "返回", modifier = Modifier.rotate(180f))
                }
                Text("设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    listOf("悬浮窗" to onOverlay, "提醒" to onReminders, "超时提醒" to onOvertime, "使用教程" to onTutorial,
                        "使用与提醒说明" to onHelp, "开源许可" to onLicenses).forEachIndexed { index, (label, action) ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = FocusSpacing.page))
                        SettingsEntry(label, action)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = FocusSpacing.page))
                    QuickSettingsTileEntry()
                    DeveloperEntry()
                }
            }
        }
    }
}

@Composable
internal fun SettingsEntry(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = FocusSpacing.page, vertical = FocusSpacing.section),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EffectSwitch(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
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
        else ReminderSnapshot.at(Countdown(durationMillis, 0), (progress * durationMillis).toLong())
    Column(Modifier.fillMaxWidth().padding(horizontal = FocusSpacing.page)) {
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(FocusSpacing.small)) {
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
