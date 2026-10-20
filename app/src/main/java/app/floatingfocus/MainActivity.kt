package app.floatingfocus

import android.Manifest
import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FocusTheme { FocusScreen() } }
    }

    @Composable
    private fun FocusScreen() {
        val session by FloatingService.session.collectAsStateWithLifecycle()
        var minutes by rememberSaveable { mutableIntStateOf(15) }
        var custom by rememberSaveable { mutableStateOf(false) }
        var showCustom by rememberSaveable { mutableStateOf(false) }
        var input by rememberSaveable { mutableStateOf("") }
        var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
        var notificationAllowed by remember { mutableStateOf(hasNotificationPermission()) }
        var permissionMessage by rememberSaveable { mutableStateOf<String?>(null) }
        var permissionHelp by rememberSaveable { mutableStateOf(false) }
        var showLicenses by rememberSaveable { mutableStateOf(false) }
        // Result never auto-starts a timer: start must happen while this activity is visible.
        val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationAllowed = hasNotificationPermission()
        }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    overlayAllowed = Settings.canDrawOverlays(this@MainActivity)
                    notificationAllowed = hasNotificationPermission()
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                            Text("◷", color = Color.White, fontSize = 24.sp, modifier = Modifier.padding(10.dp))
                        }
                        Text("Floating Focus", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(40.dp))
                    Text("把时间，\n留在视线里。", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text("开始前，给自己一个小小的约定。\n刷视频时，悬浮计时会温柔提醒你。", color = Color(0xFF667067), lineHeight = 25.sp)
                    Spacer(Modifier.height(30.dp))
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (session.active) if (session.stage == ReminderStage.FINISHED) "约定的时间到了" else "还可以享受" else "这次，留给自己", color = Color(0xFF667067))
                            Text(if (session.active) formatRemaining(session.remainingMillis) else "%02d:00".format(minutes),
                                fontSize = 58.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 12.dp))
                            Text(if (session.active) "悬浮胶囊正在提醒你" else "分钟 · 自己决定何时停下", style = MaterialTheme.typography.bodySmall, color = Color(0xFF667067))
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("选择时长", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 15, 30).forEach { value ->
                            FilterChip(selected = !custom && minutes == value, enabled = !session.active,
                                onClick = { minutes = value; custom = false }, label = { Text("$value 分") }, modifier = Modifier.weight(1f))
                        }
                    }
                    TextButton(onClick = { input = if (custom) minutes.toString() else ""; showCustom = true }, enabled = !session.active) {
                        Text(if (custom) "自定义：$minutes 分钟" else "自定义时长 →")
                    }
                    Spacer(Modifier.height(12.dp))
                    if (session.active) {
                        Button(onClick = { FloatingService.stop(this@MainActivity) }, modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Text(if (session.stage == ReminderStage.FINISHED) "知道了，结束计时" else "结束倒计时")
                        }
                    } else {
                        Button(onClick = {
                            if (!Settings.canDrawOverlays(this@MainActivity)) permissionHelp = true
                            else FloatingService.start(this@MainActivity, minutes * 60_000L)
                        }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("开始倒计时") }
                    }
                    Spacer(Modifier.height(20.dp))
                    if (!overlayAllowed) {
                        PermissionCard("允许显示悬浮窗", "用于在其他 App 上方显示倒计时。", "去授权") { permissionHelp = true }
                    }
                    if (!notificationAllowed && Build.VERSION.SDK_INT >= 33) {
                        PermissionCard("开启计时通知", "建议开启；拒绝后仍可计时，可从悬浮窗结束。", "去设置") {
                            if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ||
                                !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
                                getPreferences(MODE_PRIVATE).edit { putBoolean("notificationAsked", true) }
                                notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                        }
                    }
                    (session.error ?: permissionMessage)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp)) }
                    Spacer(Modifier.height(20.dp))
                    Text("轻一点的提醒，渐渐变得清晰", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    ReminderRow(Color(0xFF303833), "前 50%", "安静陪伴")
                    ReminderRow(Color(0xFFAF580D), "50%—80%", "逐渐转为暖橙色")
                    ReminderRow(Color(0xFFB52E36), "最后 20%", "渐变红色，轻缓呼吸")
                    Spacer(Modifier.height(20.dp))
                    Text("只提醒，由你决定。\n点击胶囊展开操作，拖动可调整位置。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF667067), lineHeight = 21.sp)
                    TextButton(onClick = { showLicenses = true }) { Text("开源许可") }
                }
            }
        }
        if (showCustom) {
            val value = input.toIntOrNull()
            AlertDialog(onDismissRequest = { showCustom = false }, title = { Text("自定义时长") }, text = {
                OutlinedTextField(value = input, onValueChange = { input = it.filter(Char::isDigit).take(3) },
                    label = { Text("分钟（1—180）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = input.isNotEmpty() && (value == null || value !in 1..180))
            }, confirmButton = {
                TextButton(enabled = value != null && value in 1..180, onClick = { minutes = value!!; custom = true; showCustom = false }) { Text("确定") }
            }, dismissButton = { TextButton(onClick = { showCustom = false }) { Text("取消") } })
        }
        if (permissionHelp) {
            AlertDialog(onDismissRequest = { permissionHelp = false }, title = { Text("让时间悬浮在其他 App 上方") },
                text = { Text("请在系统设置中允许 Floating Focus「显示在其他应用上层」。授权后返回，再点击开始。我们不会读取屏幕内容。") },
                confirmButton = { TextButton(onClick = {
                    permissionHelp = false
                    if (!openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))) {
                        permissionMessage = "无法打开悬浮窗设置，请在系统应用设置中手动授权。"
                    }
                }) { Text("前往系统设置") } },
                dismissButton = { TextButton(onClick = { permissionHelp = false }) { Text("稍后") } })
        }
        if (showLicenses) {
            val notices = remember {
                listOf("THIRD_PARTY_NOTICES.txt", "FloatingCountdownTimer-MIT.txt", "Apache-2.0.txt")
                    .joinToString("\n\n") { name -> assets.open(name).bufferedReader().use { it.readText() } }
            }
            AlertDialog(onDismissRequest = { showLicenses = false }, title = { Text("开源许可") },
                text = { Text(notices, modifier = Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
                confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("关闭") } })
        }
    }

    private fun hasNotificationPermission() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openSettings(intent: Intent): Boolean = try { startActivity(intent); true }
        catch (_: ActivityNotFoundException) { false }
        catch (_: SecurityException) { false }
}

@Composable
private fun PermissionCard(title: String, description: String, action: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        Text(description, style = MaterialTheme.typography.bodySmall, color = Color(0xFF667067))
        OutlinedButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun ReminderRow(color: Color, label: String, description: String) {
    Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, modifier = Modifier.widthIn(min = 92.dp), style = MaterialTheme.typography.bodySmall)
        Text(description, color = Color(0xFF667067), style = MaterialTheme.typography.bodySmall)
    }
}
