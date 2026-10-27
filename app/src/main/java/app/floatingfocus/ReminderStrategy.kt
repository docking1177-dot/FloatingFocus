package app.floatingfocus

enum class TriggerMode(val label: String) { PROGRESS("进度触发"), REMAINING("剩余时间触发"), MIXED("混合触发") }

data class ReminderNode(val percent: Int, val seconds: Int,
                        val useProgress: Boolean = true, val useRemaining: Boolean = false) {
    init { require(percent in 0..99 && seconds in 1..10_800) }
}

/** Two independent escalation nodes. Completion is always owned by the real clock. */
data class ReminderStrategy(val mode: TriggerMode = TriggerMode.PROGRESS,
                            val warm: ReminderNode = ReminderNode(50, 60),
                            val urgent: ReminderNode = ReminderNode(80, 20)) {
    /** Legacy global modes are read without rewriting saved values. New edits save one type per node. */
    fun singleNode(node: ReminderNode): ReminderNode = when (mode) {
        TriggerMode.PROGRESS -> node.copy(useProgress = true, useRemaining = false)
        TriggerMode.REMAINING -> node.copy(useProgress = false, useRemaining = true)
        TriggerMode.MIXED -> node
    }

    fun position(node: ReminderNode, durationMillis: Long): Double {
        val n = singleNode(node)
        return if (n.useProgress) n.percent / 100.0 else 1.0 - n.seconds * 1000.0 / durationMillis
    }

    fun validationError(durationMillis: Long, timerMode: TimerMode = TimerMode.COUNTDOWN): String? {
        if (timerMode == TimerMode.STOPWATCH) return null
        if (durationMillis !in 1..Countdown.MAX_DURATION_MILLIS) return "计时时长无效。"
        for ((label, node) in listOf("第二级" to warm, "第三级" to urgent)) {
            val n = singleNode(node)
            if (n.useProgress == n.useRemaining) return "$label 必须选择一种触发条件，请修改提醒策略。"
            if (!n.useProgress && n.seconds * 1000L > durationMillis)
                return "$label 的剩余时间超过本次总时长，请修改提醒策略或计时时长。"
        }
        // Compare exact time units, not chart fractions: 0.58 != 1.0 - 0.42 in Double.
        fun triggerUnits(node: ReminderNode): Long = singleNode(node).let {
            if (it.useProgress) durationMillis * it.percent else (durationMillis - it.seconds * 1000L) * 100
        }
        if (triggerUnits(warm) >= triggerUnits(urgent))
            return "触发顺序冲突：本次总时长 ${durationLabel((durationMillis / 1000).toInt())}，第三级必须晚于第二级。请修改提醒策略。"
        return null
    }

    /** Quantize a dragged node in its own unit; reject crossings rather than swapping identities. */
    fun moved(warmNode: Boolean, fraction: Double, durationMillis: Long): ReminderStrategy {
        val old = singleNode(if (warmNode) warm else urgent)
        if (old.useProgress == old.useRemaining) return this
        val next = if (old.useProgress) old.copy(percent = kotlin.math.round(fraction * 100).toInt().coerceIn(0, 99))
            else old.copy(seconds = kotlin.math.round((1 - fraction) * durationMillis / 1000).toInt().coerceIn(1, 10800))
        val result = copy(mode = TriggerMode.MIXED, warm = if (warmNode) next else singleNode(warm),
            urgent = if (warmNode) singleNode(urgent) else next)
        return if (result.validationError(durationMillis) == null) result else this
    }
    private fun threshold(node: ReminderNode, snapshot: ReminderSnapshot): Float {
        val progress = node.percent / 100f
        val duration = snapshot.durationMillis.takeIf { it > 0 }
            ?: if (snapshot.progress < 1f) (snapshot.remainingMillis / (1.0 - snapshot.progress)).toLong() else 0L
        val remaining = if (duration > 0) (1f - node.seconds * 1000f / duration).coerceIn(0f, 1f) else 1f
        return when (mode) {
            TriggerMode.PROGRESS -> progress
            TriggerMode.REMAINING -> remaining
            TriggerMode.MIXED -> minOf(if (node.useProgress) progress else 1f,
                if (node.useRemaining) remaining else 1f)
        }
    }

    fun stage(snapshot: ReminderSnapshot): ReminderStage {
        if (snapshot.mode == TimerMode.STOPWATCH) return ReminderStage.QUIET
        if (snapshot.progress >= 1f) return ReminderStage.FINISHED
        fun matches(node: ReminderNode): Boolean {
            val progress = if (snapshot.durationMillis > 0)
                (snapshot.durationMillis - snapshot.remainingMillis).coerceAtLeast(0) * 100 >= snapshot.durationMillis * node.percent
            else snapshot.progress >= node.percent / 100f
            val remaining = snapshot.remainingMillis <= node.seconds * 1000L
            return when (mode) {
                TriggerMode.PROGRESS -> progress
                TriggerMode.REMAINING -> remaining
                TriggerMode.MIXED -> (node.useProgress && progress) || (node.useRemaining && remaining)
            }
        }
        return when {
            matches(urgent) -> ReminderStage.URGENT
            matches(warm) -> ReminderStage.WARM
            else -> ReminderStage.QUIET
        }
    }

    /** Map custom boundaries onto the existing effect curve; default output stays identical. */
    fun effectProgress(snapshot: ReminderSnapshot): Float {
        val stage = stage(snapshot)
        if (stage == ReminderStage.FINISHED) return 1f
        if (snapshot.mode == TimerMode.STOPWATCH) return 0f
        val p = snapshot.progress.coerceIn(0f, 1f)
        val upper = threshold(urgent, snapshot)
        val lower = minOf(threshold(warm, snapshot), upper)
        val result = when {
            p < lower -> if (lower > 0f) .5f * p / lower else .5f
            p < upper -> .5f + .3f * (p - lower) / (upper - lower)
            upper < 1f -> .8f + .2f * (p - upper) / (1f - upper)
            else -> .5f + .3f * (p - lower) / (1f - lower).coerceAtLeast(.0001f)
        }
        // Avoid floating-point rounding placing an exact time boundary in a lower stage.
        return when (stage) {
            ReminderStage.URGENT -> result.coerceIn(.8f, .999999f)
            ReminderStage.WARM -> result.coerceIn(.5f, .799999f)
            else -> result.coerceIn(0f, .499999f)
        }
    }
}
