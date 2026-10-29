package app.floatingfocus

enum class PresetPinPhase { CHECKING, NOT_SENT, UNSUPPORTED, WAITING, UNCONFIRMED, CONFIRMED }

/** Lack of a launcher callback never means cancellation or success. */
data class PresetPinFeedback(val presetId: String, val phase: PresetPinPhase, val message: String,
                             val canOpenSettings: Boolean = false) {
    fun afterWaiting(xiaomi: Boolean): PresetPinFeedback = if (phase != PresetPinPhase.WAITING) this else copy(
        phase = PresetPinPhase.UNCONFIRMED,
        message = "尚未收到桌面确认。若已取消，可重新添加。" + PresetPinHelp.instructions(xiaomi),
        canOpenSettings = true)
}

object PresetPinHelp {
    fun isXiaomi(manufacturer: String, brand: String): Boolean = listOf(manufacturer, brand).any {
        it.equals("xiaomi", true) || it.equals("redmi", true)
    }
    fun instructions(xiaomi: Boolean): String = if (xiaomi)
        "若未弹出提示，请在 FF 应用信息 → 权限管理／其他权限中允许「桌面快捷方式」；同时检查桌面布局是否锁定。"
    else "若未弹出提示，请检查当前桌面和应用的快捷方式设置。"
}
