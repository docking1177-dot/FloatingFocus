package app.floatingfocus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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

@Composable
internal fun QuickDurationsScreen(values: List<Int>, onSave: (List<Int>) -> Unit, onBack: () -> Unit) {
    var first by rememberSaveable { mutableStateOf(values[0].toString()) }
    var second by rememberSaveable { mutableStateOf(values[1].toString()) }
    var third by rememberSaveable { mutableStateOf(values[2].toString()) }
    var fourth by rememberSaveable { mutableStateOf(values[3].toString()) }
    val fields = listOf(first, second, third, fourth)
    val setters: List<(String) -> Unit> = listOf({ first = it }, { second = it }, { third = it }, { fourth = it })
    val parsed = fields.mapNotNull { it.toIntOrNull() }
    PreferencePage("常用时长", onBack) {
        Text("首页的四个时长按钮，按此顺序显示。", style = MaterialTheme.typography.bodySmall)
        fields.forEachIndexed { index, value ->
            NumberField("第 ${index + 1} 个时长（分钟）", value, 1..180, setters[index])
        }
        if (parsed.size == 4 && parsed.distinct().size != 4) Text("请设置四个不同的时长。", color = MaterialTheme.colorScheme.error)
        Row {
            TextButton(onClick = { first = "5"; second = "10"; third = "15"; fourth = "30" }) { Text("恢复默认") }
            Button(enabled = QuickDurations.valid(parsed), onClick = { onSave(parsed); onBack() }) { Text("保存") }
        }
    }
}

@Composable
internal fun StrategyScreen(value: ReminderStrategy, onSave: (ReminderStrategy) -> Unit, onBack: () -> Unit) {
    var draft by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.mapSaver(
        save = { ReminderSettingsCodec.encode(ReminderSettings(strategy = it)) },
        restore = { ReminderSettingsCodec.decode(it).strategy })) { mutableStateOf(value) }
    var resetGeneration by rememberSaveable { mutableIntStateOf(0) }
    PreferencePage("提醒策略", onBack) {
        val valid = key(resetGeneration) { strategyEditor(draft) { draft = it } }
        Row {
            TextButton(onClick = { draft = ReminderStrategy(); resetGeneration++ }) { Text("恢复默认") }
            Button(enabled = valid, onClick = { onSave(draft); onBack() }) { Text("保存") }
        }
    }
}

@Composable
internal fun strategyEditor(strategy: ReminderStrategy, onChange: (ReminderStrategy) -> Unit): Boolean {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TriggerMode.entries.forEach { mode ->
            FilterChip(strategy.mode == mode, { onChange(strategy.copy(mode = mode)) }, label = { Text(mode.label) })
        }
    }
    Text("第二级和第三级分别设置；满足多个条件时采用较高级别。归零与超时行为保持原有规则。",
        style = MaterialTheme.typography.bodySmall)
    if (strategy.mode != TriggerMode.PROGRESS) Text("剩余时间超过本次时长时，该条件会立即满足。", style = MaterialTheme.typography.bodySmall)
    val warmValid = nodeEditor("第二级", strategy.warm, strategy.mode) { onChange(strategy.copy(warm = it)) }
    HorizontalDivider()
    val urgentValid = nodeEditor("第三级", strategy.urgent, strategy.mode) { onChange(strategy.copy(urgent = it)) }
    return warmValid && urgentValid
}

@Composable
private fun nodeEditor(label: String, node: ReminderNode, mode: TriggerMode, onChange: (ReminderNode) -> Unit): Boolean {
    var valid = true
    Text(label, style = MaterialTheme.typography.titleMedium)
    if (mode == TriggerMode.MIXED) {
        NodeCondition("$label · 进度条件", node.useProgress) { onChange(node.copy(useProgress = it)) }
        NodeCondition("$label · 剩余时间条件", node.useRemaining) { onChange(node.copy(useRemaining = it)) }
        if (!node.useProgress && !node.useRemaining) Text("该级未启用触发条件", style = MaterialTheme.typography.bodySmall)
    }
    if (mode == TriggerMode.PROGRESS || mode == TriggerMode.MIXED && node.useProgress) {
        valid = validNodeField("$label · 已用进度（%）", node.percent, 0..99) { onChange(node.copy(percent = it)) } && valid
    }
    if (mode == TriggerMode.REMAINING || mode == TriggerMode.MIXED && node.useRemaining) {
        valid = validNodeField("$label · 剩余时间（秒）", node.seconds, 1..10_800) { onChange(node.copy(seconds = it)) } && valid
    }
    return valid
}

@Composable
private fun NodeCondition(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Checkbox, onValueChange = onChange).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(enabled, onCheckedChange = null)
        Text(label)
    }
}

@Composable
private fun validNodeField(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit): Boolean {
    // Editing must not recreate the field on every valid digit and reset its cursor.
    var text by rememberSaveable(label) { mutableStateOf(value.toString()) }
    NumberField(label, text, range) {
        text = it
        it.toIntOrNull()?.takeIf { number -> number in range }?.let(onChange)
    }
    return text.toIntOrNull() in range
}

@Composable
private fun NumberField(label: String, text: String, range: IntRange, onChange: (String) -> Unit) {
    OutlinedTextField(text, { onChange(it.filter(Char::isDigit).take(5)) }, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = text.toIntOrNull() !in range,
        supportingText = { Text("${range.first}—${range.last}") })
}
