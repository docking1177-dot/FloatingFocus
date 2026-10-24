package app.floatingfocus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            var minutes by rememberSaveable(presetId) { mutableStateOf((preset?.minutes ?: 15).toString()) }
            val settings = if (editing) draft else sharedSettings
            val updateSettings: ((ReminderSettings) -> ReminderSettings) -> Unit = { change ->
                if (editing) draft = change(draft) else store.update(change)
            }
            Scaffold { insets ->
                BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
                    val previewHeight = minOf(184.dp, maxHeight * 0.3f)
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (editing) "编辑方案" else "设置", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { finish() }) { Text(if (editing) "取消" else "返回") }
                            if (editing) TextButton(enabled = name.isNotBlank() && minutes.toIntOrNull() in 1..180, onClick = {
                                presetStore.save(TimerPreset(presetId!!, name.trim(), minutes.toInt(), draft))
                                finish()
                            }) { Text("保存") }
                        }
                        SettingsPreview(settings, previewHeight, (minutes.toIntOrNull()?.coerceIn(1, 180) ?: 15) * 60_000L)
                        HorizontalDivider()
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (editing) {
                                OutlinedTextField(name, { name = it.take(40) }, label = { Text("方案名称") }, singleLine = true)
                                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(3) },
                                    label = { Text("分钟（1—180）") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    isError = minutes.toIntOrNull() !in 1..180)
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
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(ReminderStyle.COLOR to "变色", ReminderStyle.SIZE to "变尺寸").forEach { (style, label) ->
                                    FilterChip(settings.style == style, { updateSettings { it.copy(style = style) } }, label = { Text(label) })
                                }
                            }
                            Text("变色：从基础颜色渐变至橙、红色。变尺寸：保持基础颜色，最大放大至基础大小的 1.5 倍。",
                                style = MaterialTheme.typography.bodySmall)
                            EffectModePicker("变色方式", settings.colorMode) { mode -> updateSettings { it.copy(colorMode = mode) } }
                            EffectModePicker("变尺寸方式", settings.sizeMode) { mode -> updateSettings { it.copy(sizeMode = mode) } }
                            Text("方式仅作用于对应提醒效果；节点为已用时间的 50%、80%、100%。", style = MaterialTheme.typography.bodySmall)
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

/** Local visual sample only: never starts a service, changes a session or requests vibration. */
@Composable
private fun SettingsPreview(settings: ReminderSettings, height: Dp, durationMillis: Long) {
    var progress by rememberSaveable { mutableFloatStateOf(0f) }
    val snapshot = ReminderSnapshot(((1f - progress) * durationMillis).toLong(), progress)
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
