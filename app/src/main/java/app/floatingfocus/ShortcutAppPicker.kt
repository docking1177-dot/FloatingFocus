package app.floatingfocus

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Only FF's saved shortcuts; never queries the installed-app catalog. */
@Composable
fun ShortcutAppPicker(current: LaunchableApp?, onChange: (LaunchableApp) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("quick_launch", Context.MODE_PRIVATE) }
    var targets by remember { mutableStateOf(prefs.readTargets()) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> targets = prefs.readTargets() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Text("打开的 App", style = MaterialTheme.typography.titleSmall)
    if (targets.isEmpty()) Text("请先在首页「管理常用 App」中添加应用。", style = MaterialTheme.typography.bodySmall)
    if (current != null && targets.none { it.packageName == current.packageName })
        Text("${current.label} 已不在快捷启动中，请重新选择。", style = MaterialTheme.typography.bodySmall)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
        items(targets, key = { it.packageName }) { app ->
            FilterChip(selected = app.packageName == current?.packageName, onClick = { onChange(app) },
                label = { Text(app.label) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

internal fun Context.isSavedShortcut(app: LaunchableApp?): Boolean = app != null &&
    getSharedPreferences("quick_launch", Context.MODE_PRIVATE).readTargets().any { it.packageName == app.packageName }
