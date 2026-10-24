package app.floatingfocus

data class TimerPreset(val id: String, val name: String, val minutes: Int, val settings: ReminderSettings) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank() && name.length <= 40)
        require(minutes in 1..180)
    }
}

/** Storage-independent codec; unknown appearance keys retain the existing compatibility policy. */
object TimerPresetCodec {
    fun encode(preset: TimerPreset): Map<String, Any> = mapOf(
        "id" to preset.id, "name" to preset.name, "minutes" to preset.minutes,
        "settings" to ReminderSettingsCodec.encode(preset.settings))

    fun decode(row: Map<String, *>): TimerPreset? {
        val id = (row["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val name = (row["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: return null
        val minutes = (row["minutes"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in 1.0..180.0 }?.toInt() ?: return null
        val values = (row["settings"] as? Map<*, *>)?.entries?.filter { it.key is String }
            ?.associate { it.key as String to it.value } ?: return null
        return TimerPreset(id, name, minutes, ReminderSettingsCodec.decode(values))
    }

    fun upsert(items: List<TimerPreset>, preset: TimerPreset): List<TimerPreset> =
        if (items.any { it.id == preset.id }) items.map { if (it.id == preset.id) preset else it } else items + preset
}
