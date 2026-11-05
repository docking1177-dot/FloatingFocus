package app.floatingfocus

/** Only this segment's intervals count in history/quota; carriedMillis is a display baseline. */
data class StopwatchCheckpoint(val behavior: StopwatchBehavior, val activity: ScreenTimeSnapshot,
    val carriedMillis: Long = 0, val continuationOf: String? = null, val rootId: String? = null,
    val presetId: String? = null, val positions: OverlayPositions = OverlayPositions(),
    val stopwatchReminders: StopwatchReminderRules = StopwatchReminderRules()) {
    init {
        require(carriedMillis >= 0)
        Math.addExact(carriedMillis, activity.activeMillis)
        require((continuationOf == null) == (rootId == null))
        require(continuationOf != null || carriedMillis == 0L)
        require(listOfNotNull(continuationOf,rootId).all { it.matches(Regex("[a-zA-Z0-9-]+")) })
        require(presetId == null || presetId.isNotBlank())
    }
}

object StopwatchRecovery {
    private val interrupted = setOf(RecordStatus.INTERRUPTED,RecordStatus.SERVICE_STOPPED,RecordStatus.ERROR)
    fun candidates(records: List<TimerRecord>): List<TimerRecord> {
        val consumed = records.mapNotNull { it.stopwatch?.continuationOf }.toSet()
        return records.filter { it.mode == TimerMode.STOPWATCH && it.status in interrupted &&
            it.activeTime != null && it.id !in consumed }.sortedByDescending { it.endedWallMillis }
    }
    fun source(records: List<TimerRecord>, id: String) = candidates(records).firstOrNull { it.id == id }
}

/** JVM-testable strict codec, used by the existing AtomicFile store. */
object StopwatchCheckpointCodec {
    fun encode(value: StopwatchCheckpoint): Map<String, Any> = buildMap {
        put("version",1); put("behavior",value.behavior.name); put("carriedMillis",value.carriedMillis)
        put("activity",mapOf("spanMillis" to value.activity.spanMillis,
            "intervals" to value.activity.intervals.map { listOf(it.startOffsetMillis,it.endOffsetMillis) }))
        value.continuationOf?.let { put("continuationOf",it) }; value.rootId?.let { put("rootId",it) }
        value.presetId?.let { put("presetId",it) }; put("positions",OverlayPositionsCodec.encode(value.positions))
        put("stopwatchReminders",StopwatchReminderCodec.encode(value.stopwatchReminders))
    }
    fun decode(value: Any?): StopwatchCheckpoint? { return try {
        val row = value as? Map<*, *> ?: return null
        fun number(raw: Any?): Long = (raw as? Number)?.toString()?.toBigDecimal()?.longValueExact() ?: error("Missing number")
        require(number(row["version"]) == 1L)
        val behavior = StopwatchBehavior.entries.firstOrNull { it.name == row["behavior"] } ?: return null
        val activity = row["activity"] as? Map<*, *> ?: return null
        val intervals = activity["intervals"] as? List<*> ?: return null
        val snapshot = ScreenTimeSnapshot(number(activity["spanMillis"]), intervals.map { raw ->
            val pair = raw as? List<*> ?: error("Invalid interval")
            require(pair.size == 2); ActiveInterval(number(pair[0]),number(pair[1])) })
        fun text(key: String): String? = if (!row.containsKey(key)) null else row[key] as? String ?: error("Invalid text")
        require(row.containsKey("positions"))
        require(row["positions"] is Map<*,*>)
        val positions = OverlayPositionsCodec.decode(row["positions"]) ?: return null
        val reminders=if(row.containsKey("stopwatchReminders")) StopwatchReminderCodec.decode(row["stopwatchReminders"]) ?: return null else StopwatchReminderRules()
        StopwatchCheckpoint(behavior,snapshot,number(row["carriedMillis"]),text("continuationOf"),text("rootId"),text("presetId"),positions,reminders)
    } catch (_: RuntimeException) { null } }
}
