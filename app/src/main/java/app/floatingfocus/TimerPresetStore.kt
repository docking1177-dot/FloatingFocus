package app.floatingfocus

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class TimerPresetStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("timer_presets", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(read())
    val presets = current.asStateFlow()

    fun save(preset: TimerPreset) = write(TimerPresetCodec.upsert(current.value, preset))
    fun delete(id: String) = write(current.value.filterNot { it.id == id })

    fun rememberPosition(id: String, position: OverlayAnchor) {
        val preset = current.value.firstOrNull { it.id == id } ?: return // never resurrect a deleted preset
        if (preset.lastPosition != position) save(preset.copy(lastPosition = position))
    }

    private fun write(items: List<TimerPreset>) {
        val rows = JSONArray()
        items.forEach { rows.put(JSONObject(TimerPresetCodec.encode(it))) }
        prefs.edit { putString("items", rows.toString()) }
        current.value = items
    }

    private fun read(): List<TimerPreset> = try {
        val rows = JSONArray(prefs.getString("items", "[]"))
        (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.let { row ->
                val appearance = row.optJSONObject("settings") ?: return@let null
                val values = appearance.keys().asSequence().associateWith { appearance.opt(it) }
                TimerPresetCodec.decode(mapOf("id" to row.opt("id"), "name" to row.opt("name"),
                    "targetPackage" to row.opt("targetPackage"), "targetLabel" to row.opt("targetLabel"),
                    "minutes" to row.opt("minutes"), "durationSeconds" to row.opt("durationSeconds"), "mode" to row.opt("mode"), "settings" to values,
                    "positionX" to row.opt("positionX"), "positionY" to row.opt("positionY")))
            }
        }.distinctBy { it.id }
    } catch (_: org.json.JSONException) { emptyList() }

    companion object {
        @Volatile private var instance: TimerPresetStore? = null
        fun get(context: Context): TimerPresetStore = instance ?: synchronized(this) {
            instance ?: TimerPresetStore(context.applicationContext).also { instance = it }
        }
    }
}
