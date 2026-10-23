package app.floatingfocus

/** Consumes completion once, even if muted. Settings changes at zero never replay it. */
class CompletionReminder {
    private var consumed = false
    fun reset() { consumed = false }
    fun consume(active: Boolean, paused: Boolean, remainingMillis: Long): Boolean {
        if (consumed || !active || paused || remainingMillis != 0L) return false
        consumed = true
        return true
    }
}

object ReminderVibrationPolicy {
    fun durationMillis(intensity: Float): Long =
        if (!intensity.isFinite() || intensity <= 0f) 0L else (200 + 300 * intensity.coerceAtMost(1f)).toLong()
    fun amplitude(intensity: Float, amplitudeControl: Boolean): Int =
        if (!amplitudeControl) -1 else (intensity.coerceIn(0f, 1f) * 255).toInt().coerceAtLeast(1)
}
