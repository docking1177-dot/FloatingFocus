package app.floatingfocus

/** Pauses by retaining consumed time; every running interval uses the existing monotonic clock. */
class PausableCountdown(val durationMillis: Long) {
    private var consumed = 0L
    private var clock: Countdown? = null
    var started = false
        private set
    val running get() = clock != null
    init { require(durationMillis in 1..Countdown.MAX_DURATION_MILLIS) }
    fun resume(now: Long) {
        if (clock == null) { clock = Countdown(durationMillis, now - consumed); started = true }
    }
    fun pause(now: Long) {
        clock?.let { consumed = durationMillis - it.remainingAt(now) }
        clock = null
    }
    fun snapshot(now: Long): ReminderSnapshot = clock?.let { ReminderSnapshot.at(it, now).copy(overtimeMillis = 0) }
        ?: ReminderSnapshot(durationMillis - consumed, consumed.toFloat() / durationMillis)
}

enum class AppEventKind { RESUME, PAUSE, STOP, CLEAR }
data class AppUsageEvent(val timestamp: Long, val packageName: String, val activity: String, val kind: AppEventKind)

/** Unknown / multiple resumed packages are deliberately not treated as reliable foreground focus. */
class AutoTimerEngine(val targets: Set<String>, durationMillis: Long) {
    val timer = PausableCountdown(durationMillis)
    private val resumed = mutableSetOf<Pair<String, String>>()
    var foreground: String? = null
        private set
    var reason = "等待目标 App"
        private set
    var lastEventAt = Long.MIN_VALUE
        private set

    fun accept(event: AppUsageEvent, atElapsed: Long) {
        if (event.timestamp < lastEventAt) return
        lastEventAt = event.timestamp
        val key = event.packageName to event.activity
        when (event.kind) {
            AppEventKind.RESUME -> resumed.add(key)
            AppEventKind.PAUSE, AppEventKind.STOP -> resumed.remove(key)
            AppEventKind.CLEAR -> resumed.clear()
        }
        val packages = resumed.map { it.first }.toSet()
        foreground = packages.singleOrNull()
        reason = when {
            packages.size > 1 -> "多个应用同时恢复，保守暂停"
            foreground in targets -> "目标 App 使用中"
            foreground == null -> "等待可靠前台事件"
            else -> "已离开目标 App"
        }
        if (foreground in targets) timer.resume(atElapsed) else timer.pause(atElapsed)
    }

    fun suspend(atElapsed: Long, message: String) {
        timer.pause(atElapsed)
        resumed.clear()
        foreground = null
        reason = message
        lastEventAt = Long.MIN_VALUE // A new cursor epoch may follow a backwards wall-clock adjustment.
    }
}

/** Bounded overlap catches slightly delayed events without replaying transitions. No history scans. */
class UsageEventCursor(startWall: Long, startElapsed: Long) {
    private var previousWall = startWall
    private var previousElapsed = startElapsed
    private var floor = startWall
    private val seen = mutableSetOf<AppUsageEvent>()
    data class Query(val from: Long, val until: Long, val discontinuity: Boolean)
    fun query(nowWall: Long, nowElapsed: Long): Query {
        val wallDelta = nowWall - previousWall
        val elapsedDelta = nowElapsed - previousElapsed
        val reset = wallDelta < 0 || elapsedDelta > MAX_GAP || kotlin.math.abs(wallDelta - elapsedDelta) > 2000
        val from = if (reset) nowWall else maxOf(floor, previousWall - OVERLAP)
        if (reset) { seen.clear(); floor = nowWall }
        seen.removeAll { it.timestamp < from }
        previousWall = nowWall
        previousElapsed = nowElapsed
        return Query(from, nowWall, reset)
    }
    fun fresh(events: List<AppUsageEvent>, query: Query): List<AppUsageEvent> = events
        .filter { it.timestamp >= query.from && it.timestamp < query.until && seen.add(it) }
        .sortedBy { it.timestamp }
    companion object {
        const val OVERLAP = 2000L
        const val MAX_GAP = 30_000L
        fun unlockFloor(nowWall: Long, lockedAtWall: Long) = maxOf(nowWall - OVERLAP, minOf(nowWall, lockedAtWall))
    }
}
