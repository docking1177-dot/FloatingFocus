package app.floatingfocus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun QuickDurationsScreen(values: List<Int>, defaults: TimerStartupDefaults,
                                 onSave: (List<Int>, TimerStartupDefaults) -> Unit, onBack: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf(values) }
    var startupMode by rememberSaveable { mutableStateOf(defaults.mode) }
    var startupSeconds by rememberSaveable { mutableIntStateOf(defaults.durationSeconds) }
    PreferencePage("常用时长", onBack) {
        draft.forEachIndexed { i, seconds ->
            DurationEntry("第 ${i + 1} 个时长", seconds, { next -> draft = draft.toMutableList().also { it[i] = next } })
        }
        if (!QuickDurations.validSeconds(draft)) Text("请设置四个不同的时长。", color = MaterialTheme.colorScheme.error)
        HorizontalDivider()
        Text("启动默认配置", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimerMode.entries.forEach { option ->
                Row(Modifier.weight(1f).selectable(selected = startupMode == option, role = Role.RadioButton,
                    onClick = { startupMode = option }).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = startupMode == option, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(option.label)
                }
            }
        }
        if (startupMode == TimerMode.COUNTDOWN)
            DurationEntry("默认倒计时时长", startupSeconds, { startupSeconds = it }, minSeconds = 1)
        Text("打开首页时采用；返回或恢复页面、计时中保留当前选择，不自动开始。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            TextButton(onClick = { draft = QuickDurations.defaultSeconds }) { Text("恢复默认") }
            Button(enabled = QuickDurations.validSeconds(draft), onClick = {
                onSave(draft, TimerStartupDefaults(startupMode, startupSeconds)); onBack()
            }) { Text("保存") }
        }
    }
}

@Composable
internal fun StrategyScreen(value: ReminderStrategy, durationSeconds: Int, onSave: (ReminderStrategy) -> Unit, onBack: () -> Unit) {
    var draft by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.mapSaver(
        save = { ReminderSettingsCodec.encode(ReminderSettings(strategy = it)) },
        restore = { ReminderSettingsCodec.decode(it).strategy })) { mutableStateOf(value) }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val duration = durationSeconds * 1000L
    val error = draft.validationError(duration)
    PreferencePage("提醒策略", onBack) {
        Text("本次时长 · ${durationLabel(durationSeconds)}")
        Text("拖动节点调整；点击节点设置触发条件。", style = MaterialTheme.typography.bodySmall)
        if (error == null) StrategyTimeline(draft, duration, { draft = it }, { selected = it })
        else {
            // Invalid legacy/shortened-duration configurations have no honest ordered timeline.
            // Keep identities editable instead of sorting or moving the user's nodes.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { selected = 0 }) { Text("修改第二级") }
                OutlinedButton(onClick = { selected = 1 }) { Text("修改第三级") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("归零和超时提醒保持不变。", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { draft = ReminderStrategy() }) { Text("恢复默认") }
            Button(enabled = error == null, onClick = { onSave(draft); onBack() }) { Text("保存") }
        }
    }
    selected?.let { index ->
        NodeDialog(index, draft, duration, { selected = null }) { draft = it; selected = null }
    }
}

@Composable
private fun StrategyTimeline(strategy: ReminderStrategy, duration: Long, onChange: (ReminderStrategy) -> Unit, onSelect: (Int) -> Unit) {
    val current by rememberUpdatedState(strategy)
    val update by rememberUpdatedState(onChange)
    BoxWithConstraints(Modifier.fillMaxWidth().height(188.dp)) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val trackWidth = (widthPx - with(LocalDensity.current) { 24.dp.toPx() }).coerceAtLeast(1f)
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).offset(y = 90.dp).height(4.dp)
            .background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
        listOf(strategy.warm, strategy.urgent).forEachIndexed { index, node ->
            val fraction = strategy.position(node, duration).coerceIn(0.0, 1.0).toFloat()
            val density = LocalDensity.current
            val x = 12.dp + with(density) { (fraction * trackWidth).toDp() }
            Box(Modifier.offset(x = x - 6.dp, y = 86.dp).size(12.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            val n = strategy.singleNode(node)
            val caption = when {
                n.useProgress == n.useRemaining -> "选择触发条件"
                n.useProgress -> "${n.percent}%"
                else -> "剩余 ${durationLabel(n.seconds)}"
            }
            var dragFraction by remember(index) { mutableDoubleStateOf(0.0) }
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.offset(x = (x - 66.dp).coerceIn(0.dp, (maxWidth - 132.dp).coerceAtLeast(0.dp)),
                    y = if (index == 0) 16.dp else 108.dp).width(132.dp)
                    .clickable { onSelect(index) }
                    .pointerInput(index, duration, trackWidth) {
                        detectDragGestures(onDragStart = {
                            dragFraction = current.position(if (index == 0) current.warm else current.urgent, duration)
                        }) { change, delta ->
                            change.consume()
                            dragFraction = (dragFraction + delta.x / trackWidth).coerceIn(0.0, 1.0)
                            update(current.moved(index == 0, dragFraction, duration))
                        }
                    }) {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (index == 0) "第二级" else "第三级", style = MaterialTheme.typography.labelLarge)
                    Text(caption, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0%", style = MaterialTheme.typography.labelSmall); Text("100%", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun NodeDialog(index: Int, strategy: ReminderStrategy, duration: Long, onDismiss: () -> Unit, onSave: (ReminderStrategy) -> Unit) {
    val initial = strategy.singleNode(if (index == 0) strategy.warm else strategy.urgent)
    var progress by rememberSaveable { mutableStateOf(initial.useProgress && !initial.useRemaining) }
    var percent by rememberSaveable { mutableStateOf(initial.percent.toString()) }
    var seconds by rememberSaveable { mutableIntStateOf(initial.seconds) }
    val node = initial.copy(percent = percent.toIntOrNull()?.coerceIn(0, 99) ?: initial.percent,
        seconds = seconds, useProgress = progress, useRemaining = !progress)
    val next = strategy.copy(mode = TriggerMode.MIXED,
        warm = if (index == 0) node else strategy.singleNode(strategy.warm),
        urgent = if (index == 1) node else strategy.singleNode(strategy.urgent))
    val error = next.validationError(duration)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (index == 0) "第二级提醒" else "第三级提醒") }, text = {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(progress, { progress = true }, label = { Text("进度百分比") })
                FilterChip(!progress, { progress = false }, label = { Text("剩余时间") })
            }
            if (progress) OutlinedTextField(percent, { percent = it.filter(Char::isDigit).take(2) },
                label = { Text("进度（0—99%）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            else DurationWheels(seconds, { seconds = it }, minSeconds = 1)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(enabled = (!progress || percent.toIntOrNull() in 0..99), onClick = {
        // Keep invalid drafts editable across both nodes; the page cannot save/start until resolved.
        onSave(next)
    }) { Text("确认") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun PreferencePage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("返回") }
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}
