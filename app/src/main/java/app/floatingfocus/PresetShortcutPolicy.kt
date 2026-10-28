package app.floatingfocus

/** Identity is independent of name, App, display style and all mutable preset settings. */
object PresetShortcutIdentity {
    private const val PREFIX = "ff.preset."
    fun valid(id: String?): Boolean = id != null && id.isNotBlank() && id.length <= 128 &&
        id == id.trim() && id.none { it.isISOControl() }
    fun shortcutId(id: String): String { require(valid(id)); return PREFIX + id }
    fun presetId(shortcutId: String): String? = shortcutId.takeIf { it.startsWith(PREFIX) }
        ?.removePrefix(PREFIX)?.takeIf(::valid)
}

enum class PresetLaunchIssue { INVALID_ID, READ_FAILED, MISSING, ACTIVE, BINDING, UNAVAILABLE, PERMISSION, STRATEGY, UNSUPPORTED }
data class PresetLaunchCheck(val preset: TimerPreset? = null, val issue: PresetLaunchIssue? = null, val message: String? = null)

/** A late pin confirmation reconciles against current storage, never the requested display snapshot. */
data class PresetShortcutSyncPlan(val disable: List<String>, val update: List<TimerPreset>) {
    companion object {
        fun from(items: List<TimerPreset>?, pinned: List<String>): PresetShortcutSyncPlan? {
            if (items == null) return null
            val byId = items.associateBy { it.id }
            val ours = pinned.mapNotNull { shortcut -> PresetShortcutIdentity.presetId(shortcut)?.let { shortcut to it } }
            return PresetShortcutSyncPlan(ours.filter { it.second !in byId }.map { it.first },
                ours.mapNotNull { byId[it.second] })
        }
    }
}

object PresetLaunchPolicy {
    fun forPin(id: String?, items: List<TimerPreset>?, supported: Boolean,
               saved: (LaunchableApp) -> Boolean, available: (LaunchableApp) -> Boolean): PresetLaunchCheck {
        if (!supported) return PresetLaunchCheck(issue = PresetLaunchIssue.UNSUPPORTED, message = "当前桌面不支持添加快捷方式。")
        // Creation is separate from starting: no permission, session or reminder-strategy gate.
        val check = check(id, items, false, saved, available, true)
        return if (check.issue == PresetLaunchIssue.STRATEGY) PresetLaunchCheck(check.preset) else check
    }
    fun check(id: String?, items: List<TimerPreset>?, active: Boolean,
              saved: (LaunchableApp) -> Boolean, available: (LaunchableApp) -> Boolean,
              permission: Boolean): PresetLaunchCheck {
        if (!PresetShortcutIdentity.valid(id)) return PresetLaunchCheck(issue = PresetLaunchIssue.INVALID_ID, message = "快捷方式无效，请在 FF 中重新添加。")
        if (items == null) return PresetLaunchCheck(issue = PresetLaunchIssue.READ_FAILED, message = "暂时无法读取预设，请重试。")
        val preset = items.firstOrNull { it.id == id }
            ?: return PresetLaunchCheck(issue = PresetLaunchIssue.MISSING, message = "该预设已删除，请在 FF 中重新添加。")
        fun reject(issue: PresetLaunchIssue, message: String) = PresetLaunchCheck(preset, issue, message)
        if (active) return reject(PresetLaunchIssue.ACTIVE, "已有计时正在运行，请先结束。")
        val app = preset.targetApp
        if (app == null || !saved(app)) return reject(PresetLaunchIssue.BINDING, "请先为预设绑定 FF 中的快捷 App。")
        if (!available(app)) return reject(PresetLaunchIssue.UNAVAILABLE, "该 App 已卸载、停用或无法打开，请重新选择。")
        if (!permission) return reject(PresetLaunchIssue.PERMISSION, "请允许 Floating Focus 显示悬浮窗，再继续启动。")
        preset.settings.strategy.validationError(preset.durationSeconds * 1000L, preset.mode)?.let {
            return reject(PresetLaunchIssue.STRATEGY, it)
        }
        return PresetLaunchCheck(preset)
    }
}

/** No cancellation callback is promised; rapid prompts are merged for a bounded two seconds only. */
class PresetPinThrottle {
    private var requestedAt: Long? = null
    fun acquire(now: Long): Boolean {
        if (requestedAt?.let { now >= it && now - it < 2000 } == true) return false
        requestedAt = now
        return true
    }
    fun reset() { requestedAt = null }
}

/** Main-thread admission, including the gap before the service publishes its first active state. */
class AppTimerLaunchGate {
    var busy = false
        private set
    fun acquire(): Boolean { if (busy) return false; busy = true; return true }
    fun release() { busy = false }
}
