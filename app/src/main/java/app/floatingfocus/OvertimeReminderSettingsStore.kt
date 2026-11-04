package app.floatingfocus

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Global periodic reminders, separate from zero completion, C4 and preset appearances. */
class OvertimeReminderSettingsStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("overtime_reminders", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(OvertimeReminderCodec.decode(prefs.all))
    val settings = current.asStateFlow()

    fun update(value: OvertimeReminderSettings) {
        val normalized = OvertimeReminderCodec.decode(OvertimeReminderCodec.encode(value))
        prefs.edit {
            OvertimeReminderCodec.encode(normalized).forEach { (key, item) ->
                when (item) {
                    is Boolean -> putBoolean(key, item)
                    is Int -> putInt(key, item)
                    is String -> putString(key, item)
                }
            }
        }
        current.value = normalized
    }

    companion object {
        @Volatile private var instance: OvertimeReminderSettingsStore? = null
        fun get(context: Context): OvertimeReminderSettingsStore = instance ?: synchronized(this) {
            instance ?: OvertimeReminderSettingsStore(context.applicationContext).also { instance = it }
        }
    }
}
