package app.floatingfocus

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/** Shared ordinary-session positions; presets have their own ID-owned table. */
class OverlayPositionStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("overlay_positions",Context.MODE_PRIVATE)
    fun snapshot(): OverlayPositions { return try {
        val raw = prefs.getString("positions",null) ?: return OverlayPositions()
        fun map(row: JSONObject): Map<String,Any?> = row.keys().asSequence().associateWith { key ->
            when (val value=row.get(key)) { is JSONObject -> map(value); JSONObject.NULL -> null; else -> value } }
        OverlayPositionsCodec.decode(map(JSONObject(raw))) ?: OverlayPositions()
    } catch (_: RuntimeException) { OverlayPositions() } catch (_: org.json.JSONException) { OverlayPositions() } }
    fun save(value: OverlayPositions) { prefs.edit { putString("positions",JSONObject(OverlayPositionsCodec.encode(value)).toString()) } }
    companion object {
        @Volatile private var instance: OverlayPositionStore? = null
        fun get(context: Context): OverlayPositionStore = instance ?: synchronized(this) {
            instance ?: OverlayPositionStore(context.applicationContext).also { instance=it }
        }
    }
}
