package app.floatingfocus

import java.util.Locale

/** Two readings from the same session. Total already includes previous legs and overtime. */
data class OvertimeReadout(val totalMillis: Long, val overtimeMillis: Long) {
    val totalText get() = formatUsageTime(totalMillis)
    val overtimeText get() = "+${formatUsageTime(overtimeMillis)}"
}

fun formatUsageTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return if (seconds < 3600) formatElapsed(millis)
        else String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
