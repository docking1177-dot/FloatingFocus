package app.floatingfocus

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One application-scoped source; simulation time is never stored here. */
class ReminderSettingsStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("reminder_settings", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(ReminderSettingsCodec.decode(prefs.all))
    val settings = current.asStateFlow()

    fun update(change: (ReminderSettings) -> ReminderSettings) {
        val value = ReminderSettingsCodec.decode(ReminderSettingsCodec.encode(change(current.value)))
        prefs.edit {
            ReminderSettingsCodec.encode(value).forEach { (key, item) ->
                when (item) {
                    is String -> putString(key, item)
                    is Float -> putFloat(key, item)
                    is Boolean -> putBoolean(key, item)
                }
            }
        }
        current.value = value
    }

    companion object {
        @Volatile private var instance: ReminderSettingsStore? = null
        fun get(context: Context): ReminderSettingsStore = instance ?: synchronized(this) {
            instance ?: ReminderSettingsStore(context.applicationContext).also { instance = it }
        }
    }
}
