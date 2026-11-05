package app.floatingfocus

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class StopwatchBehaviorStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("stopwatch_behavior", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(StopwatchBehavior.decode(prefs.all["behavior"]))
    val behavior = current.asStateFlow()
    fun set(value: StopwatchBehavior) {
        prefs.edit { putString("behavior", value.name) }
        current.value = value
    }
    companion object {
        @Volatile private var instance: StopwatchBehaviorStore? = null
        fun get(context: Context): StopwatchBehaviorStore = instance ?: synchronized(this) {
            instance ?: StopwatchBehaviorStore(context.applicationContext).also { instance = it }
        }
    }
}
