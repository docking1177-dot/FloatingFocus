package app.floatingfocus

/** Widget/tile defaults and the fallback countdown duration for a new home selection. */
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

        /** Reading/restoring a selection never changes the separately remembered explicit home mode. */
        fun initialHomeSelection(defaults: TimerStartupDefaults, session: SessionState,
                                 lastHomeMode: TimerMode? = null,
                                 restoredSelection: TimerStartupDefaults? = null): TimerStartupDefaults {
            val page = restoredSelection ?: defaults.copy(mode = lastHomeMode ?: defaults.mode)
            if (!session.active) return page
            val seconds = if (session.mode == TimerMode.COUNTDOWN && session.durationMillis > 0)
                ((session.durationMillis.coerceAtMost(Countdown.MAX_DURATION_MILLIS) + 999) / 1000).toInt()
            else page.durationSeconds
            return TimerStartupDefaults(session.mode, seconds)
        }
    }
}

/** Missing/bad memory preserves the existing default mode rather than silently selecting countdown. */
internal object HomeTimerMode {
    fun decode(value: Any?): TimerMode? = TimerMode.entries.firstOrNull { it.name == value }
}
