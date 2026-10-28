package app.floatingfocus

import android.content.Context
import android.app.Application
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class TimerPresetStore private constructor(private val context: Application) {
    private val prefs = context.getSharedPreferences("timer_presets", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(read().orEmpty())
    val presets = current.asStateFlow()

    /** null means read failure, never an authoritative empty list. Keep the last good UI state. */
    @Synchronized fun snapshot(): List<TimerPreset>? = read()?.also { current.value = it }

    @Synchronized fun save(preset: TimerPreset): Boolean {
        val before = read() ?: return false
        write(TimerPresetCodec.upsert(before, preset))
        val old = before.firstOrNull { it.id == preset.id }
        if (old?.name != preset.name || old?.targetApp != preset.targetApp) PresetDesktopShortcuts.get(context).scheduleSync()
        return true
    }

    @Synchronized fun delete(id: String): Boolean {
        val before = read() ?: return false
        write(before.filterNot { it.id == id })
        PresetDesktopShortcuts.get(context).scheduleSync()
        return true
    }

    @Synchronized fun rememberPosition(id: String, position: OverlayAnchor) {
        val preset = read()?.firstOrNull { it.id == id } ?: return // never resurrect a deleted preset
        if (preset.lastPosition != position) save(preset.copy(lastPosition = position))
    }

    private fun write(items: List<TimerPreset>) {
        val rows = JSONArray()
        items.forEach { rows.put(JSONObject(TimerPresetCodec.encode(it))) }
        prefs.edit { putString("items", rows.toString()) }
        current.value = items
    }

    private fun read(): List<TimerPreset>? { return try {
        val rows = JSONArray(prefs.getString("items", "[]"))
        val decoded = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val appearance = row.getJSONObject("settings")
                val values = appearance.keys().asSequence().associateWith { appearance.opt(it) }
                TimerPresetCodec.decode(mapOf("id" to row.opt("id"), "name" to row.opt("name"),
                    "targetPackage" to row.opt("targetPackage"), "targetLabel" to row.opt("targetLabel"),
                    "minutes" to row.opt("minutes"), "durationSeconds" to row.opt("durationSeconds"), "mode" to row.opt("mode"), "settings" to values,
                    "positionX" to row.opt("positionX"), "positionY" to row.opt("positionY"))) ?: return null
        }
        decoded.takeIf { it.distinctBy(TimerPreset::id).size == it.size }
    } catch (_: RuntimeException) { null } catch (_: org.json.JSONException) { null }
    }

    companion object {
        @Volatile private var instance: TimerPresetStore? = null
        fun get(context: Context): TimerPresetStore = instance ?: synchronized(this) {
            instance ?: TimerPresetStore(context.applicationContext as Application).also { instance = it }
        }
    }
}
