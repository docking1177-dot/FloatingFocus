package app.floatingfocus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
internal fun HistoryScreen(records: List<TimerRecord>, loading: Boolean, storageError: String?, clock: HistoryClock,
                           exporting: Boolean, message: String?, onBack: () -> Unit, onRetry: () -> Unit,
                           onDismissMessage: () -> Unit, onStartTimer: () -> Unit, onExport: () -> Unit) {
    var rangeName by rememberSaveable { mutableStateOf(HistoryRange.TODAY.name) }
    var appPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var showApps by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val range = HistoryRange.valueOf(rangeName)
    val period = remember(range, clock.now.atZone(clock.zone).toLocalDate(), clock.zone) {
        HistoryStatistics.period(range, clock.now.atZone(clock.zone).toLocalDate(), clock.zone)
    }
    var selectedBucket by rememberSaveable(period, appPackage) { mutableStateOf<Int?>(null) }
    val apps = remember(records) { HistoryStatistics.apps(records) }
    val app = apps.firstOrNull { it.packageName == appPackage }
    val appLabel = if (appPackage == null) "全部" else app?.label ?: "所选 App"
    val report by produceState<HistoryReport?>(null, records, period, appPackage, clock.now) {
        value = withContext(Dispatchers.Default) { HistoryStatistics.summarize(records, period, appPackage, clock.now) }
    }
    // Never label a previous filter's total as the newly selected App or period.
    val current = report?.takeIf { it.period == period && it.packageFilter == appPackage }
    val scroll = rememberLazyListState()
    var lastFilter by remember { mutableStateOf(period to appPackage) }
    LaunchedEffect(period, appPackage) {
        if (lastFilter != (period to appPackage)) { scroll.scrollToItem(0); lastFilter = period to appPackage }
    }
    val timeFormat = remember(clock.zone) { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(clock.zone) }

    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回") }
                Text("时长记录", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onExport, enabled = records.isNotEmpty() && !loading && !exporting) {
                    Icon(painterResource(R.drawable.ic_export), contentDescription = if (exporting) "正在导出…" else "导出全部记录")
                }
            }
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(FocusSpacing.page),
                verticalArrangement = Arrangement.spacedBy(FocusSpacing.section)) {
                item(key = "filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(FocusSpacing.medium)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FocusSpacing.small)) {
                            HistoryRange.entries.forEach { option ->
                                FilterChip(selected = range == option, onClick = { rangeName = option.name }, label = { Text(option.label) },
                                    modifier = Modifier.weight(1f))
                            }
                        }
                        OutlinedCard(Modifier.fillMaxWidth().clickable { showApps = true }
                            .semantics(mergeDescendants = true) { contentDescription = "筛选 App：$appLabel" }) {
                            Row(Modifier.padding(FocusSpacing.medium).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(FocusSpacing.medium)) {
                                appPackage?.let { AppIcon(it, 28.dp) }
                                Text(appLabel, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                if (storageError != null) item(key = "storage-error") {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(FocusSpacing.medium)) {
                            Text(storageError, color = MaterialTheme.colorScheme.onErrorContainer)
                            if (records.isNotEmpty()) Text("统计仅含成功读取的记录。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onRetry, enabled = !loading) { Text("重试") }
                        }
                    }
                }
                if (message != null) item(key = "export-message") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FocusSpacing.small)) {
                        Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onDismissMessage) { Text("关闭") }
                    }
                }
                if (loading && records.isEmpty() || current == null) item(key = "loading") {
                    Row(Modifier.fillMaxWidth().padding(FocusSpacing.page), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text("正在读取记录…", modifier = Modifier.padding(start = 12.dp))
                    }
                } else if (storageError == null || records.isNotEmpty()) {
                    item(key = "chart") {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(FocusSpacing.section), verticalArrangement = Arrangement.spacedBy(FocusSpacing.small)) {
                                Text(period.caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("$appLabel · ${range.label}累计", style = MaterialTheme.typography.titleSmall)
                                Text(historyDurationLabel(current.totalMillis), style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.SemiBold)
                                if (current.hasRunning) Text("含计时中会话 · 截至最近保存进度", style = MaterialTheme.typography.bodySmall)
                                if (current.hasApproximate) Text("含异常中断约计记录", style = MaterialTheme.typography.bodySmall)
                                if (current.entries.isEmpty()) {
                                    Text(if (records.isEmpty()) "暂无记录" else "此范围暂无记录")
                                    if (records.isEmpty()) TextButton(onClick = onStartTimer) { Text("去计时") }
                                    else if (appPackage != null) TextButton(onClick = { appPackage = null }) { Text("查看全部 App") }
                                } else HistoryChart(current, clock.now.toEpochMilli(), selectedBucket, { selectedBucket = it })
                            }
                        }
                    }
                    if (current.entries.isNotEmpty()) {
                        item(key = "records-heading") {
                            Text("会话记录 · ${current.entries.size}", style = MaterialTheme.typography.titleMedium)
                        }
                        items(current.entries, key = { it.record.id }) { entry ->
                            HistoryRecordCard(entry, range.label, timeFormat)
                        }
                    }
                }
                item(key = "explanation") {
                    TextButton(onClick = { showHelp = true }, modifier = Modifier.fillMaxWidth()) { Text("统计说明") }
                }
            }
        }
    }
    if (showApps) HistoryAppPicker(apps, appPackage, { showApps = false }) { appPackage = it; showApps = false }
    if (showHelp) AlertDialog(onDismissRequest = { showHelp = false }, title = { Text("统计说明") }, text = {
        Text("记录 FF 会话时长，含手动计时；离开目标 App 后仍计时，不代表 App 实际前台使用时间。\n\n" +
            "按设备当前时区及已保存的会话时间轴拆分小时和日期；本周从周一开始。当前时区变化后，历史分布可能变化。\n\n" +
            "现有累计口径包含暂停期间的会话经过时间；未保存暂停区间或系统改时轨迹，图表不推测这些区间。异常中断仅按最后检查点约计，不补入停机时间。\n\n" +
            "导出包含全部原始会话，不受页面筛选影响。", modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } })
}

@Composable
private fun HistoryRecordCard(entry: HistoryEntry, rangeLabel: String, timeFormat: DateTimeFormatter) {
    val record = entry.record
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(FocusSpacing.section), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FocusSpacing.medium)) {
                AppIcon(record.packageName, 32.dp)
                Text(if (record.packageName.isEmpty()) "手动计时" else record.appName, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
            }
            Text(timeFormat.format(Instant.ofEpochMilli(record.startedWallMillis)), style = MaterialTheme.typography.bodySmall)
            Text("${rangeLabel}计时 ${historyDurationLabel(entry.inRangeMillis)}")
            Text("${record.mode.label} · 整次累计 ${historyDurationLabel(record.elapsedMillis)}", style = MaterialTheme.typography.bodySmall)
            if (record.mode == TimerMode.COUNTDOWN) Text("计划 ${historyDurationLabel(record.plannedMillis)} · 超时 ${historyDurationLabel(record.overtimeMillis)}",
                style = MaterialTheme.typography.bodySmall)
            Text(record.status.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryAppPicker(apps: List<HistoryApp>, selected: String?, onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val duplicates = apps.groupingBy { it.label }.eachCount()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择 App") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(FocusSpacing.small)) {
            if (apps.size > 5) OutlinedTextField(query, { query = it }, label = { Text("搜索历史 App") }, singleLine = true)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                item {
                    HistoryAppOption("全部（含手动计时）", null, selected == null, { onSelect(null) })
                }
                val matching = apps.filter { it.label.contains(query, ignoreCase = true) }
                if (matching.isEmpty()) item { Text("没有匹配的 App", modifier = Modifier.padding(FocusSpacing.medium)) }
                items(matching, key = { it.packageName }) { app ->
                    HistoryAppOption(app.label, app.packageName, selected == app.packageName, { onSelect(app.packageName) },
                        app.packageName.takeIf { (duplicates[app.label] ?: 0) > 1 })
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun HistoryAppOption(label: String, pkg: String?, selected: Boolean, onSelect: () -> Unit, distinction: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, onClick = onSelect, role = Role.RadioButton)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FocusSpacing.medium)) {
        RadioButton(selected, onClick = null)
        pkg?.let { AppIcon(it, 28.dp) }
        Column(Modifier.weight(1f)) {
            Text(label)
            distinction?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
