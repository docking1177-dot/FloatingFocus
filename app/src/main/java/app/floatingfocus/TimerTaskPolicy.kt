package app.floatingfocus

/** Transient, excluded entry tasks finish themselves; the real FF task still stops a session. */
internal object TimerTaskPolicy {
    fun endsSession(rootComponent: String?): Boolean = rootComponent !in setOf(
        "app.floatingfocus.WidgetStartActivity", "app.floatingfocus.PresetShortcutActivity")
}
