package app.floatingfocus

enum class TimerTileState { INACTIVE, ACTIVE, UNAVAILABLE }
data class TimerTilePresentation(val state: TimerTileState, val subtitle: String, val command: WidgetCommand)

/** Native tile consumes the same sampled clock and control policy as widgets; never ends on one tap. */
object TimerTileModel {
    fun present(s: SessionState, defaults: TimerStartupDefaults, now: Long, busy: Boolean = false): TimerTilePresentation {
        if (busy) return TimerTilePresentation(TimerTileState.UNAVAILABLE, "正在开始…", WidgetCommand.OPEN)
        val clock = TimerWidgetModel.present(s, defaults, now)
        val command = when {
            !s.active -> WidgetCommand.START
            s.c4Enabled || s.c4Active || s.c4Locked || s.automatic || clock.overtime -> WidgetCommand.OPEN
            s.paused -> WidgetCommand.RESUME
            else -> WidgetCommand.PAUSE
        }
        val subtitle = when {
            !s.active -> "${defaults.mode.label} ${clock.time} · 开始"
            s.c4Locked -> "C4 口令阶段 · 查看"
            s.c4Active -> "C4 超时 ${clock.time} · 查看"
            s.c4Enabled -> "C4 ${clock.time} · 查看"
            clock.overtime -> "超时 ${clock.time} · 查看"
            s.paused -> "${clock.time} · 继续"
            s.automatic -> "计时中 · 查看"
            else -> "${clock.time} · 暂停"
        }
        return TimerTilePresentation(if (!s.active || s.paused) TimerTileState.INACTIVE else TimerTileState.ACTIVE, subtitle, command)
    }

    fun accepts(current: SessionState, command: WidgetCommand, request: String?, leg: Long, busy: Boolean = false): Boolean {
        if (busy) return false
        return when (command) {
            WidgetCommand.START -> !current.active && !current.automatic
            WidgetCommand.OPEN -> true
            WidgetCommand.PAUSE, WidgetCommand.RESUME -> TimerWidgetModel.accepts(current, command, request, leg)
            else -> false
        }
    }
}
