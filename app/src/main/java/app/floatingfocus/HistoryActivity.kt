package app.floatingfocus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class HistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = TimerRecordStore.get(this)
        setContent { FocusTheme {
            val records by store.records.collectAsStateWithLifecycle()
            val storageError by store.error.collectAsStateWithLifecycle()
            var message by remember { mutableStateOf<String?>(null) }
            var exporting by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                if (uri != null) {
                    val snapshot = store.records.value.toList()
                    exporting = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val stream = contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open output")
                                stream.bufferedWriter(Charsets.UTF_8).use { it.write(TimerRecordCsv.encode(snapshot)) }
                            }
                            message = "已导出 ${snapshot.size} 条记录"
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { message = "导出失败，请重新选择保存位置。" }
                        finally { exporting = false }
                    }
                }
            }
            val timeFormat = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()) }
            Scaffold { insets ->
                Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 24.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("时长记录", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { finish() }) { Text("返回") }
                    }
                    Text("记录 FF 计时会话时长；离开目标 App 后仍计时。异常中断按最后保存的进度约计。",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = records.isNotEmpty() && !exporting, onClick = {
                        export.launch("FloatingFocus-${java.time.LocalDate.now()}.csv")
                    }) { Text(if (exporting) "正在导出…" else "导出 CSV") }
                    (storageError ?: message)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (records.isEmpty()) Text("暂无记录")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(records, key = { it.id }) { record ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(record.appName, style = MaterialTheme.typography.titleMedium)
                                    Text(timeFormat.format(Instant.ofEpochMilli(record.startedWallMillis)), style = MaterialTheme.typography.bodySmall)
                                    Text("${record.mode.label} · 累计 ${formatElapsed(record.elapsedMillis)}")
                                    if (record.mode == TimerMode.COUNTDOWN) Text(
                                        "计划 ${formatElapsed(record.plannedMillis)} · 超时 ${formatElapsed(record.overtimeMillis)}",
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(record.status.label, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        } }
    }
}
