package app.floatingfocus

/** Implementation profile, NOT a measured CS2 timeline. May be replaced by verified cue offsets. */
data class C4Profile(
    val windowMillis: Long = 40_000,
    val finalMillis: Long = 10_000,
    val normalPulseMillis: Long = 80,
    val finalPulseMillis: Long = 120,
    val maxLateMillis: Long = 100,
    val baseIntervalMillis: Long = 100,
    val minimumIntervalMillis: Long = 150,
    val intervalSlope: Double = .9,
    val measuredRemainingMillis: List<Long> = emptyList()
) {
    init {
        require(windowMillis == 40_000L && finalMillis in 1..windowMillis)
        require(normalPulseMillis in 1..minimumIntervalMillis && finalPulseMillis in 1..minimumIntervalMillis)
        require(maxLateMillis in 0..120 && baseIntervalMillis in 1..2000 && minimumIntervalMillis in 120..2000)
        require(intervalSlope.isFinite() && intervalSlope in 0.0..2.0)
        require(measuredRemainingMillis.all { it in 1..windowMillis })
        require(measuredRemainingMillis.zipWithNext().all { (a, b) -> a > b && a-b >= 120 })
    }
    fun interval(remaining: Long) = maxOf(minimumIntervalMillis,
        (baseIntervalMillis + intervalSlope * remaining / 40.0).toLong())
}

enum class C4Tone { NORMAL, FINAL, EXPLOSION }
data class C4Cue(val index: Int, val remainingMillis: Long, val pulseMillis: Long, val tone: C4Tone)
data class C4Frame(val active: Boolean = false, val led: Boolean = false, val finalPhase: Boolean = false)
data class C4Sample(val frame: C4Frame, val cue: C4Cue?, val nextDelayMillis: Long?)

/** One absolute 40s timeline, aligned to the real timer deadline, including short starts. */
class C4Timeline(val profile: C4Profile = C4Profile()) {
    val cues: List<C4Cue> = (profile.measuredRemainingMillis.ifEmpty {
        buildList {
            var remaining = profile.windowMillis
            while (remaining > 0) { add(remaining); remaining -= profile.interval(remaining) }
        }
    }).mapIndexed { i, remaining ->
        val final = remaining <= profile.finalMillis
        C4Cue(i, remaining, if (final) profile.finalPulseMillis else profile.normalPulseMillis,
            if (final) C4Tone.FINAL else C4Tone.NORMAL)
    }
    fun indexAt(remaining: Long) = cues.indexOfLast { it.remainingMillis >= remaining }
    fun frame(remaining: Long): C4Frame {
        if (remaining !in 1..profile.windowMillis) return C4Frame()
        val cue = cues.getOrNull(indexAt(remaining))
        return C4Frame(true, cue != null && cue.remainingMillis - remaining < cue.pulseMillis,
            remaining <= profile.finalMillis)
    }
}

/** Cursor consumes at most one current cue; delayed callbacks never replay a catch-up burst. */
class C4Cursor(private val timeline: C4Timeline = C4Timeline()) {
    private var consumedIndex = -1
    private var ledOffAtRemaining = Long.MAX_VALUE
    fun sample(remaining: Long, paused: Boolean = false, consumeCues: Boolean = true): C4Sample {
        if (remaining <= 0) return C4Sample(C4Frame(), null, null)
        if (remaining > timeline.profile.windowMillis)
            return C4Sample(C4Frame(), null, if (paused) null else remaining - timeline.profile.windowMillis)
        val index = timeline.indexAt(remaining)
        val cue = timeline.cues.getOrNull(index)
        val next = timeline.cues.getOrNull(index + 1)
        // A late cue must still finish before the next cue/deadline; never overlap clips.
        val available = cue?.let { it.remainingMillis - (next?.remainingMillis ?: 0) - it.pulseMillis } ?: 0
        val play = !paused && consumeCues && index > consumedIndex && cue != null &&
            cue.remainingMillis - remaining <= minOf(timeline.profile.maxLateMillis, available)
        if (!paused && consumeCues) consumedIndex = maxOf(consumedIndex, index)
        if (play) ledOffAtRemaining = remaining - cue!!.pulseMillis
        val frame = C4Frame(true, remaining > ledOffAtRemaining, remaining <= timeline.profile.finalMillis)
        val nextCue = next?.let { remaining - it.remainingMillis }
        val off = (remaining - ledOffAtRemaining).takeIf { it > 0 }
        return C4Sample(frame, if (play) cue else null,
            if (paused) null else listOfNotNull(nextCue, off, remaining).filter { it > 0 }.minOrNull())
    }
}

/** Pausing changes the sampled clock, not Countdown's duration, identity, or clock algorithm. */
class SessionClock {
    private var pausedAt: Long? = null
    private var pausedMillis = 0L
    val paused: Boolean get() = pausedAt != null
    fun at(elapsedRealtime: Long): Long = (pausedAt ?: elapsedRealtime) - pausedMillis
    fun pause(now: Long) { if (!paused) pausedAt = now }
    fun resume(now: Long) { pausedAt?.let { pausedMillis += (now - it).coerceAtLeast(0); pausedAt = null } }
}

/** Per-leg arm/consume state, shared by real callbacks and tests. Muting still consumes expiry. */
class C4EffectState(private val timeline: C4Timeline = C4Timeline()) {
    private var leg: Long? = null
    private var cursor = C4Cursor(timeline)
    private var armed = false
    private var completed = false
    fun update(key: Long, mode: TimerMode, remaining: Long, enabled: Boolean, paused: Boolean, consumeCues: Boolean = true): C4Sample {
        if (leg != key) { leg = key; cursor = C4Cursor(timeline); armed = false; completed = false }
        if (!enabled || mode != TimerMode.COUNTDOWN) { armed = false; return C4Sample(C4Frame(), null, null) }
        if (completed) return C4Sample(C4Frame(), null, null)
        val sample = cursor.sample(remaining, paused, consumeCues)
        if (sample.frame.active) armed = true
        return sample
    }
    fun complete(sound: Boolean): Boolean {
        if (completed) return false
        completed = true
        val result = armed && sound
        armed = false
        return result
    }
    fun cancel() { armed = false; completed = true }
}
