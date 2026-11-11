package app.floatingfocus

/** Admission and compatibility projection, shared by every start entry. */
object DualOverlayPolicy {
    const val LIMIT = 2
    fun primary(rows: List<SessionState>) = rows.firstOrNull { it.active }
    fun rejection(rows: List<SessionState>, c4: Boolean): String? = when {
        rows.count { it.active } >= LIMIT -> "最多同时运行两个浮窗，请先结束其中一个。"
        c4 && rows.any { it.active && it.c4Enabled } -> "已有 C4 会话；可另开普通计时，同时最多一个 C4。"
        else -> null
    }
}
