package app.floatingfocus

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** One atomic file per session. Only the current small record is checkpointed, never the full history. */
class TimerRecordStore private constructor(context: Context) {
    private val directory = File(context.filesDir, "timer_records")
    private val io = Executors.newSingleThreadExecutor()
    private val checkpointQueued = AtomicBoolean(false)
    private val current = MutableStateFlow<List<TimerRecord>>(emptyList())
    val records = current.asStateFlow()
    private val problem = MutableStateFlow<String?>(null)
    val error = problem.asStateFlow()

    init {
        io.execute {
            directory.mkdirs()
            val restored = directory.listFiles().orEmpty().filter { it.name.endsWith(".json") }.mapNotNull { file ->
                try {
                    val row = decode(JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() }))
                    val recovered = row.recover()
                    if (recovered != row) write(recovered)
                    recovered
                } catch (e: Exception) { report(e); null }
            }
            current.value = restored.sortedByDescending { it.startedWallMillis }
        }
    }

    fun save(record: TimerRecord, checkpoint: Boolean = false, after: (() -> Unit)? = null) {
        if (checkpoint && !checkpointQueued.compareAndSet(false, true)) return
        io.execute {
            try {
                write(record)
                current.value = (current.value.filterNot { it.id == record.id } + record).sortedByDescending { it.startedWallMillis }
            } catch (e: Exception) { report(e) }
            finally { if (checkpoint) checkpointQueued.set(false); after?.invoke() }
        }
    }

    private fun write(record: TimerRecord) {
        directory.mkdirs()
        val file = AtomicFile(File(directory, "${record.id}.json"))
        val stream = file.startWrite()
        try {
            stream.write(JSONObject().apply {
                put("id", record.id); put("appName", record.appName); put("packageName", record.packageName)
                put("mode", record.mode.name); put("plannedMillis", record.plannedMillis)
                put("startedWallMillis", record.startedWallMillis); put("elapsedMillis", record.elapsedMillis)
                put("status", record.status.name)
            }.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }

    private fun decode(row: JSONObject): TimerRecord {
        val id = row.getString("id")
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        val duration = row.getLong("plannedMillis")
        val elapsed = row.getLong("elapsedMillis")
        val started = row.getLong("startedWallMillis")
        val mode = TimerMode.valueOf(row.getString("mode"))
        require(duration in 0..Countdown.MAX_DURATION_MILLIS && elapsed >= 0 && started > 0)
        return TimerRecord(id, row.getString("appName"), row.getString("packageName"), mode, duration,
            started, elapsed, RecordStatus.valueOf(row.getString("status")))
    }

    private fun report(e: Exception) {
        Log.w("FloatingFocus", "Timer record storage failed", e)
        problem.value = "部分记录读写失败，请检查存储空间后重试。"
    }

    companion object {
        @Volatile private var instance: TimerRecordStore? = null
        fun get(context: Context): TimerRecordStore = instance ?: synchronized(this) {
            instance ?: TimerRecordStore(context.applicationContext).also { instance = it }
        }
    }
}
