package app.floatingfocus

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Current enable preference plus a live global sound switch; presets snapshot only enable. */
data class C4Settings(val enabled: Boolean = false, val sound: Boolean = true) {
    /** Null is used only by current-config launches; persisted presets always have an explicit Boolean. */
    fun appliesTo(mode: TimerMode, presetEnabled: Boolean? = null): Boolean =
        (presetEnabled ?: enabled) && mode == TimerMode.COUNTDOWN
}
class C4SettingsStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("c4_settings", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(C4Settings(prefs.getBoolean("enabled", false), prefs.getBoolean("sound", true)))
    val settings = current.asStateFlow()
    val noticeAcknowledged: Boolean get() = prefs.getBoolean("noticeAcknowledged", false)
    fun acknowledgeNotice() { prefs.edit { putBoolean("noticeAcknowledged", true) } }
    fun update(value: C4Settings) {
        prefs.edit { putBoolean("enabled", value.enabled); putBoolean("sound", value.sound) }
        current.value = value
    }
    companion object {
        @Volatile private var instance: C4SettingsStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: C4SettingsStore(context.applicationContext).also { instance = it }
        }
    }
}
