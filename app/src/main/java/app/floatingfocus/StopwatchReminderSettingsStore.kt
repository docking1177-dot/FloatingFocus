package app.floatingfocus

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class StopwatchReminderSettingsStore private constructor(context: Context) {
    private val prefs=context.getSharedPreferences("stopwatch_reminders",Context.MODE_PRIVATE)
    private val initial=read()
    private val current=MutableStateFlow(initial ?: StopwatchReminderRules())
    val rules=current.asStateFlow()
    private val problem=MutableStateFlow(if(initial==null) "正计时提醒读取失败，请在设置中重新保存。" else null)
    val error=problem.asStateFlow()
    private val audio=MutableStateFlow(StopwatchReminderPermissions(prefs.all["allow_sound"] as? Boolean ?: false,
        prefs.all["allow_vibration"] as? Boolean ?: false))
    val permissions=audio.asStateFlow()

    fun snapshot(): StopwatchReminderRules?=current.value.takeIf { problem.value==null }
    fun updateRules(value: StopwatchReminderRules) {
        prefs.edit { putString("rules",encodeSnapshot(value)) }
        current.value=value;problem.value=null
    }
    fun setPermissions(value: StopwatchReminderPermissions) {
        prefs.edit { putBoolean("allow_sound",value.sound);putBoolean("allow_vibration",value.vibration) }
        audio.value=value
    }
    private fun read(): StopwatchReminderRules? { return try {
        if(!prefs.contains("rules")) StopwatchReminderRules() else decodeSnapshot(prefs.getString("rules",null) ?: return null)
    } catch (_: Exception) { null } }
    companion object {
        fun encodeSnapshot(value: StopwatchReminderRules): String=JSONObject(StopwatchReminderCodec.encode(value)).toString()
        fun decodeSnapshot(text: String): StopwatchReminderRules? = try {
            val row=JSONObject(text)
            fun values(json: JSONObject): Map<String,Any?> = json.keys().asSequence().associateWith { key ->
                val value=json.get(key)
                when(value) { is JSONObject -> values(value); JSONObject.NULL -> null; else -> value }
            }
            StopwatchReminderCodec.decode(values(row))
        } catch (_: Exception) { null }
        @Volatile private var instance: StopwatchReminderSettingsStore?=null
        fun get(context: Context): StopwatchReminderSettingsStore=instance ?: synchronized(this) {
            instance ?: StopwatchReminderSettingsStore(context.applicationContext).also { instance=it }
        }
    }
}
