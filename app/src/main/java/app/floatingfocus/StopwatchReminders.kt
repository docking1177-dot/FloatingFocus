package app.floatingfocus

enum class StopwatchLevel(val label: String, val key: String) {
    PRIMARY("初级提醒","primary"), MIDDLE("中级提醒","middle"), HIGH("高级提醒","high")
}

data class StopwatchRule(val enabled: Boolean = false, val intervalSeconds: Int = 300,
                         val effects: Set<OvertimeEffect> = setOf(OvertimeEffect.BRIGHTEN),
                         val color: StopwatchColorSettings = StopwatchColorSettings()) {
    init { require(intervalSeconds in 60..10800) }
}

/** Immutable session/preset configuration; audio permissions are deliberately separate. */
data class StopwatchReminderRules(val primary: StopwatchRule = StopwatchRule(),
    val middle: StopwatchRule = StopwatchRule(intervalSeconds=900),
    val high: StopwatchRule = StopwatchRule(intervalSeconds=1800)) {
    fun rule(level: StopwatchLevel) = when(level) {
        StopwatchLevel.PRIMARY -> primary; StopwatchLevel.MIDDLE -> middle; StopwatchLevel.HIGH -> high
    }
    fun changed(level: StopwatchLevel,value: StopwatchRule) = when(level) {
        StopwatchLevel.PRIMARY -> copy(primary=value); StopwatchLevel.MIDDLE -> copy(middle=value); StopwatchLevel.HIGH -> copy(high=value)
    }
    val reserveStatus get() = StopwatchLevel.entries.any { rule(it).enabled && OvertimeEffect.STATUS in rule(it).effects }
}

data class StopwatchReminderPermissions(val sound: Boolean = false,val vibration: Boolean = false)

/** Countdown paths never depend on the unrelated stopwatch configuration store. */
internal fun resolveStopwatchRules(mode: TimerMode,readRules: ()->StopwatchReminderRules?): StopwatchReminderRules? =
    if(mode==TimerMode.STOPWATCH) readRules() else StopwatchReminderRules()

data class StopwatchCue(val id: Long,val level: StopwatchLevel,val effects: Set<OvertimeEffect>,val elapsedMillis: Long,
                        val colors: List<Int> = emptyList(), val colorMode: StopwatchColorMode = StopwatchColorMode.TRANSITION) {
    fun allowed(permissions: StopwatchReminderPermissions) = copy(effects=effects.filterTo(linkedSetOf()) {
        when(it) { OvertimeEffect.SOUND -> permissions.sound; OvertimeEffect.VIBRATION -> permissions.vibration; else -> true }
    })
    fun visualCue() = OvertimeCue(id,effects,OvertimeLimit.STANDARD,0,0,0,elapsedMillis,0)
}

object StopwatchReminderCodec {
    fun encode(rules: StopwatchReminderRules): Map<String,Any> = StopwatchLevel.entries.associate { level ->
        val rule=rules.rule(level)
        level.key to mapOf("enabled" to rule.enabled,"intervalSeconds" to rule.intervalSeconds,
            "effects" to rule.effects.sortedBy { it.ordinal }.joinToString(",") { it.name },
            "color" to mapOf("mode" to rule.color.mode.name, "a" to rule.color.a, "b" to rule.color.b))
    }
    /** Missing whole fields are handled by the legacy caller; explicit new corruption never means OFF. */
    fun decode(raw: Any?): StopwatchReminderRules? = try {
        val map=raw as? Map<*,*> ?: return null
        var result=StopwatchReminderRules()
        for(level in StopwatchLevel.entries) {
            val value=map[level.key] as? Map<*,*> ?: return null
            val enabled=value["enabled"] as? Boolean ?: return null
            val seconds=(value["intervalSeconds"] as? Number)?.toString()?.toBigDecimal()?.intValueExact() ?: return null
            val names=value["effects"] as? String ?: return null
            val effects=if(names.isEmpty()) emptySet() else names.split(',').map { name ->
                OvertimeEffect.entries.firstOrNull { it.name==name } ?: return null
            }.toSet()
            val color=if (!value.containsKey("color")) StopwatchColorSettings() else {
                val fields=value["color"] as? Map<*,*> ?: return null
                val mode=StopwatchColorMode.entries.firstOrNull { it.name==fields["mode"] } ?: return null
                val a=(fields["a"] as? Number)?.toString()?.toBigDecimal()?.intValueExact() ?: return null
                val b=(fields["b"] as? Number)?.toString()?.toBigDecimal()?.intValueExact() ?: return null
                StopwatchColorSettings(mode,a,b)
            }
            result=result.changed(level,StopwatchRule(enabled,seconds,effects,color))
        }
        result
    } catch (_: RuntimeException) { null }
}

/** Pure deadlines in cumulative RUNNING milliseconds. No wall clock, UI ticks or second timer. */
class StopwatchReminderEngine(private val rules: StopwatchReminderRules, initialElapsed: Long,
    private val randomIndex: (Int)->Int = { kotlin.random.Random.nextInt(it) }) {
    private var lastElapsed=initialElapsed
    private var serial=0L
    private val next=StopwatchLevel.entries.associateWith { level ->
        rules.rule(level).takeIf { it.enabled }?.let { following(initialElapsed,it.intervalSeconds*1000L) }
    }.toMutableMap()
    init { require(initialElapsed>=0) }
    val nextElapsed: Long? get()=next.values.filterNotNull().minOrNull()

    fun update(elapsed: Long,paused: Boolean): StopwatchCue? {
        require(elapsed>=0)
        if(elapsed<lastElapsed) return null
        lastElapsed=elapsed
        val due=StopwatchLevel.entries.filter { next[it]?.let { at -> elapsed>=at }==true }
        if(due.isEmpty()) return null
        due.forEach { next[it]=following(elapsed,rules.rule(it).intervalSeconds*1000L) }
        // Pause may reach the queue just ahead of a delayed due callback. Mark
        // passed cycles consumed without replaying them when the user resumes.
        if(paused) return null
        val selected=due.maxBy { it.ordinal }
        val rule=rules.rule(selected)
        return StopwatchCue(++serial,selected,rule.effects.toSet(),elapsed,rule.color.sample(randomIndex),rule.color.mode)
    }
    private fun following(elapsed: Long,period: Long): Long? {
        val completed=elapsed/period
        return if(completed>=Long.MAX_VALUE/period) null else (completed+1)*period
    }
}
