package app.floatingfocus

/** Tolerates missing/older keys and corrupt values without discarding other valid settings. */
object ReminderSettingsCodec {
    private inline fun <reified T : Enum<T>> enumValue(value: Any?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    fun decode(values: Map<String, *>): ReminderSettings {
        val defaults = ReminderSettings()
        fun number(key: String, fallback: Float, range: ClosedFloatingPointRange<Float>): Float =
            (values[key] as? Number)?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(range) ?: fallback
        fun integer(key: String, fallback: Int, range: IntRange): Int =
            (values[key] as? Number)?.toDouble()?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in range.first.toDouble()..range.last.toDouble() }?.toInt() ?: fallback
        fun node(prefix: String, default: ReminderNode) = ReminderNode(
            integer(prefix + "Percent", default.percent, 0..99), integer(prefix + "Seconds", default.seconds, 1..10_800),
            values[prefix + "Progress"] as? Boolean ?: default.useProgress,
            values[prefix + "Remaining"] as? Boolean ?: default.useRemaining)
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
            values["sizeEnabled"] as? Boolean ?: (style == ReminderStyle.SIZE),
            ReminderStrategy(enumValue(values["triggerMode"], TriggerMode.PROGRESS),
                node("warm", defaults.strategy.warm), node("urgent", defaults.strategy.urgent)))
    }

    fun encode(value: ReminderSettings): Map<String, Any> = mapOf(
        "style" to value.style.name, "colorMode" to value.colorMode.name, "sizeMode" to value.sizeMode.name,
        "intensity" to value.intensity, "baseSize" to value.baseSize, "opacity" to value.opacity,
        "color" to value.color.name, "shape" to value.shape.name,
        "colorEnabled" to value.colorEnabled, "sizeEnabled" to value.sizeEnabled,
        "triggerMode" to value.strategy.mode.name,
        "warmPercent" to value.strategy.warm.percent, "warmSeconds" to value.strategy.warm.seconds,
        "warmProgress" to value.strategy.warm.useProgress, "warmRemaining" to value.strategy.warm.useRemaining,
        "urgentPercent" to value.strategy.urgent.percent, "urgentSeconds" to value.strategy.urgent.seconds,
        "urgentProgress" to value.strategy.urgent.useProgress, "urgentRemaining" to value.strategy.urgent.useRemaining)
}
