package app.floatingfocus

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Shared by the existing home flow and tutorial. The system remains the only grant authority. */
internal object OverlayPermission {
    fun granted(context: Context): Boolean = Settings.canDrawOverlays(context)
    fun request(activity: Activity): Boolean {
        for (intent in listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${activity.packageName}".toUri()),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))) {
            try { activity.startActivity(intent); return true }
            catch (_: ActivityNotFoundException) { /* Try the system list on OEMs without an app-specific page. */ }
            catch (_: SecurityException) { /* Leave the app usable and show the caller's guidance. */ }
        }
        return false
    }
}

@Composable
internal fun rememberOverlayPermission(): State<Boolean> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val allowed = remember(context) { mutableStateOf(OverlayPermission.granted(context)) }
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed.value = OverlayPermission.granted(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return allowed
}
