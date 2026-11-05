package app.floatingfocus

/** Commit a linked record only after observer registration and its first synchronous sample succeed. */
internal object TimerStartupGate {
    fun initialize(prepare: () -> Unit, valid: () -> Boolean, commit: () -> Unit): Boolean {
        prepare()
        if (!valid()) return false
        commit()
        return true
    }
}
