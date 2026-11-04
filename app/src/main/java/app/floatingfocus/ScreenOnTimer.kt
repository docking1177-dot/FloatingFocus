package app.floatingfocus

/** Offsets on the session's monotonic timeline, anchored to its original wall time for history. */
data class ActiveInterval(val startOffsetMillis: Long, val endOffsetMillis: Long) {
    init { require(startOffsetMillis >= 0 && endOffsetMillis > startOffsetMillis) }
}

data class ScreenTimeSnapshot(val spanMillis: Long, val intervals: List<ActiveInterval>) {
    init {
        require(spanMillis >= 0)
        var end = 0L
        intervals.forEach { require(it.startOffsetMillis >= end && it.endOffsetMillis <= spanMillis); end = it.endOffsetMillis }
    }
    val activeMillis: Long get() = intervals.sumOf { it.endOffsetMillis - it.startOffsetMillis }
}

object ScreenOnPolicy {
    fun counts(interactive: Boolean, displayOn: Boolean) = interactive && displayOn
}

/** State transitions only. Never starts a service or infers time after process death. */
class ScreenOnTracker(private val startedAt: Long) {
    private val closedIntervals = mutableListOf<ActiveInterval>()
    private var openAt: Long? = null
    private var lastNow = startedAt
    private var finished: ScreenTimeSnapshot? = null
    private fun offset(now: Long): Long { lastNow = maxOf(lastNow, now); return (lastNow - startedAt).coerceAtLeast(0) }

    fun update(now: Long, lit: Boolean): Boolean {
        if (finished != null) return false
        val at = offset(now)
        if ((openAt != null) == lit) return false
        if (lit) openAt = at else {
            openAt?.takeIf { at > it }?.let { append(ActiveInterval(it, at)) }
            openAt = null
        }
        return true
    }

    private fun append(interval: ActiveInterval) {
        val last = closedIntervals.lastOrNull()
        if (last?.endOffsetMillis == interval.startOffsetMillis)
            closedIntervals[closedIntervals.lastIndex] = ActiveInterval(last.startOffsetMillis, interval.endOffsetMillis)
        else closedIntervals += interval
    }

    fun snapshot(now: Long): ScreenTimeSnapshot {
        finished?.let { return it }
        val at = offset(now)
        val current = openAt?.takeIf { at > it }?.let { ActiveInterval(it, at) }
        val intervals = closedIntervals.toMutableList()
        if (current != null) {
            val last = intervals.lastOrNull()
            if (last?.endOffsetMillis == current.startOffsetMillis)
                intervals[intervals.lastIndex] = ActiveInterval(last.startOffsetMillis, current.endOffsetMillis)
            else intervals += current
        }
        return ScreenTimeSnapshot(at, intervals)
    }

    fun finish(now: Long): ScreenTimeSnapshot = finished ?: snapshot(now).also { finished = it; openAt = null }
}
