package app.floatingfocus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Real renderer, inert callbacks. Reading the preview never creates or edits presets. */
@Composable
internal fun PresetStylePicker() {
    val context = LocalContext.current
    val store = remember { PresetStyleStore.get(context) }
    val style by store.style.collectAsStateWithLifecycle()
    val presets by TimerPresetStore.get(context).presets.collectAsStateWithLifecycle()
    val examples = remember {
        val app = LaunchableApp(context.packageName, "Floating Focus")
        listOf(
            TimerPreset("preview-short", "短休息", 5, ReminderSettings(), targetApp = app),
            TimerPreset("preview-long", "同一 App 的另一方案，名称可以换行", 30, ReminderSettings(), targetApp = app),
            TimerPreset("preview-up", "自由计时", 1, ReminderSettings(), TimerMode.STOPWATCH, app))
    }
    Text("预设卡片样式", style = MaterialTheme.typography.titleMedium)
    Text("仅影响本机 Debug 首页；下方预览不可操作。", style = MaterialTheme.typography.bodySmall)
    Column(Modifier.selectableGroup()) {
        PresetStyle.entries.forEach { item ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected = style == item, role = Role.RadioButton, onClick = { store.select(item) })
                .semantics { contentDescription = "选择预设样式 ${item.label}" }.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected = style == item, onClick = null)
                Text(item.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    Text("预览", style = MaterialTheme.typography.labelLarge)
    PresetComparisonItems(style, presets.take(3).ifEmpty { examples }, enabled = false, onCreate = {}, onLaunch = {})
}
