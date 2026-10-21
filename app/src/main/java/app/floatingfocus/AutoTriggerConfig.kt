package app.floatingfocus

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import androidx.core.content.edit

data class AutoTriggerConfig(val packages: Set<String> = emptySet(), val durationMillis: Long = 900_000, val intervalMillis: Long = 1000) {
    init {
        require(durationMillis in 1..Countdown.MAX_DURATION_MILLIS)
        require(intervalMillis in INTERVALS)
    }
    companion object {
        val INTERVALS = listOf(500L, 1000L, 2000L, 5000L)
        fun read(context: Context): AutoTriggerConfig {
            val prefs = context.getSharedPreferences("auto_trigger", Context.MODE_PRIVATE)
            return AutoTriggerConfig(prefs.getStringSet("packages", emptySet())!!.toSet(),
                prefs.getLong("duration", 900_000).coerceIn(1, Countdown.MAX_DURATION_MILLIS),
                prefs.getLong("interval", 1000).takeIf { it in INTERVALS } ?: 1000)
        }
        fun save(context: Context, config: AutoTriggerConfig) {
            context.getSharedPreferences("auto_trigger", Context.MODE_PRIVATE).edit {
                putStringSet("packages", config.packages); putLong("duration", config.durationMillis); putLong("interval", config.intervalMillis)
            }
        }
        fun hasUsageAccess(context: Context): Boolean = context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
}
