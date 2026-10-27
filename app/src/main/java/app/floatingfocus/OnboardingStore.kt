package app.floatingfocus

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Completion is independent of permissions and app version. Never rewrites functional data. */
class OnboardingStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(if (prefs.contains("completed")) prefs.getBoolean("completed", false) else {
        val used = listOf("reminder_settings", "timer_presets", "quick_launch", "quick_durations", "c4_settings", "MainActivity")
            .any { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.isNotEmpty() } ||
            File(context.filesDir, "timer_records").listFiles().orEmpty().any { it.extension == "json" }
        prefs.edit { putBoolean("completed", used) }
        used
    })
    val completed = current.asStateFlow()
    fun complete() { prefs.edit { putBoolean("completed", true) }; current.value = true }
    companion object {
        @Volatile private var instance: OnboardingStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: OnboardingStore(context.applicationContext).also { instance = it }
        }
    }
}
