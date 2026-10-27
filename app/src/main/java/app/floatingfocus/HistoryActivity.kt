package app.floatingfocus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

class HistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = TimerRecordStore.get(this)
        setContent { FocusTheme(darkTheme = isSystemInDarkTheme()) {
            val records by store.records.collectAsStateWithLifecycle()
            val loading by store.isLoading.collectAsStateWithLifecycle()
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
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val clock by produceState(HistoryClock(Instant.now(), ZoneId.systemDefault()), lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (isActive) {
                        value = HistoryClock(Instant.now(), ZoneId.systemDefault())
                        // Live data comes from checkpoints. Only refresh calendar boundaries here.
                        delay(30_000)
                    }
                }
            }
            val observedClock = remember(records, clock) { HistoryClock(Instant.now(), ZoneId.systemDefault()) }
            HistoryScreen(records, loading, storageError, observedClock, exporting, message, onBack = { finish() },
                onRetry = store::reload, onDismissMessage = { message = null }, onStartTimer = { finish() }, onExport = {
                    export.launch("FloatingFocus-${clock.now.atZone(clock.zone).toLocalDate()}.csv")
                })
        } }
    }
}

internal data class HistoryClock(val now: Instant, val zone: ZoneId)
