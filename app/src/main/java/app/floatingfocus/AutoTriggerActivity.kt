package app.floatingfocus

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SelectableApp(val packageName: String, val label: String)

class AutoTriggerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FocusTheme { SettingsScreen() } }
    }

    @Suppress("DEPRECATION")
    private fun selectableApps(): List<SelectableApp> {
        val homePackages = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        return packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName && it.activityInfo.packageName !in homePackages }
            .map { SelectableApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
            .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    @Composable
    private fun SettingsScreen() {
        val saved = remember { AutoTriggerConfig.read(this) }
        var selected by rememberSaveable { mutableStateOf<List<String>>(saved.packages.toList()) }
        var minutes by rememberSaveable { mutableStateOf((saved.durationMillis / 60_000).toString()) }
        var interval by rememberSaveable { mutableLongStateOf(saved.intervalMillis) }
        var search by rememberSaveable { mutableStateOf("") }
        var apps by remember { mutableStateOf<List<SelectableApp>>(emptyList()) }
        var loaded by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var usage by remember { mutableStateOf(AutoTriggerConfig.hasUsageAccess(this)) }
        var overlay by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
        var notifications by remember { mutableStateOf(notificationsAllowed()) }
        var showLogs by rememberSaveable { mutableStateOf(false) }
        val status by FloatingService.monitoring.collectAsStateWithLifecycle()
        val session by FloatingService.session.collectAsStateWithLifecycle()
        val logs by AutoTriggerLog.lines.collectAsStateWithLifecycle()
        val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notifications = notificationsAllowed()
        }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    usage = AutoTriggerConfig.hasUsageAccess(this@AutoTriggerActivity)
                    overlay = Settings.canDrawOverlays(this@AutoTriggerActivity)
                    notifications = notificationsAllowed()
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(Unit) {
            try {
                apps = withContext(Dispatchers.IO) { selectableApps() }
                selected = selected.filter { pkg -> apps.any { it.packageName == pkg } }
            } catch (_: RuntimeException) { message = "无法读取可启动 App 列表，请重新打开页面。" }
            loaded = true
        }
        Scaffold { insets ->
            LazyColumn(Modifier.padding(insets).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("指定 App 自动计时", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                    Text("可选功能 · 默认关闭", color = MaterialTheme.colorScheme.primary)
                    Text("仅在你主动开启后读取前台使用事件，不读取屏幕内容，不使用无障碍。进入选中 App 开始或恢复，离开即暂停并隐藏浮窗。多个目标 App 共用一份时间额度。")
                }
                item {
                    Text(if (status.enabled) "监测已开启：${status.reason}" else status.reason, style = MaterialTheme.typography.titleMedium)
                    if (status.enabled) {
                        Text("剩余 ${formatRemaining(status.remainingMillis)} · ${if (status.running) "计时中" else "暂停/等待"}")
                        status.detectionDelayMillis?.let { Text("最近目标进入检测延迟：${it}ms") }
                        Button(onClick = { FloatingService.stop(this@AutoTriggerActivity) }) { Text("停止监测") }
                    } else {
                        if (session.active) Text("手动计时正在进行；请先结束后再开启监测。")
                        Button(enabled = loaded && !session.active && selected.isNotEmpty() && minutes.toIntOrNull() in 1..180,
                            onClick = {
                                usage = AutoTriggerConfig.hasUsageAccess(this@AutoTriggerActivity)
                                overlay = Settings.canDrawOverlays(this@AutoTriggerActivity)
                                if (!usage || !overlay) message = "请先开启下方两项授权，返回后再点击开启。"
                                else {
                                    AutoTriggerConfig.save(this@AutoTriggerActivity,
                                        AutoTriggerConfig(selected.toSet(), minutes.toLong() * 60_000, interval))
                                    message = null
                                    FloatingService.startMonitoring(this@AutoTriggerActivity)
                                }
                            }) { Text("开启 App 监测") }
                    }
                    (session.error ?: message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                item {
                    Text("使用情况访问：${if (usage) "已授权" else "未授权"}")
                    TextButton(onClick = {
                        openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:$packageName".toUri())) {
                            message = "请到系统设置的「特殊应用权限 → 使用情况访问」中授权 Floating Focus。"
                        }
                    }) { Text("授权使用情况访问") }
                    Text("悬浮窗：${if (overlay) "已授权" else "未授权"}")
                    TextButton(onClick = {
                        openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())) { message = "请在系统应用设置中授权悬浮窗。" }
                    }) { Text("授权悬浮窗") }
                    if (!notifications && Build.VERSION.SDK_INT >= 33) {
                        Text("建议开启通知，方便看到监测状态并随时停止。拒绝通知不阻止监测。")
                        TextButton(onClick = { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("开启状态通知") }
                    }
                }
                item {
                    OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(3) }, enabled = !status.enabled,
                        label = { Text("计时时长（1—180 分钟）") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Text("检测间隔：越短响应越快，耗电越高。系统事件可能延迟，不能保证实时。")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AutoTriggerConfig.INTERVALS.forEach { value ->
                            FilterChip(interval == value, { interval = value }, enabled = !status.enabled,
                                label = { Text("${value}ms") })
                        }
                    }
                    Text("运行中请先停止再调整配置。锁屏暂停；分屏检测到多个恢复的 App 时暂停。事件不确定时请退出再进入目标 App。")
                    Text("系统终止或重启后不会自动恢复监测，本轮计时不保留；请重新手动开启。到期确认或结束浮窗会停止整轮监测。")
                }
                item {
                    TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "收起调试日志" else "查看调试日志") }
                    if (showLogs) {
                        Text("最近 150 条，仅在内存中；Debug 同时写入 AutoTrigger Logcat。")
                        TextButton(onClick = { AutoTriggerLog.clear() }) { Text("清空日志") }
                        Text(logs.takeLast(30).joinToString("\n").ifEmpty { "暂无事件" }, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("选择 App（已选 ${selected.size}）", style = MaterialTheme.typography.titleMedium)
                    Text("仅列出当前用户可启动的 App，不申请读取全部安装包权限。")
                    OutlinedTextField(search, { search = it }, label = { Text("搜索名称或包名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (!loaded) Text("正在加载 App…")
                }
                items(apps.filter { it.label.contains(search, true) || it.packageName.contains(search, true) }, key = { it.packageName }) { app ->
                    Row(Modifier.fillMaxWidth().toggleable(app.packageName in selected, enabled = !status.enabled, role = Role.Checkbox) {
                        selected = if (it) selected + app.packageName else selected - app.packageName
                    }.padding(vertical = 6.dp)) {
                        Checkbox(app.packageName in selected, onCheckedChange = null, enabled = !status.enabled)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(app.label)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { TextButton(onClick = { finish() }) { Text("返回（已开启的监测会继续）") } }
            }
        }
    }
    private fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openSettings(intent: Intent, onError: () -> Unit) {
        try { startActivity(intent) } catch (_: RuntimeException) { onError() }
    }
}
