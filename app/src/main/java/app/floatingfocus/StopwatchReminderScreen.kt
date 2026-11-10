package app.floatingfocus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Shared with the independent preset draft; no store, timer or audio side effects here. */
@Composable
internal fun StopwatchRulesEditor(rules: StopwatchReminderRules,onChange: (StopwatchReminderRules)->Unit,
    selected: StopwatchLevel? = null,onSelect: ((StopwatchLevel)->Unit)? = null,
    onPreview: (StopwatchLevel, StopwatchRule, OvertimeEffect?) -> Unit = { _, _, _ -> }) {
    var ownSelection by rememberSaveable { mutableStateOf(StopwatchLevel.PRIMARY.name) }
    val level=selected ?: StopwatchLevel.entries.first { it.name==ownSelection }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        StopwatchLevel.entries.forEach { option ->
            FilterChip(level==option,{ ownSelection=option.name;onSelect?.invoke(option) },label={ Text(option.label) })
        }
    }
    val rule=rules.rule(level)
    fun change(value: StopwatchRule, explicitEffect: OvertimeEffect? = null) {
        onChange(rules.changed(level,value))
        onPreview(level,value,explicitEffect)
    }
    EffectSwitch("启用${level.label}",rule.enabled) { change(rule.copy(enabled=it)) }
    DurationEntry("累计运行间隔",rule.intervalSeconds,{ change(rule.copy(intervalSeconds=it)) })
    Text("每累计运行 ${durationLabel(rule.intervalSeconds)} 提醒一次",style=MaterialTheme.typography.bodySmall,
        color=MaterialTheme.colorScheme.onSurfaceVariant)
    OvertimeEffect.entries.forEach { effect ->
        EffectSwitch(if(effect==OvertimeEffect.STATUS) "累计状态" else effect.label,effect in rule.effects) { checked ->
            change(rule.copy(effects=if(checked) rule.effects+effect else rule.effects-effect),if(checked) effect else null)
        }
    }
    Text("颜色效果",style=MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        StopwatchColorMode.entries.forEach { mode ->
            FilterChip(rule.color.mode==mode,{ change(rule.copy(color=rule.color.copy(mode=mode))) },label={ Text(mode.label) })
        }
    }
    if(rule.color.mode==StopwatchColorMode.TRANSITION || rule.color.mode==StopwatchColorMode.ALTERNATING) {
        StopwatchColorPicker("颜色 A",rule.color.a) { change(rule.copy(color=rule.color.copy(a=it))) }
        StopwatchColorPicker("颜色 B",rule.color.b) { change(rule.copy(color=rule.color.copy(b=it))) }
    }
    if(rule.color.mode!=StopwatchColorMode.NONE) Text("约 1.6 秒后恢复原色；仅改变背景颜色。",style=MaterialTheme.typography.bodySmall,
        color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(rule.effects.isEmpty() && rule.color.mode==StopwatchColorMode.NONE) Text("未选择效果，该级不会发出提醒。",style=MaterialTheme.typography.bodySmall,
        color=MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StopwatchColorPicker(label: String,current: Int,onChange: (Int)->Unit) {
    Text(label,style=MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        val labels=listOf("蓝", "黄", "绿", "红", "紫", "青")
        StopwatchColorSettings.PALETTE.forEachIndexed { index, color ->
            FilterChip(current==color,{ onChange(color) }, label={ Text(labels[index]) },
                leadingIcon={ Box(Modifier.size(16.dp).background(Color(color),CircleShape)) })
        }
    }
}

@Composable
internal fun StopwatchReminderScreen(onBack: ()->Unit) {
    val context=LocalContext.current
    val store=remember { StopwatchReminderSettingsStore.get(context) }
    val rules by store.rules.collectAsStateWithLifecycle()
    val permissions by store.permissions.collectAsStateWithLifecycle()
    val error by store.error.collectAsStateWithLifecycle()
    val appearance by ReminderSettingsStore.get(context).settings.collectAsStateWithLifecycle()
    var selection by rememberSaveable { mutableStateOf(StopwatchLevel.PRIMARY.name) }
    val level=StopwatchLevel.entries.first { it.name==selection }
    val (preview,showPreview)=rememberStopwatchPreview()
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=FocusSpacing.page),verticalAlignment=Alignment.CenterVertically) {
            Text("正计时提醒",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
            TextButton(onClick=onBack) { Text("返回") }
        }
        val elapsed=rules.rule(level).intervalSeconds*1000L
        OverlayPreview(180.dp) {
            FocusOverlay(ReminderPresenter.present(ReminderSnapshot(0,0f,0,TimerMode.STOPWATCH,elapsed),appearance),
                interactive=true,controlsEnabled=false,stopwatchCue=preview.cue,reserveStopwatchStatus=OvertimeEffect.STATUS in rules.rule(level).effects,
                initialExpanded=false,onStop={})
        }
        if(preview.feedback.isNotEmpty()) Text(preview.feedback,style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=FocusSpacing.page))
        HorizontalDivider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(FocusSpacing.page),
            verticalArrangement=Arrangement.spacedBy(FocusSpacing.small)) {
            Text("各级独立重复；同点只发最高级。暂停不累计。",style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("周期效果采用标准强度，独立于浮窗渐进强度。",style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            StopwatchRulesEditor(rules,store::updateRules,level,{ selection=it.name;preview.clear() },showPreview)
            TextButton(onClick={
                showPreview(level,rules.rule(level),null)
            }) { Text("预览视觉") }
            HorizontalDivider()
            Text("声音与震动总许可",style=MaterialTheme.typography.titleMedium)
            EffectSwitch("允许短提示音",permissions.sound) { store.setPermissions(permissions.copy(sound=it)) }
            EffectSwitch("允许周期震动",permissions.vibration) { store.setPermissions(permissions.copy(vibration=it)) }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={ showPreview(level,rules.rule(level),OvertimeEffect.SOUND) }) { Text("试听提示音") }
                TextButton(onClick={ showPreview(level,rules.rule(level),OvertimeEffect.VIBRATION) }) { Text("测试震动") }
            }
            Text("总许可立即生效，不随预设保存；仍受静音、勿扰及媒体音量限制。",style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("规则用于下次普通计时和快捷 App 启动；预设使用保存规则，异常续计沿用原规则。",style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
