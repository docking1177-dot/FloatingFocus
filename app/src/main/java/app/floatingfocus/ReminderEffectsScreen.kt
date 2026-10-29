package app.floatingfocus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ReminderEffectsScreen(settings: C4Settings, onChange: (C4Settings) -> Unit, onStrategy: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { C4SettingsStore.get(context) }
    var notice by remember { mutableStateOf(false) }
    val mediaAvailable = remember { C4Audio.available(context) }
    val session by FloatingService.session.collectAsStateWithLifecycle()
    PreferencePage("提醒", onBack) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onStrategy), verticalAlignment = Alignment.CenterVertically) {
            Text("提醒策略", modifier = Modifier.weight(1f)); Text(">")
        }
        HorizontalDivider()
        Text("末段特效", style = MaterialTheme.typography.titleMedium)
        C4Switch("C4 倒计时", settings.enabled) {
            if (it && !store.noticeAcknowledged) notice = true else onChange(settings.copy(enabled = it))
        }
        if (settings.enabled) {
            C4Switch("音效", settings.sound) { onChange(settings.copy(sound = it)) }
            Text("使用媒体音量；静音、振动模式或勿扰时不播放。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("仅倒计时最后 44.439 秒生效，正计时不触发；含下包过程，下包完成后输入 7355608 结束计时。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("C4 阶段不提供暂停或加时；C4 开关对下一次会话生效。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!mediaAvailable) Text("本构建未包含 C4 音轨，视觉与键盘仍可使用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (session.active) Text(if (session.c4Locked) "本次 C4 仍在继续；开关修改对下一次会话生效。"
            else "C4 开关修改对下一次会话生效。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (notice) AlertDialog(onDismissRequest = { notice = false }, title = { Text("开启 C4 倒计时") }, text = {
        Text("C4 阶段点击浮窗展开装置，需输入 7355608 结束计时。下包完成（音轨 4.92 秒）后可输入；* 退格，# 清空，第七位自动校验。\n\n" +
            "轻触显示屏可收起，键盘区域不切换形态。阶段内首页与通知也不提供正常结束、暂停或加时。系统强停、划掉 FF 或撤销悬浮权限仍会停止。自然到时播放一次爆炸，尾音后恢复超时计时。\n\n" +
            "更改 C4 开关对下一次会话生效，音效开关可即时静音。", modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = {
        store.acknowledgeNotice(); onChange(settings.copy(enabled = true)); notice = false
    }) { Text("开启") } }, dismissButton = { TextButton(onClick = { notice = false }) { Text("取消") } })
}

@Composable
private fun C4Switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f)); Switch(checked, onCheckedChange = null)
    }
}
