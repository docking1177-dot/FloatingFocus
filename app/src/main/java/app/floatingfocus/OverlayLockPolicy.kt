package app.floatingfocus

/** Explicit desired state makes repeated commands safe; neither ID nor leg may be omitted. */
data class OverlayLockRequest(val sessionId: String, val legStartedAt: Long, val locked: Boolean)

object OverlayLockPolicy {
    fun accepts(state: SessionState, request: OverlayLockRequest): Boolean = state.active && !state.automatic &&
        request.sessionId.isNotBlank() && request.sessionId == state.startRequestId &&
        request.legStartedAt == state.timerStartedAt

    fun apply(state: SessionState, request: OverlayLockRequest): SessionState? =
        if (accepts(state, request)) state.copy(overlayLocked = request.locked) else null

    /** Android 12+ checks the native window alpha, rather than the drawn pixel opacity. */
    fun displayOpacity(savedOpacity: Float, locked: Boolean, maximumObscuringOpacity: Float?): Float {
        val saved = if (savedOpacity.isFinite()) savedOpacity.coerceIn(0f, 1f) else 1f
        if (!locked || maximumObscuringOpacity == null) return saved
        val limit = if (maximumObscuringOpacity.isFinite()) maximumObscuringOpacity.coerceIn(0f, 1f) else 0f
        return minOf(saved, limit)
    }
}
