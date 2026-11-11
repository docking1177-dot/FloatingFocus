package app.floatingfocus

import android.content.Context
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

internal val appTimerLaunchGate = AppTimerLaunchGate()

/** Shared by saved presets and the existing quick launcher. Called from a visible UI on Main. */
suspend fun launchAppWithTimer(context: Context, lifecycle: Lifecycle, app: LaunchableApp,
                              seconds: Int, settings: ReminderSettings? = null, mode: TimerMode = TimerMode.COUNTDOWN,
                              presetId: String? = null, c4Enabled: Boolean? = null, useDailyQuota: Boolean = false,
                              stopwatchBehavior: StopwatchBehavior? = null,
                              stopwatchReminders: StopwatchReminderRules? = null): String? {
    if (FloatingService.atCapacity || FloatingService.monitoring.value.enabled || FloatingService.startPending || appTimerLaunchGate.busy)
        return "最多同时运行两个浮窗，或已有启动请求。"
    var ownsTimer = false
    var opened = false
    val store = ReminderSettingsStore.get(context)
    val requestId = java.util.UUID.randomUUID().toString()
    if (!appTimerLaunchGate.acquire()) return "已有计时正在启动，请稍后再试。"
    try {
        val quotas = AppQuotaStore.get(context).refresh()
        quotas.error?.let { return it }
        val duration = if (useDailyQuota) {
            if (mode != TimerMode.COUNTDOWN) return "今日额度仅可用作倒计时来源。"
            val quota = quotas.summary(app.packageName)
            if (quota?.enabled != true) return "请先在 App 管理中设置并启用该 App 的每日额度。"
            if (quota.remainingMillis <= 0) return "该 App 今日额度已用完。"
            quota.remainingMillis
        } else seconds * 1000L
        (settings ?: store.settings.value).strategy.validationError(duration, mode)?.let { return it }
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return "该 App 已卸载、停用或无法打开，请重新选择。"
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return "请返回主界面后再试。"
        ownsTimer = FloatingService.start(context, duration, mode, app.packageName, presetId, requestId,
            c4Enabled = c4Enabled, stopwatchBehavior = stopwatchBehavior, stopwatchReminders = stopwatchReminders, appearance = settings, appName = app.label)
        if (!ownsTimer) return FloatingService.latestStart.value.error ?: "最多同时运行两个浮窗，或已有启动请求。"
        val result = withTimeout(5000) { FloatingService.startResults.first { it[requestId]?.let { row -> row.active || row.error != null } == true }[requestId]!! }
        if (result.error != null) return result.error
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return "启动已取消，请在主界面重新点击。"
        context.startActivity(intent)
        FloatingService.appOpened(context, app, requestId)
        opened = true
        return null
    } catch (_: TimeoutCancellationException) {
        return "计时器启动超时，请重试。"
    } catch (e: CancellationException) {
        throw e
    } catch (_: RuntimeException) {
        return "无法打开目标 App，计时已取消，请重新选择或重试。"
    } finally {
        try {
            if (!opened) {
                if (ownsTimer) FloatingService.cancelFailedLaunch(context, requestId)
            }
        } finally { appTimerLaunchGate.release() }
    }
}
