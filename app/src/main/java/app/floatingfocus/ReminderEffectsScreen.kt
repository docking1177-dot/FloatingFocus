package app.floatingfocus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun ReminderEffectsScreen(settings: C4Settings, onChange: (C4Settings) -> Unit, onStrategy: () -> Unit, onBack: () -> Unit) {
    PreferencePage("提醒", onBack) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onStrategy), verticalAlignment = Alignment.CenterVertically) {
            Text("提醒策略", modifier = Modifier.weight(1f)); Text(">")
        }
        HorizontalDivider()
        Text("末段特效", style = MaterialTheme.typography.titleMedium)
        C4Switch("C4 倒计时", settings.enabled) { onChange(settings.copy(enabled = it)) }
        if (settings.enabled) {
            C4Switch("音效", settings.sound) { onChange(settings.copy(sound = it)) }
            Text("使用媒体音量；静音、振动模式或勿扰时不播放。", style = MaterialTheme.typography.bodySmall)
            Text("最后 40 秒生效；可点击浮窗展开暂停。", style = MaterialTheme.typography.bodySmall)
            Text("使用原创音效与近似节奏，尚未验证 CS2 原版精确时间轴。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun C4Switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f)); Switch(checked, onCheckedChange = null)
    }
}
