package app.floatingfocus

import android.content.Context
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Shared by saved presets and the existing quick launcher. Called from a visible UI on Main. */
suspend fun launchAppWithTimer(context: Context, lifecycle: Lifecycle, app: LaunchableApp,
                              seconds: Int, settings: ReminderSettings? = null, mode: TimerMode = TimerMode.COUNTDOWN,
                              presetId: String? = null): String? {
    if (FloatingService.session.value.active || FloatingService.monitoring.value.enabled)
        return "已有计时正在运行，请先结束。"
    (settings ?: ReminderSettingsStore.get(context).settings.value).strategy.validationError(seconds * 1000L, mode)?.let { return it }
    var ownsTimer = false
    var opened = false
    val store = ReminderSettingsStore.get(context)
    val previous = store.settings.value
    var applied: ReminderSettings? = null
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return "该 App 已卸载、停用或无法打开，请重新选择。"
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return "请返回主界面后再试。"
        if (settings != null) {
            store.update { settings }
            applied = store.settings.value
        }
        ownsTimer = FloatingService.start(context, seconds * 1000L, mode, app.packageName, presetId)
        if (!ownsTimer) return FloatingService.session.value.error ?: "已有计时正在运行，请先结束。"
        val result = withTimeout(5000) { FloatingService.session.first { it.active || it.error != null } }
        if (result.error != null) return result.error
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return "启动已取消，请在主界面重新点击。"
        context.startActivity(intent)
        FloatingService.appOpened(context, app)
        opened = true
        return null
    } catch (_: TimeoutCancellationException) {
        return "计时器启动超时，请重试。"
    } catch (e: CancellationException) {
        throw e
    } catch (_: RuntimeException) {
        return "无法打开目标 App，计时已取消，请重新选择或重试。"
    } finally {
        if (!opened) {
            if (ownsTimer) FloatingService.stop(context)
            // Do not overwrite a later settings edit while a launch was pending.
            if (applied != null && store.settings.value == applied) store.update { previous }
        }
    }
}
