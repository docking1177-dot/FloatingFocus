package app.floatingfocus

/** A session is not foreground-app usage: leaving the selected app does not stop it. */
data class TimerRecord(
    val id: String, val appName: String, val packageName: String,
    val mode: TimerMode, val plannedMillis: Long, val startedWallMillis: Long,
    val elapsedMillis: Long = 0, val status: RecordStatus = RecordStatus.RUNNING, val savedOvertimeMillis: Long? = null,
    val screenTime: ScreenTimeSnapshot? = null, val stopwatch: StopwatchCheckpoint? = null
) {
    init { require(screenTime == null || (mode == TimerMode.STOPWATCH && plannedMillis == 0L &&
        packageName.isBlank() && elapsedMillis == screenTime.activeMillis))
        require(stopwatch == null || (screenTime == null && mode == TimerMode.STOPWATCH && plannedMillis == 0L &&
            elapsedMillis == stopwatch.activity.activeMillis && stopwatch.continuationOf != id && stopwatch.rootId != id))
    }
    val activeTime: ScreenTimeSnapshot? get() = stopwatch?.activity ?: screenTime
    val stopwatchBehavior: StopwatchBehavior get() = stopwatch?.behavior ?: if (screenTime != null) StopwatchBehavior.SCREEN_ON else StopwatchBehavior.CONTINUOUS
    val displayElapsedMillis: Long get() = (stopwatch?.carriedMillis ?: 0) + elapsedMillis
    val endedWallMillis: Long get() = startedWallMillis + (activeTime?.spanMillis ?: elapsedMillis)
    val overtimeMillis: Long get() = if (mode == TimerMode.COUNTDOWN)
        savedOvertimeMillis ?: (elapsedMillis - plannedMillis).coerceAtLeast(0) else 0
    fun checkpoint(startedElapsed: Long, now: Long) = copy(elapsedMillis = (now - startedElapsed).coerceAtLeast(elapsedMillis))
    fun recover() = if (status == RecordStatus.RUNNING) copy(status = RecordStatus.INTERRUPTED) else this
}

enum class RecordStatus(val label: String) {
    RUNNING("计时中"), MANUAL("手动结束"), TASK_REMOVED("划掉 FF 结束"),
    INTERRUPTED("异常中断，约计"), SERVICE_STOPPED("服务停止"), ERROR("异常结束")
}

object TimerRecordCsv {
    // Quote all fields and neutralize spreadsheet formulas in untrusted application labels.
    private fun cell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') ||
            value.startsWith('\t') || value.startsWith('\r')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    fun encode(records: List<TimerRecord>): String = "\uFEFF" +
        (listOf(listOf("记录ID", "App", "包名", "模式", "开始时间UTC", "结束或最后记录时间UTC", "计划秒数", "累计秒数", "超时秒数", "结束方式", "计时口径", "会话跨度秒数", "亮屏活动区间毫秒偏移", "续计来源ID", "续计根ID", "承接累计秒数", "运行活动区间毫秒偏移")) +
            records.map { listOf(it.id, it.appName, it.packageName, it.mode.label,
                java.time.Instant.ofEpochMilli(it.startedWallMillis).toString(),
                java.time.Instant.ofEpochMilli(it.endedWallMillis).toString(),
                (it.plannedMillis / 1000).toString(), (it.elapsedMillis / 1000).toString(),
                (it.overtimeMillis / 1000).toString(), it.status.label,
                if (it.stopwatchBehavior == StopwatchBehavior.SCREEN_ON && it.activeTime != null) "亮屏有效时长" else if (it.stopwatch != null) "运行有效时长" else "会话经过时长",
                ((it.activeTime?.spanMillis ?: it.elapsedMillis) / 1000).toString(),
                it.activeTime?.takeIf { _ -> it.stopwatchBehavior == StopwatchBehavior.SCREEN_ON }?.intervals?.joinToString(",", "[", "]") { interval ->
                    "[${interval.startOffsetMillis},${interval.endOffsetMillis}]" }.orEmpty(),
                it.stopwatch?.continuationOf.orEmpty(), it.stopwatch?.rootId.orEmpty(),
                it.stopwatch?.let { checkpoint -> (checkpoint.carriedMillis / 1000).toString() }.orEmpty(),
                it.stopwatch?.activity?.intervals?.joinToString(",","[","]") { interval ->
                    "[${interval.startOffsetMillis},${interval.endOffsetMillis}]" }.orEmpty()) })
            .joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",", transform = ::cell) }
}
