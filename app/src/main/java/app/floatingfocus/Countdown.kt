package app.floatingfocus

import java.util.Locale

/** Pure clock model: UI cadence, wall-clock changes and sleep cannot accumulate drift. */
enum class TimerMode(val label: String) { COUNTDOWN("倒计时"), STOPWATCH("正计时") }

data class Countdown(val durationMillis: Long, val startedAt: Long, val mode: TimerMode = TimerMode.COUNTDOWN) {
    init { require(if (mode == TimerMode.STOPWATCH) durationMillis == 0L else durationMillis in 1..MAX_DURATION_MILLIS) }
    fun elapsedAt(now: Long): Long = (now - startedAt).coerceAtLeast(0)
    fun overtimeAt(now: Long): Long = if (mode == TimerMode.COUNTDOWN) (elapsedAt(now) - durationMillis).coerceAtLeast(0) else 0L
    fun remainingAt(now: Long): Long = (durationMillis - (now - startedAt).coerceAtLeast(0)).coerceAtLeast(0)
    fun progressAt(now: Long): Float = if (mode == TimerMode.STOPWATCH) 0f else 1f - remainingAt(now).toFloat() / durationMillis
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

/** Elapsed time floors seconds; unlike remaining time it never shows time not yet spent. */
fun formatElapsed(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
