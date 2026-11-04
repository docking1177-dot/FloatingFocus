package app.floatingfocus

import kotlin.random.Random

enum class OvertimeEffect(val label: String, val visual: Boolean) {
    BRIGHTEN("背景提亮", true), OUTLINE("描边脉冲", true), STATUS("超时状态", true),
    VIBRATION("两段短震动", false), SOUND("短提示音", false)
}

enum class OvertimeLimit(val label: String, val strength: Float) {
    LIGHT("轻量", .22f), STANDARD("标准", .38f), NOTICEABLE("明显", .55f)
}

enum class OvertimeInteraction(val label: String) { LIGHTWEIGHT("轻量提醒"), CHOICE("主动选择下一次") }

data class OvertimeReminderSettings(
    val enabled: Boolean = false,
    val intervalSeconds: Int = 300,
    val effects: Set<OvertimeEffect> = setOf(OvertimeEffect.BRIGHTEN, OvertimeEffect.OUTLINE, OvertimeEffect.STATUS),
    val limit: OvertimeLimit = OvertimeLimit.STANDARD,
    val interaction: OvertimeInteraction = OvertimeInteraction.LIGHTWEIGHT
) {
    init { require(intervalSeconds in 60..10800) }
    val intervalRange: LongRange get() = intervalSeconds * 800L..intervalSeconds * 1200L
}

/** Independent global preferences: never decoded from an appearance or preset snapshot. */
object OvertimeReminderCodec {
    fun encode(s: OvertimeReminderSettings): Map<String, Any> = mapOf(
        "enabled" to s.enabled, "interval_seconds" to s.intervalSeconds,
        "effects" to s.effects.sortedBy { it.ordinal }.joinToString(",") { it.name },
        "limit" to s.limit.name, "interaction" to s.interaction.name)

    fun decode(values: Map<String, *>): OvertimeReminderSettings {
        val defaults = OvertimeReminderSettings()
        val effects = (values["effects"] as? String)?.split(',')?.mapNotNull { name ->
            OvertimeEffect.entries.firstOrNull { it.name == name }
        }?.toSet() ?: defaults.effects
        return OvertimeReminderSettings(values["enabled"] as? Boolean ?: false,
            (values["interval_seconds"] as? Int)?.takeIf { it in 60..10800 } ?: 300, effects,
            OvertimeLimit.entries.firstOrNull { it.name == values["limit"] } ?: defaults.limit,
            OvertimeInteraction.entries.firstOrNull { it.name == values["interaction"] } ?: defaults.interaction)
    }
}

data class OvertimeKey(val sessionId: String, val timerStart: Long)

/** All durations are existing Countdown readings; no new timer or accounting model. */
data class OvertimeContext(
    val key: OvertimeKey?, val inOvertime: Boolean, val paused: Boolean = false,
    val overtimeMillis: Long = 0, val elapsedMillis: Long = 0, val plannedMillis: Long = 0,
    val c4: Boolean = false, val c4Expanded: Boolean = false, val c4AudioBusy: Boolean = false
)

data class OvertimeScheduleState(
    val nextAt: Long? = null, val exact: Boolean = false, val round: Int = 0,
    val snoozeCount: Int = 0, val lastSnoozeSeconds: Int? = null
)

data class OvertimeCue(
    val id: Long, val effects: Set<OvertimeEffect>, val level: OvertimeLimit,
    val round: Int, val snoozeCount: Int, val overtimeMillis: Long,
    val elapsedMillis: Long, val plannedMillis: Long
)

data class OvertimeReminderInfo(val nextInMillis: Long, val snoozeCount: Int, val pickerSeconds: Int,
                                val choiceMode: Boolean, val plannedMillis: Long)

data class OvertimeSnoozeRequest(val key: OvertimeKey, val seconds: Int, val operationId: String) {
    fun accepts(context: OvertimeContext, settings: OvertimeReminderSettings): Boolean =
        settings.enabled && context.inOvertime && !context.paused && key == context.key &&
            seconds in 60..10800 && operationId.isNotBlank() && operationId.length <= 128
}

/** Also used on live settings changes; a cue can only lose effects, never acquire new ones. */
object OvertimeFeedbackPolicy {
    fun filter(cue: OvertimeCue, context: OvertimeContext, settings: OvertimeReminderSettings): OvertimeCue {
        val level = if (cue.level.ordinal > settings.limit.ordinal) settings.limit else cue.level
        return cue.copy(level = level, effects = cue.effects.filterTo(linkedSetOf()) { effect ->
            settings.enabled && context.inOvertime && !context.paused && effect in settings.effects &&
                (level != OvertimeLimit.LIGHT || effect.visual) &&
                (!context.c4 || !context.c4Expanded || !effect.visual) &&
                (effect != OvertimeEffect.SOUND || !context.c4AudioBusy)
        })
    }
}

object OvertimeRotation {
    /** Recent effects get one slot, others four. Repetition remains possible. */
    fun select(allowed: Set<OvertimeEffect>, recent: List<Set<OvertimeEffect>>, count: Int,
               random: (Int) -> Int): Set<OvertimeEffect> {
        val available = allowed.sortedBy { it.ordinal }.toMutableList()
        val used = recent.takeLast(2).flatten().toSet()
        val result = linkedSetOf<OvertimeEffect>()
        repeat(minOf(count, available.size)) {
            val slots = available.flatMap { effect -> List(if (effect in used) 1 else 4) { effect } }
            val chosen = slots[random(slots.size)]
            result += chosen
            available.remove(chosen)
        }
        return result
    }
}

/** One owner (the existing service), one fixed deadline per round, in SessionClock's domain. */
class OvertimeReminderEngine(private val random: (Int) -> Int = Random.Default::nextInt) {
    var state = OvertimeScheduleState()
        private set
    private var key: OvertimeKey? = null
    private var interval: Int? = null
    private var serial = 0L
    private val recent = ArrayDeque<Set<OvertimeEffect>>()
    private val operations = mutableSetOf<String>()

    fun clear() {
        key = null; interval = null; state = OvertimeScheduleState()
        recent.clear(); operations.clear()
    }

    private fun next(now: Long, s: OvertimeReminderSettings) {
        val range = s.intervalRange
        state = state.copy(nextAt = now + range.first + random((range.last - range.first + 1).toInt()), exact = false)
    }

    fun update(now: Long, context: OvertimeContext, settings: OvertimeReminderSettings): OvertimeCue? {
        if (!context.inOvertime || context.key == null) { clear(); return null }
        if (key != context.key) { clear(); key = context.key }
        val intervalChanged = interval != null && interval != settings.intervalSeconds
        interval = settings.intervalSeconds
        if (!settings.enabled) { state = state.copy(nextAt = null, exact = false); return null }
        if (context.paused) return null // SessionClock itself freezes the remaining interval.
        if (state.nextAt == null || (intervalChanged && !state.exact)) { next(now, settings); return null }
        if (now < state.nextAt!!) return null

        val round = state.round + 1
        val desired = when {
            round == 1 -> OvertimeLimit.LIGHT
            state.snoozeCount >= 3 -> OvertimeLimit.NOTICEABLE
            context.overtimeMillis >= settings.intervalSeconds * 3000L -> OvertimeLimit.STANDARD
            else -> OvertimeLimit.LIGHT
        }
        val level = if (desired.ordinal > settings.limit.ordinal) settings.limit else desired
        val allowed = settings.effects.filterTo(linkedSetOf()) { effect ->
            (level != OvertimeLimit.LIGHT || effect.visual) &&
                (!context.c4 || !context.c4Expanded || !effect.visual) &&
                (effect != OvertimeEffect.SOUND || !context.c4AudioBusy)
        }
        val count = if (level == OvertimeLimit.LIGHT) 1 else 1 + random(2)
        val effects = OvertimeRotation.select(allowed, recent.toList(), count, random)
        if (effects.isNotEmpty()) { recent.addLast(effects); if (recent.size > 2) recent.removeFirst() }
        state = state.copy(round = round)
        next(now, settings) // Late callbacks never replay a backlog of missed rounds.
        return OvertimeCue(++serial, effects, level, round, state.snoozeCount,
            context.overtimeMillis, context.elapsedMillis, context.plannedMillis)
    }

    fun snooze(now: Long, context: OvertimeContext, settings: OvertimeReminderSettings,
               seconds: Int, operationId: String): Boolean {
        if (!settings.enabled || !context.inOvertime || context.paused || context.key == null || context.key != key ||
            seconds !in 60..10800 || operationId.isBlank() || operationId.length > 128 || !operations.add(operationId)) return false
        state = state.copy(nextAt = now + seconds * 1000L, exact = true, snoozeCount = state.snoozeCount + 1,
            lastSnoozeSeconds = seconds)
        return true
    }
}
