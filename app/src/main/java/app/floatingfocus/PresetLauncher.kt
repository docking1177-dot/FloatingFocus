package app.floatingfocus

import android.content.Context
import androidx.lifecycle.Lifecycle

/** The home and desktop use the same current-ID lookup and launch gates. */
internal fun checkPresetLaunch(context: Context, id: String?): PresetLaunchCheck = PresetLaunchPolicy.check(
    id, TimerPresetStore.get(context).snapshot(),
    FloatingService.session.value.active || FloatingService.monitoring.value.enabled || FloatingService.startPending || appTimerLaunchGate.busy,
    context::isSavedShortcut, { app -> runCatching { context.packageManager.getLaunchIntentForPackage(app.packageName) != null }.getOrDefault(false) },
    OverlayPermission.granted(context))

internal suspend fun launchPresetWithTimer(context: Context, lifecycle: Lifecycle, id: String): String? {
    val check = checkPresetLaunch(context, id)
    if (check.issue != null) return check.message
    val preset = check.preset!!
    return launchAppWithTimer(context, lifecycle, preset.targetApp!!, preset.durationSeconds, preset.settings, preset.mode, preset.id)
}
