package app.floatingfocus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun OvertimeReminderScreen(settings: OvertimeReminderSettings,
                                    onChange: (OvertimeReminderSettings) -> Unit, onBack: () -> Unit) {
    PreferencePage("超时提醒", onBack) {
        EffectSwitch("启用超时提醒", settings.enabled) { onChange(settings.copy(enabled = it)) }
        Text("仅管理归零后的周期提醒，保留原归零提醒。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        DurationEntry("基准间隔", settings.intervalSeconds, { onChange(settings.copy(intervalSeconds = it)) })
        Text("每轮 ${durationLabel((settings.intervalRange.first / 1000).toInt())}—${durationLabel((settings.intervalRange.last / 1000).toInt())}，排定后不再变化。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("允许的效果", style = MaterialTheme.typography.titleMedium)
        OvertimeEffect.entries.forEach { effect ->
            EffectSwitch(effect.label, effect in settings.effects) { checked ->
                onChange(settings.copy(effects = if (checked) settings.effects + effect else settings.effects - effect))
            }
        }
        if (settings.effects.isEmpty()) Text("未选择效果，不会发出周期提醒。", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        Text("声音与震动分别开启，遵循静音、勿扰及系统限制；短提示音使用媒体音量。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("强度上限", style = MaterialTheme.typography.titleMedium)
        OvertimeLimit.entries.forEach { option ->
            OvertimeRadio(option.label, settings.limit == option) { onChange(settings.copy(limit = option)) }
        }
        Text(when (settings.limit) {
            OvertimeLimit.LIGHT -> "仅视觉，不发出周期声音或震动。"
            OvertimeLimit.STANDARD -> "持续超时后可加入已开启的短声音或震动。"
            OvertimeLimit.NOTICEABLE -> "多次主动延后后加强视觉，不继续提高音量、面积或频率。"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("周期强度独立于普通浮窗的渐进提醒强度。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("交互方式", style = MaterialTheme.typography.titleMedium)
        OvertimeInteraction.entries.forEach { option ->
            OvertimeRadio(option.label, settings.interaction == option) { onChange(settings.copy(interaction = option)) }
        }
        Text(if (settings.interaction == OvertimeInteraction.CHOICE)
            "普通超时浮窗展开后可选择下次提醒，周期提醒不强制展开。C4 请在首页选择；完整装置不变。"
            else "保持轻量；需要调整下一次提醒时，可在首页选择。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("稍后提醒只改变下一次提醒，不加时。明确选择的一次不浮动；系统休眠可能延迟，不连续补发。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("首轮仅视觉；超时达到基准间隔的 3 倍后可达标准，主动延后 3 次后可达明显，始终受上限约束。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("C4 仅折叠态使用兼容视觉效果；展开时跳过视觉，音轨忙时跳过短提示音，不补播。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OvertimeRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

/** The same entry for ordinary and C4 overtime. A saved request keeps its original session/leg. */
@Composable
internal fun OvertimeHomeControls(session: SessionState, onSnooze: (String, Long, Int, String) -> Unit) {
    var requestSession by rememberSaveable { mutableStateOf<String?>(null) }
    var requestLeg by rememberSaveable { mutableLongStateOf(0) }
    var operation by rememberSaveable { mutableStateOf("") }
    var initial by rememberSaveable { mutableIntStateOf(300) }
    val info = session.overtimeReminder
    LaunchedEffect(session.startRequestId, session.timerStartedAt, info != null) {
        if (session.startRequestId != requestSession || session.timerStartedAt != requestLeg || info == null) requestSession = null
    }
    if (info != null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("下次提醒 ${formatRemaining(info.nextInMillis)}", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                requestSession = session.startRequestId; requestLeg = session.timerStartedAt
                initial = info.pickerSeconds; operation = java.util.UUID.randomUUID().toString()
            }) { Text("稍后提醒") }
        }
        if (info.snoozeCount >= 3) Text(
            "计划 ${formatUsageTime(info.plannedMillis)} · 已用 ${formatUsageTime(session.elapsedMillis)} · 延后 ${info.snoozeCount} 次\n计划含已确认加时",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    requestSession?.let { id ->
        DurationDialog("多久后再提醒", initial, { requestSession = null }, { seconds ->
            onSnooze(id, requestLeg, seconds, operation)
            requestSession = null
        })
    }
}
