package app.floatingfocus

enum class StopwatchBehavior(val label: String, val description: String) {
    CONTINUOUS("息屏仍计时", "亮屏、息屏持续累计，可手动暂停或结束。"),
    SCREEN_ON("随亮屏计时", "亮屏累计，息屏暂停；锁屏亮起也计入，AOD 不计。仅由屏幕控制暂停。");

    companion object {
        fun decode(value: Any?): StopwatchBehavior = entries.firstOrNull { it.name == value } ?: CONTINUOUS
        fun resolve(mode: TimerMode, current: StopwatchBehavior, saved: StopwatchBehavior? = null) =
            if (mode == TimerMode.STOPWATCH) saved ?: current else CONTINUOUS
    }
}
