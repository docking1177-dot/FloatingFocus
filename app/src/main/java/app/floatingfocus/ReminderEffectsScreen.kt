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
    val mediaAvailable = remember { C4Audio.available(context) }
    val session by FloatingService.session.collectAsStateWithLifecycle()
    PreferencePage("提醒", onBack) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onStrategy), verticalAlignment = Alignment.CenterVertically) {
            Text("提醒策略", modifier = Modifier.weight(1f)); Text(">")
        }
        HorizontalDivider()
        Text("末段特效", style = MaterialTheme.typography.titleMedium)
        C4EnabledSetting(settings.enabled) { enabled ->
            if (enabled) store.acknowledgeNotice()
            onChange(settings.copy(enabled = enabled))
        }
        Text("预设使用保存的 C4 设置；当前设置仅用于普通计时和快捷 App 启动。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        C4Switch("音效", settings.sound) { onChange(settings.copy(sound = it)) }
        Text("音效即时作用于全部 C4 会话；使用媒体音量，静音、振动模式或勿扰时不播放。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (settings.enabled) {
            Text("仅倒计时最后 44.439 秒生效。归零前下包完成后输入 7355608 提前结束；归零后可正常结束，不可加时。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("C4 阶段不提供加时。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!mediaAvailable) Text("本构建未包含 C4 音轨，视觉与键盘仍可使用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (session.active) Text("本次会话不受启用状态修改影响。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shared current/preset editor: this component writes only through its supplied callback. */
@Composable
internal fun C4EnabledSetting(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val store = remember { C4SettingsStore.get(context) }
    var notice by remember { mutableStateOf(false) }
    C4Switch("C4 倒计时", enabled) {
        if (it && !store.noticeAcknowledged) notice = true else onChange(it)
    }
    if (enabled) Text("启用 C4 的会话从开始就不可暂停。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (notice) AlertDialog(onDismissRequest = { notice = false }, title = { Text("开启 C4 倒计时") }, text = {
        Text("C4 阶段点击浮窗展开装置，需输入 7355608 结束计时。下包完成（音轨 4.92 秒）后可输入；* 退格，# 清空，第七位自动校验。\n\n" +
            "轻触显示屏可收起，键盘区域不切换形态。本次会话从开始就不能暂停；进入 C4 阶段前仍可直接结束。C4 阶段归零前，首页与通知也不能正常结束，且不能加时。到时播放一次爆炸并保留专属超时外观，# 键改为结束，首页、通知和组件也可结束。主动结束会停止尾音。系统强停、划掉 FF 或撤权仍会停止。\n\n" +
            "启用状态在会话开始时确定；全局音效开关可即时静音。", modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = {
        onChange(true); notice = false
    }) { Text("开启") } }, dismissButton = { TextButton(onClick = { notice = false }) { Text("取消") } })
}

@Composable
private fun C4Switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f)); Switch(checked, onCheckedChange = null)
    }
}
