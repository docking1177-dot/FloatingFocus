package app.floatingfocus

enum class WidgetCommand { START, PAUSE, RESUME, END, OPEN }
enum class WidgetLayout { COMPACT, SHORT_PRESETS, TALL_PRESETS }

data class TimerWidgetPresentation(val status: String, val time: String, val base: Long = 0,
    val running: Boolean = false, val countDown: Boolean = false, val overtime: Boolean = false,
    val primary: WidgetCommand = WidgetCommand.START, val canEnd: Boolean = false)

/** Desktop display only; the existing service remains the sole clock and command owner. */
object TimerWidgetModel {
    fun tallHeight(fontScale: Float) = 156f + 40f * (fontScale - 1f).coerceAtLeast(0f)
    fun layoutForHeight(height: Int, fontScale: Float) = when {
        height >= tallHeight(fontScale) -> WidgetLayout.TALL_PRESETS
        height >= 110 -> WidgetLayout.SHORT_PRESETS
        else -> WidgetLayout.COMPACT
    }

    fun present(s: SessionState, defaults: TimerStartupDefaults, now: Long): TimerWidgetPresentation {
        if (!s.active) return TimerWidgetPresentation(
            if (s.error != null) "启动失败，点击查看" else defaults.mode.label,
            if (defaults.mode == TimerMode.STOPWATCH) "00:00" else formatRemaining(defaults.durationSeconds * 1000L))
        val delay = if (s.paused || s.sampledAt <= 0) 0 else (now - s.sampledAt).coerceAtLeast(0)
        val stopwatch = s.mode == TimerMode.STOPWATCH
        val over = !stopwatch && (s.stage == ReminderStage.FINISHED || s.overtimeMillis > 0 || s.remainingMillis <= delay)
        val elapsed = s.elapsedMillis + delay
        val remaining = (s.remainingMillis - delay).coerceAtLeast(0)
        val overtime = if (s.remainingMillis > 0) (delay - s.remainingMillis).coerceAtLeast(0) else s.overtimeMillis + delay
        return TimerWidgetPresentation(
            status = when { s.c4Locked -> "C4 计时中"; s.paused -> "已暂停"; stopwatch -> "正计时"; over -> "超时"; else -> "倒计时" },
            time = when { stopwatch -> formatElapsed(elapsed); over -> "+${formatElapsed(overtime)}"; else -> formatRemaining(remaining) },
            base = when { stopwatch -> now - elapsed; over -> now - overtime; else -> now + remaining },
            running = !s.paused, countDown = !stopwatch && !over, overtime = over,
            primary = when { s.c4Locked || s.automatic || over -> WidgetCommand.OPEN; s.paused -> WidgetCommand.RESUME; else -> WidgetCommand.PAUSE },
            canEnd = !s.c4Locked && !s.automatic)
    }

    fun accepts(s: SessionState, command: WidgetCommand, request: String?, leg: Long): Boolean {
        if (!s.active || s.automatic || s.c4Locked || request == null || request != s.startRequestId || leg != s.timerStartedAt) return false
        return when (command) {
            WidgetCommand.END -> true
            WidgetCommand.PAUSE -> !s.paused && (s.mode == TimerMode.STOPWATCH || s.remainingMillis > 0)
            WidgetCommand.RESUME -> s.paused
            else -> false
        }
    }
}
