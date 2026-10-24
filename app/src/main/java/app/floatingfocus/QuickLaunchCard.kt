package app.floatingfocus

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Explicit one-shot user action. Never observes which app is in the foreground. */
@Composable
fun QuickLaunchCard(minutes: Int, enabled: Boolean, onBusyChange: (Boolean) -> Unit, onPermissionRequired: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("quick_launch", Context.MODE_PRIVATE) }
    var targets by remember { mutableStateOf(prefs.readTargets()) }
    var choosing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val launch: (LaunchableApp) -> Unit = { app ->
        if (enabled && !busy) {
            error = null
            if (!Settings.canDrawOverlays(context)) onPermissionRequired()
            else {
                busy = true
                onBusyChange(true)
                scope.launch {
                    try {
                        error = launchAppWithTimer(context, lifecycle, app, minutes)
                    } finally {
                        busy = false
                        onBusyChange(false)
                    }
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("打开 App 并计时", style = MaterialTheme.typography.titleSmall)
        TextButton(enabled = enabled && !busy, onClick = { choosing = true }) {
            Text(if (targets.isEmpty()) "添加常用 App" else "管理常用 App（${targets.size}）")
        }
        val layout = quickLaunchLayoutPicker()
        QuickLaunchButtons(targets, layout, enabled && !busy, launch)
        if (busy) Text("正在启动…")
        if (targets.isNotEmpty()) Text("离开 App 后计时仍继续。", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (choosing) {
        var selected by remember { mutableStateOf(targets.map { it.packageName }.toSet()) }
        var search by remember { mutableStateOf("") }
        var apps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
        var loaded by remember { mutableStateOf(false) }
        var loadError by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            try { apps = withContext(Dispatchers.IO) { context.launchableApps() } }
            catch (_: RuntimeException) { loadError = true }
            loaded = true
        }
        AlertDialog(onDismissRequest = { choosing = false }, title = { Text("管理常用 App") }, text = {
            Column {
                OutlinedTextField(search, { search = it }, label = { Text("搜索名称或包名") }, singleLine = true)
                Text("已选 ${selected.size} 个 · 可多选")
                val matching = (apps + targets).distinctBy { it.packageName }
                    .filter { it.label.contains(search, true) || it.packageName.contains(search, true) }
                if (!loaded) Text("正在加载…")
                else if (loadError) Text("读取失败，请关闭后重试。")
                else if (matching.isEmpty()) Text("未找到可打开的 App")
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(matching, key = { it.packageName }) { app ->
                        Row(Modifier.fillMaxWidth().toggleable(app.packageName in selected, role = Role.Checkbox) {
                            selected = if (it) selected + app.packageName else selected - app.packageName
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(app.packageName in selected, onCheckedChange = null)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(app.label + if (loaded && apps.none { it.packageName == app.packageName }) "（不可用）" else "")
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }, confirmButton = {
            TextButton(enabled = loaded && !loadError, onClick = {
                targets = QuickLaunchTargets.applySelection(targets, apps, selected)
                prefs.writeTargets(targets)
                error = null
                choosing = false
            }) { Text("保存") }
        }, dismissButton = { TextButton(onClick = { choosing = false }) { Text("取消") } })
    }
}

@Composable
private fun QuickLaunchButtons(targets: List<LaunchableApp>, layout: QuickLaunchLayout, enabled: Boolean, launch: (LaunchableApp) -> Unit) {
    when (layout) {
        QuickLaunchLayout.LIST -> targets.forEach { app ->
            Button(onClick = { launch(app) }, enabled = enabled, modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = "打开 ${app.label} 并计时" }) {
                Text("打开 ${app.label} 并计时", maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        QuickLaunchLayout.GRID -> targets.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { app -> QuickLaunchTile(app, enabled, Modifier.weight(1f), launch) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        QuickLaunchLayout.STRIP -> LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(targets, key = { it.packageName }) { app -> QuickLaunchTile(app, enabled, Modifier.width(156.dp), launch) }
        }
    }
}

@Composable
private fun QuickLaunchTile(app: LaunchableApp, enabled: Boolean, modifier: Modifier, launch: (LaunchableApp) -> Unit) {
    Button(onClick = { launch(app) }, enabled = enabled, modifier = modifier.heightIn(min = 80.dp)
        .semantics { contentDescription = "打开 ${app.label} 并计时" }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(app.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("打开并计时", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun SharedPreferences.readTargets(): List<LaunchableApp> {
    val legacy = getString("package", null)?.let { LaunchableApp(it, getString("label", "").orEmpty()) }
    val saved = getString("targets", null)?.let { raw ->
        try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let {
                LaunchableApp(it.optString("package"), it.optString("label"))
            } }
        } catch (_: org.json.JSONException) { null }
    }
    return QuickLaunchTargets.restore(saved, legacy)
}

private fun SharedPreferences.writeTargets(targets: List<LaunchableApp>) {
    val array = JSONArray()
    targets.forEach { array.put(JSONObject().put("package", it.packageName).put("label", it.label)) }
    edit { putString("targets", array.toString()) }
}
