package app.floatingfocus

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Explicit one-shot user action. Never observes which app is in the foreground. */
@Composable
fun QuickLaunchCard(minutes: Int, enabled: Boolean, onBusyChange: (Boolean) -> Unit, onPermissionRequired: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("quick_launch", Context.MODE_PRIVATE) }
    var target by remember { mutableStateOf(prefs.getString("package", "").orEmpty()) }
    var label by remember { mutableStateOf(prefs.getString("label", "").orEmpty()) }
    var choosing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column {
        Text("打开 App，开始专注", style = MaterialTheme.typography.titleSmall)
        TextButton(enabled = enabled && !busy, onClick = { choosing = true }) {
            Text(if (target.isEmpty()) "选择目标 App" else "目标：$label · 更换")
        }
        Button(enabled = enabled && !busy && target.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
            error = null
            if (!Settings.canDrawOverlays(context)) onPermissionRequired()
            else {
                busy = true
                onBusyChange(true)
                scope.launch {
                    var ownsTimer = false
                    var opened = false
                    try {
                        val intent = context.packageManager.getLaunchIntentForPackage(target)
                        if (intent == null) {
                            error = "该 App 已卸载、停用或无法打开，请重新选择。"
                        } else if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            error = "请返回主界面后再试。"
                        } else {
                            ownsTimer = FloatingService.start(context, minutes * 60_000L)
                            if (ownsTimer) {
                                val result = withTimeout(5000) { FloatingService.session.first { it.active || it.error != null } }
                                if (result.error != null) error = result.error
                                else if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    error = "启动已取消，请在主界面重新点击。"
                                } else {
                                    context.startActivity(intent)
                                    opened = true
                                }
                            } else error = FloatingService.session.value.error ?: "已有计时正在运行，请先结束。"
                        }
                    } catch (_: TimeoutCancellationException) {
                        error = "计时器启动超时，请重试。"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: RuntimeException) {
                        error = "无法打开目标 App，计时已取消，请重新选择或重试。"
                    } finally {
                        // Only roll back the timer started by this action, never an existing timer.
                        if (ownsTimer && !opened) FloatingService.stop(context)
                        busy = false
                        onBusyChange(false)
                    }
                }
            }
        }) { Text(if (busy) "正在启动…" else if (target.isEmpty()) "打开 App 并计时" else "打开 $label 并计时") }
        Text("使用上方所选的 $minutes 分钟；离开目标 App 后计时仍继续。", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (choosing) {
        var search by remember { mutableStateOf("") }
        var apps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
        var loaded by remember { mutableStateOf(false) }
        var loadError by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            try { apps = withContext(Dispatchers.IO) { context.launchableApps() } }
            catch (_: RuntimeException) { loadError = true }
            loaded = true
        }
        AlertDialog(onDismissRequest = { choosing = false }, title = { Text("选择目标 App") }, text = {
            Column {
                OutlinedTextField(search, { search = it }, label = { Text("搜索名称或包名") }, singleLine = true)
                val matching = apps.filter { it.label.contains(search, true) || it.packageName.contains(search, true) }
                if (!loaded) Text("正在加载…")
                else if (loadError) Text("读取失败，请关闭后重试。")
                else if (matching.isEmpty()) Text("未找到可打开的 App")
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(matching, key = { it.packageName }) { app ->
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            target = app.packageName
                            label = app.label
                            prefs.edit { putString("package", target); putString("label", label) }
                            error = null
                            choosing = false
                        }) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(app.label)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("取消") } })
    }
}
