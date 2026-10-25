package app.floatingfocus

/** Tolerates missing/older keys and corrupt values without discarding other valid settings. */
object ReminderSettingsCodec {
    private inline fun <reified T : Enum<T>> enumValue(value: Any?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    fun decode(values: Map<String, *>): ReminderSettings {
        val defaults = ReminderSettings()
        fun number(key: String, fallback: Float, range: ClosedFloatingPointRange<Float>): Float =
            (values[key] as? Number)?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(range) ?: fallback
        // Confirmation remains an experiment, not a production mode.
        val style = enumValue(values["style"], defaults.style).let {
            if (it == ReminderStyle.CONFIRM) defaults.style else it
        }
        return ReminderSettings(style,
            enumValue(values["colorMode"], defaults.colorMode),
            enumValue(values["sizeMode"], defaults.sizeMode),
            number("intensity", defaults.intensity, 0f..1f),
            number("baseSize", defaults.baseSize, 0.75f..1.5f),
            number("opacity", defaults.opacity, 0.3f..1f),
            enumValue(values["color"], defaults.color), enumValue(values["shape"], defaults.shape),
            values["colorEnabled"] as? Boolean ?: (style == ReminderStyle.COLOR),
            values["sizeEnabled"] as? Boolean ?: (style == ReminderStyle.SIZE))
    }

    fun encode(value: ReminderSettings): Map<String, Any> = mapOf(
        "style" to value.style.name, "colorMode" to value.colorMode.name, "sizeMode" to value.sizeMode.name,
        "intensity" to value.intensity, "baseSize" to value.baseSize, "opacity" to value.opacity,
        "color" to value.color.name, "shape" to value.shape.name,
        "colorEnabled" to value.colorEnabled, "sizeEnabled" to value.sizeEnabled)
}
