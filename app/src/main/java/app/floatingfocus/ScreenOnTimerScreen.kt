package app.floatingfocus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ScreenOnTimerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { StopwatchBehaviorStore.get(context) }
    val behavior by store.behavior.collectAsStateWithLifecycle()
    PreferencePage("正计时方式", onBack) {
        StopwatchBehaviorSetting(behavior, store::set)
        Text("仅影响下次开始的正计时；预设使用保存的方式。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("划掉 FF 后计时继续。系统或厂商清理仍可能停止进程，可重新打开 FF 从已保存进度续计；停机时间不计入。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun StopwatchBehaviorSetting(value: StopwatchBehavior, onChange: (StopwatchBehavior) -> Unit) {
    Text("正计时方式", style = MaterialTheme.typography.titleMedium)
    StopwatchBehavior.entries.forEach { option ->
        Row(Modifier.fillMaxWidth().selectable(value == option, role = Role.RadioButton,
            onClick = { onChange(option) }).padding(vertical = FocusSpacing.small),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = value == option, onClick = null)
            Column(Modifier.weight(1f).padding(start = FocusSpacing.small)) {
                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                Text(option.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
