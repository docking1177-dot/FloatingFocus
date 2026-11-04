package app.floatingfocus

/** One set of capabilities for floating UI, home, widgets and the system tile. Service rechecks its clock. */
object SessionControls {
    /** Legacy unbound STOP remains compatible for ordinary sessions, never for screen-driven ones. */
    fun acceptsStop(s: SessionState, request: String?, leg: Long) = !s.screenDriven ||
        (canEnd(s) && request != null && request == s.startRequestId && leg == s.timerStartedAt)
    fun canEnd(s: SessionState) = s.active && !s.automatic && !s.c4Locked
    fun canPause(s: SessionState) = s.active && !s.automatic && !s.screenDriven && !s.c4Enabled && !s.c4Locked && !s.c4Active &&
        !s.paused && (s.mode == TimerMode.STOPWATCH || s.remainingMillis > 0)
    fun canResume(s: SessionState) = s.active && !s.automatic && !s.screenDriven && !s.c4Enabled && !s.c4Locked && !s.c4Active && s.paused
    fun canExtend(s: SessionState) = s.active && !s.automatic && !s.c4Locked && !s.c4Active &&
        s.mode == TimerMode.COUNTDOWN && s.stage == ReminderStage.FINISHED
}
