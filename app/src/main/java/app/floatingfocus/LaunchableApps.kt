package app.floatingfocus

import android.content.Context
import android.content.Intent

data class LaunchableApp(val packageName: String, val label: String)

/** Shared by the quick launcher and the retained, deferred auto-timer settings. */
@Suppress("DEPRECATION")
fun Context.launchableApps(): List<LaunchableApp> {
    val homes = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        .map { it.activityInfo.packageName }.toSet()
    return packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .filter { it.activityInfo.packageName != packageName && it.activityInfo.packageName !in homes }
        .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
        .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
}
