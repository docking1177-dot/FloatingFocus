package app.floatingfocus

/** Task removal is a navigation event, never an explicit end of a foreground timer. */
internal object TimerTaskPolicy {
    fun endsSession(@Suppress("UNUSED_PARAMETER") rootComponent: String?): Boolean = false
}
