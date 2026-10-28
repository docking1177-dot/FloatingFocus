package app.floatingfocus

/** Positions on the supplied recording, not newly measured CS2 sound/light mechanics. */
data class C4Event(val index: Int, val atMillis: Long, val text: String) {
    val lightsLed: Boolean get() = text.contains("滴") || text.contains("快速连响") || text.contains("下包完成")
    val pulseMillis: Long get() = if (text.contains("快速连响")) 45 else 80
}
enum class C4Phase { OFF, PLANTING, ARMED, EXPLODING, DEFUSED }
data class C4Frame(val phase: C4Phase = C4Phase.OFF, val led: Boolean = false,
                   val remainingMillis: Long = 0, val input: String = "", val error: Boolean = false) {
    val active: Boolean get() = phase != C4Phase.OFF
    val inputEnabled: Boolean get() = phase == C4Phase.ARMED
}

class C4Timeline(val events: List<C4Event>) {
    init { require(events.isNotEmpty() && events.zipWithNext().all { (a, b) -> a.atMillis <= b.atMillis }) }
    fun phase(signedRemaining: Long): C4Phase = when {
        signedRemaining > WINDOW_MILLIS || signedRemaining <= -TAIL_MILLIS -> C4Phase.OFF
        signedRemaining <= 0 -> C4Phase.EXPLODING
        WINDOW_MILLIS - signedRemaining < INPUT_AT_MILLIS -> C4Phase.PLANTING
        else -> C4Phase.ARMED
    }
    fun led(signedRemaining: Long): Boolean {
        val offset = WINDOW_MILLIS - signedRemaining
        val event = events.lastOrNull { it.atMillis <= offset } ?: return false
        return signedRemaining > 0 && event.lightsLed && offset - event.atMillis < event.pulseMillis
    }
    fun nextDelay(signedRemaining: Long): Long? {
        if (signedRemaining <= -TAIL_MILLIS) return null
        val offset = WINDOW_MILLIS - signedRemaining
        val boundaries = events.flatMap { if (it.lightsLed) listOf(it.atMillis, it.atMillis + it.pulseMillis) else listOf(it.atMillis) } +
            listOf(0L, INPUT_AT_MILLIS, WINDOW_MILLIS, WINDOW_MILLIS + TAIL_MILLIS)
        return boundaries.filter { it > offset }.minOrNull()?.minus(offset)
    }
    companion object {
        const val WINDOW_MILLIS = 44_439L
        const val INPUT_AT_MILLIS = 4920L
        const val TAIL_MILLIS = 5902L
        const val CODE = "7355608"
        fun parse(lrc: String): C4Timeline {
            val pattern = Regex("^\\[(\\d{2}):(\\d{2})\\.(\\d{2})](.+)$")
            return C4Timeline(lrc.lineSequence().filter { it.isNotBlank() }.mapIndexed { index, line ->
                val match = requireNotNull(pattern.matchEntire(line.trim()))
                val (minute, second, hundredth, text) = match.destructured
                require(second.toInt() < 60)
                C4Event(index, minute.toLong() * 60_000 + second.toLong() * 1000 + hundredth.toLong() * 10, text)
            }.toList())
        }
    }
}

enum class C4KeyResult { IGNORED, CHANGED, WRONG, DEFUSED, EXPIRED }

/** Main-thread owner. The seventh key samples the same monotonic deadline as natural completion. */
class C4Input {
    var digits: String = ""
        private set
    var defused = false
        private set
    private var errorUntilOffset = -1L
    fun error(signedRemaining: Long) = C4Timeline.WINDOW_MILLIS - signedRemaining < errorUntilOffset
    fun errorDelay(signedRemaining: Long) = (errorUntilOffset - (C4Timeline.WINDOW_MILLIS - signedRemaining)).takeIf { it > 0 }
    fun key(key: Char, signedRemaining: Long): C4KeyResult {
        if (defused) return C4KeyResult.IGNORED
        if (signedRemaining <= 0) return C4KeyResult.EXPIRED
        val offset = C4Timeline.WINDOW_MILLIS - signedRemaining
        if (offset < C4Timeline.INPUT_AT_MILLIS) return C4KeyResult.IGNORED
        errorUntilOffset = -1
        when (key) {
            '*' -> digits = digits.dropLast(1)
            '#' -> digits = ""
            in '0'..'9' -> digits += key
            else -> return C4KeyResult.IGNORED
        }
        if (digits.length < 7) return C4KeyResult.CHANGED
        if (digits == C4Timeline.CODE) { defused = true; return C4KeyResult.DEFUSED }
        digits = ""; errorUntilOffset = offset + 420
        return C4KeyResult.WRONG
    }
}

/** Preserve existing pre-C4 pause behavior; no new clock or parallel timer. */
class SessionClock {
    private var pausedAt: Long? = null
    private var pausedMillis = 0L
    val paused: Boolean get() = pausedAt != null
    fun at(elapsedRealtime: Long): Long = (pausedAt ?: elapsedRealtime) - pausedMillis
    fun pause(now: Long) { if (!paused) pausedAt = now }
    fun resume(now: Long) { pausedAt?.let { pausedMillis += (now - it).coerceAtLeast(0); pausedAt = null } }
}
