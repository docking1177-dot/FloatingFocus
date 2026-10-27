package app.floatingfocus

data class TimerPreset(val id: String, val name: String, val minutes: Int, val settings: ReminderSettings, val mode: TimerMode = TimerMode.COUNTDOWN, val targetApp: LaunchableApp? = null,
                       val lastPosition: OverlayAnchor? = null, val durationSeconds: Int = minutes * 60) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank() && name.length <= 40)
        require(minutes in 1..180)
        require(durationSeconds in 1..10800)
    }
}

/** Storage-independent codec; unknown appearance keys retain the existing compatibility policy. */
object TimerPresetCodec {
    fun encode(preset: TimerPreset): Map<String, Any> = mapOf(
        "id" to preset.id, "name" to preset.name, "minutes" to preset.minutes, "durationSeconds" to preset.durationSeconds,
        "settings" to ReminderSettingsCodec.encode(preset.settings), "mode" to preset.mode.name,
        "targetPackage" to preset.targetApp?.packageName.orEmpty(), "targetLabel" to preset.targetApp?.label.orEmpty()) +
        (preset.lastPosition?.let { mapOf("positionX" to it.xFraction, "positionY" to it.yFraction) } ?: emptyMap())

    fun decode(row: Map<String, *>): TimerPreset? {
        val id = (row["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val name = (row["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: return null
        val minutes = (row["minutes"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in 1.0..180.0 }?.toInt() ?: return null
        val values = (row["settings"] as? Map<*, *>)?.entries?.filter { it.key is String }
            ?.associate { it.key as String to it.value } ?: return null
        return TimerPreset(id, name, minutes, ReminderSettingsCodec.decode(values),
            TimerMode.entries.firstOrNull { it.name == row["mode"] } ?: TimerMode.COUNTDOWN,
            (row["targetPackage"] as? String)?.takeIf { it.isNotBlank() }?.let { pkg ->
                LaunchableApp(pkg, (row["targetLabel"] as? String)?.takeIf { it.isNotBlank() } ?: pkg)
            }, OverlayAnchor.decode(row["positionX"], row["positionY"]),
            (row["durationSeconds"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in 1.0..10800.0 }?.toInt() ?: minutes * 60)
    }

    fun upsert(items: List<TimerPreset>, preset: TimerPreset): List<TimerPreset> =
        if (items.any { it.id == preset.id }) items.map { if (it.id == preset.id) preset else it } else items + preset
}
