package app.floatingfocus

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object QuickDurations {
    val defaults = listOf(5, 10, 15, 30)
    fun valid(values: List<Int>) = values.size == 4 && values.all { it in 1..180 } && values.distinct().size == 4
    val defaultSeconds = defaults.map { it * 60 }
    fun validSeconds(values: List<Int>) = values.size == 4 && values.all { it in 60..10800 } && values.distinct().size == 4
    fun decodeSeconds(raw: String?, legacy: String?): List<Int> = if (raw == null) decode(legacy).map { it * 60 } else
        raw.split(',').takeIf { it.size == 4 }?.mapNotNull { it.toIntOrNull() }?.takeIf { validSeconds(it) } ?: defaultSeconds
    fun decode(raw: String?): List<Int> = raw?.split(',')?.takeIf { it.size == 4 }?.mapNotNull { it.toIntOrNull() }
        ?.takeIf { valid(it) } ?: defaults
}

/** Home shortcuts are not timer presets; keep their four values in existing-style local preferences. */
class QuickDurationsStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("quick_durations", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(QuickDurations.decodeSeconds(prefs.getString("seconds", null), prefs.getString("minutes", null)))
    val durations = current.asStateFlow()
    private val currentStartupDefaults = MutableStateFlow(TimerStartupDefaults.decode(
        prefs.all["startup_mode"] as? String, prefs.all["startup_seconds"] as? Int))
    val startupDefaults = currentStartupDefaults.asStateFlow()
    fun save(values: List<Int>, defaults: TimerStartupDefaults = currentStartupDefaults.value) {
        require(QuickDurations.validSeconds(values))
        val copy = values.toList()
        prefs.edit {
            putString("seconds", copy.joinToString(","))
            putString("startup_mode", defaults.mode.name)
            putInt("startup_seconds", defaults.durationSeconds)
        }
        current.value = copy
        currentStartupDefaults.value = defaults
    }
    companion object {
        @Volatile private var instance: QuickDurationsStore? = null
        fun get(context: Context): QuickDurationsStore = instance ?: synchronized(this) {
            instance ?: QuickDurationsStore(context.applicationContext).also { instance = it }
        }
    }
}
