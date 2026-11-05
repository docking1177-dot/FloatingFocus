package app.floatingfocus

/** Explicit source tags keep damaged new records from being mistaken for markerless legacy rows. */
object TimerRecordCodec {
    fun encode(r: TimerRecord): Map<String,Any> = buildMap {
        put("id",r.id);put("appName",r.appName);put("packageName",r.packageName);put("mode",r.mode.name)
        put("plannedMillis",r.plannedMillis);put("startedWallMillis",r.startedWallMillis);put("elapsedMillis",r.elapsedMillis);put("status",r.status.name)
        r.savedOvertimeMillis?.let { put("overtimeMillis",it) }
        r.stopwatch?.let { put("source","STOPWATCH");put("stopwatch",StopwatchCheckpointCodec.encode(it)) }
        r.screenTime?.let { put("source","SCREEN_ON");put("screenSpanMillis",it.spanMillis)
            put("activeIntervals",it.intervals.map { interval -> listOf(interval.startOffsetMillis,interval.endOffsetMillis) }) }
    }
    fun decode(value: Any?): TimerRecord? { return try {
        val row=value as? Map<*,*> ?: return null
        fun number(key: String): Long = (row[key] as? Number)?.toString()?.toBigDecimal()?.longValueExact() ?: error("Missing number")
        fun text(key: String): String = row[key] as? String ?: error("Missing text")
        val id=text("id");require(id.matches(Regex("[a-zA-Z0-9-]+")))
        val duration=number("plannedMillis");val elapsed=number("elapsedMillis");val wall=number("startedWallMillis")
        require(duration>=0 && elapsed>=0 && wall>0)
        val source=row["source"]
        val stopwatch=if (source=="STOPWATCH") StopwatchCheckpointCodec.decode(row["stopwatch"]) ?: error("Invalid checkpoint") else {
            require(!row.containsKey("stopwatch"));null }
        val screen=if (source=="SCREEN_ON") {
            val intervals=row["activeIntervals"] as? List<*> ?: error("Missing intervals")
            ScreenTimeSnapshot(number("screenSpanMillis"),intervals.map { raw ->
                val pair=raw as? List<*> ?: error("Invalid pair");require(pair.size==2)
                fun offset(i: Int)=(pair[i] as? Number)?.toString()?.toBigDecimal()?.longValueExact() ?: error("Invalid offset")
                ActiveInterval(offset(0),offset(1)) })
        } else { require(!row.containsKey("activeIntervals") && !row.containsKey("screenSpanMillis"));null }
        require(!row.containsKey("source") || source=="STOPWATCH" || source=="SCREEN_ON")
        val overtime=if(row.containsKey("overtimeMillis")) number("overtimeMillis").also { require(it>=0) } else null
        TimerRecord(id,text("appName"),text("packageName"),TimerMode.valueOf(text("mode")),duration,wall,elapsed,
            RecordStatus.valueOf(text("status")),overtime,screen,stopwatch)
    } catch (_: RuntimeException) { null } }
}

/** A retry recovers old owners, but must not interrupt the current process's submitted live record. */
internal class RecordRecoveryOwnership {
    private val owned=mutableSetOf<String>()
    @Synchronized fun submitted(record: TimerRecord) { if(record.status==RecordStatus.RUNNING) owned+=record.id }
    @Synchronized fun written(record: TimerRecord) { if(record.status!=RecordStatus.RUNNING) owned-=record.id }
    @Synchronized fun recover(record: TimerRecord) = if(record.id in owned) record else record.recover()
}
