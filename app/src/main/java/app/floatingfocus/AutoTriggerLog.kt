package app.floatingfocus

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Opt-in feature's diagnostics stay in a bounded in-memory buffer. No usage history is saved. */
object AutoTriggerLog {
    private val buffer = MutableStateFlow<List<String>>(emptyList())
    val lines = buffer.asStateFlow()
    fun record(context: Context, message: String) {
        val line = "${System.currentTimeMillis()} $message"
        buffer.value = (buffer.value + line).takeLast(150)
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d("AutoTrigger", line)
    }
    fun clear() { buffer.value = emptyList() }
}
