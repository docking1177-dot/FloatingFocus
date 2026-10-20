package app.floatingfocus

import java.util.Locale

/** Pure clock model: UI cadence, wall-clock changes and sleep cannot accumulate drift. */
data class Countdown(val durationMillis: Long, val startedAt: Long) {
    init { require(durationMillis in 1..MAX_DURATION_MILLIS) }
    fun remainingAt(now: Long): Long = (durationMillis - (now - startedAt).coerceAtLeast(0)).coerceAtLeast(0)
    fun progressAt(now: Long): Float = 1f - remainingAt(now).toFloat() / durationMillis
    companion object { const val MAX_DURATION_MILLIS = 180 * 60_000L }
}

enum class ReminderStage { QUIET, WARM, URGENT, FINISHED }

object ReminderPolicy {
    const val WARM_START = 0.5f
    const val URGENT_START = 0.8f
    const val BREATH_MILLIS = 2400
    fun stage(progress: Float) = when {
        progress >= 1f -> ReminderStage.FINISHED
        progress >= URGENT_START -> ReminderStage.URGENT
        progress >= WARM_START -> ReminderStage.WARM
        else -> ReminderStage.QUIET
    }
}

fun formatRemaining(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) + 999) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
