package app.floatingfocus

/** Debug-only value model. Signed remaining time: negative means overtime. Never stores a SessionState. */
data class LabSimulation(
    val durationMillis: Long = 900_000,
    val signedRemainingMillis: Long = 900_000,
    val settings: ReminderSettings = ReminderSettings(),
    val acknowledgedStage: ReminderStage? = null,
    val playback: Countdown? = null
) {
    init {
        require(durationMillis in 1..Countdown.MAX_DURATION_MILLIS)
        require(signedRemainingMillis in -Countdown.MAX_DURATION_MILLIS..durationMillis)
    }
    val snapshot: ReminderSnapshot get() = ReminderSnapshot.at(Countdown(durationMillis, 0), durationMillis - signedRemainingMillis)
    val appearance: ReminderPresentation get() = ReminderPresenter.present(snapshot, settings, acknowledgedStage)

    fun seek(remaining: Long) = copy(signedRemainingMillis = remaining.coerceIn(-Countdown.MAX_DURATION_MILLIS, durationMillis),
        acknowledgedStage = null, playback = null)
    fun confirm() = copy(acknowledgedStage = snapshot.stage)
    fun play(now: Long) = copy(playback = Countdown(durationMillis, now - (durationMillis - signedRemainingMillis)))
    fun tick(now: Long): LabSimulation {
        val clock = playback ?: return this
        val frame = ReminderSnapshot.at(clock, now)
        val remaining = (frame.remainingMillis - frame.overtimeMillis).coerceAtLeast(-Countdown.MAX_DURATION_MILLIS)
        return copy(signedRemainingMillis = remaining,
            playback = if (remaining == -Countdown.MAX_DURATION_MILLIS) null else clock)
    }
}
