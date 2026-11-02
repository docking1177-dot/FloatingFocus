package app.floatingfocus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun OvertimeStylePicker(settings: ReminderSettings) {
    val store = OvertimeStyleStore.get(LocalContext.current)
    val selected by store.style.collectAsState()
    var shape by rememberSaveable { mutableStateOf(settings.shape) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var longSession by rememberSaveable { mutableStateOf(false) }
    Text("超时浮窗布局", style = MaterialTheme.typography.titleMedium)
    Text("仅本机 Debug；累计为 FF 会话时长。预览不启动计时，C4 使用独立显示。", style = MaterialTheme.typography.bodySmall)
    Column(Modifier.selectableGroup()) {
        OvertimeStyle.entries.forEach { option ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected == option, role = Role.RadioButton, onClick = { store.select(option) })
                .semantics { contentDescription = "选择超时布局 ${option.label}" }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == option, onClick = null)
                Text(option.label)
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OverlayShape.entries.forEach { item -> FilterChip(shape == item, { shape = item }, label = { Text(item.label) }) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!expanded, { expanded = false }, label = { Text("折叠预览") })
        FilterChip(expanded, { expanded = true }, label = { Text("展开预览") })
        FilterChip(longSession, { longSession = !longSession }, label = { Text("长时长") })
    }
    val snapshot = if (longSession) ReminderSnapshot.at(Countdown(10_800_000, 0), 28_800_000)
        else ReminderSnapshot.at(Countdown(900_000, 0), 1_200_000)
    Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
        FocusOverlay(ReminderPresenter.present(snapshot, settings.copy(shape = shape)), interactive = false,
            controlsEnabled = false, initialExpanded = expanded, onExtend = {}, onStop = {})
    }
}
