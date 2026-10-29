package app.floatingfocus

/** Only the home startup selection; independent of shortcuts, presets and appearance. */
data class TimerStartupDefaults(val mode: TimerMode = TimerMode.COUNTDOWN, val durationSeconds: Int = 900) {
    init { require(durationSeconds in 1..10800) }

    companion object {
        fun restoreSelection(value: Any?): TimerStartupDefaults? {
            val fields = value as? List<*> ?: return null
            if (fields.size != 2) return null
            return decode(fields[0] as? String, fields[1] as? Int)
        }

        fun decode(modeName: String?, durationSeconds: Int?): TimerStartupDefaults = TimerStartupDefaults(
            TimerMode.entries.firstOrNull { it.name == modeName } ?: TimerMode.COUNTDOWN,
            durationSeconds?.takeIf { it in 1..10800 } ?: 900)

        /** Called only by a saveable initializer, so restored home choices always win. */
        fun initialHomeSelection(defaults: TimerStartupDefaults, session: SessionState): TimerStartupDefaults {
            if (!session.active) return defaults
            val seconds = if (session.mode == TimerMode.COUNTDOWN && session.durationMillis > 0)
                ((session.durationMillis.coerceAtMost(Countdown.MAX_DURATION_MILLIS) + 999) / 1000).toInt()
            else defaults.durationSeconds
            return TimerStartupDefaults(session.mode, seconds)
        }
    }
}
