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
import org.json.JSONArray

/** One atomic file per session. Only the current small record is checkpointed, never the full history. */
class TimerRecordStore private constructor(context: Context) {
    private val directory = File(context.filesDir, "timer_records")
    private val io = Executors.newSingleThreadExecutor()
    private val checkpointQueued = AtomicBoolean(false)
    private val current = MutableStateFlow<List<TimerRecord>>(emptyList())
    val records = current.asStateFlow()
    private val problem = MutableStateFlow<String?>(null)
    val error = problem.asStateFlow()
    private val reading = MutableStateFlow(true)
    val isLoading = reading.asStateFlow()

    private val ownership=RecordRecoveryOwnership()
    private val failedWrites=mutableMapOf<String,TimerRecord>() // IO thread only

    init { readFiles() }

    /** Retry reading without recovering a live session owned by this process. */
    fun reload() {
        if (reading.compareAndSet(false, true)) readFiles()
    }

    fun recoverySource(id: String): TimerRecord? = if (reading.value || problem.value != null) null else
        StopwatchRecovery.source(current.value, id)

    private fun readFiles() {
        io.execute {
            problem.value = null
            try {
                check(directory.isDirectory || directory.mkdirs())
                failedWrites.toMap().values.forEach { row ->
                    write(row); ownership.written(row); failedWrites.remove(row.id)
                }
                val files = checkNotNull(directory.listFiles())
                val restored = files.filter { it.name.endsWith(".json") }.mapNotNull { file ->
                    try {
                        val row = decode(JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() }))
                        val recovered = ownership.recover(row)
                        if (recovered != row) write(recovered)
                        recovered
                    } catch (e: Exception) { report(e); null }
                }
                current.value = restored.sortedByDescending { it.startedWallMillis }
            } catch (e: Exception) { report(e) }
            finally {
                reading.value = false
            }
        }
    }

    fun save(record: TimerRecord, checkpoint: Boolean = false, after: (() -> Unit)? = null) {
        if (checkpoint && !checkpointQueued.compareAndSet(false, true)) return
        ownership.submitted(record)
        io.execute {
            try {
                write(record)
                ownership.written(record); failedWrites.remove(record.id)
                current.value = (current.value.filterNot { it.id == record.id } + record).sortedByDescending { it.startedWallMillis }
            } catch (e: Exception) { failedWrites[record.id]=record; report(e) }
            finally { if (checkpoint) checkpointQueued.set(false); after?.invoke() }
        }
    }

    private fun write(record: TimerRecord) {
        directory.mkdirs()
        val file = AtomicFile(File(directory, "${record.id}.json"))
        val stream = file.startWrite()
        try {
            stream.write(JSONObject(TimerRecordCodec.encode(record)).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }

    private fun decode(row: JSONObject): TimerRecord = TimerRecordCodec.decode(jsonValue(row))
        ?: error("Invalid timer record")

    private fun jsonValue(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { jsonValue(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
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
