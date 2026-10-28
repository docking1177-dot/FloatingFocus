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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onResume() { super.onResume(); PresetDesktopShortcuts.get(this).scheduleSync() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val onboarding = OnboardingStore.get(this)
        TimerRecordStore.get(this)
        setContent {
            val completed by onboarding.completed.collectAsStateWithLifecycle()
            if (completed) FocusTheme { FocusScreen() }
            else OnboardingScreen(onComplete = { onboarding.complete() }, onExit = { finish() },
                onRequestPermission = { OverlayPermission.request(this) })
        }
    }

    @Composable
    private fun FocusScreen() {
        val session by FloatingService.session.collectAsStateWithLifecycle()
        val monitor by FloatingService.monitoring.collectAsStateWithLifecycle()
        val quickDurations by QuickDurationsStore.get(this).durations.collectAsStateWithLifecycle()
        var mode by rememberSaveable { mutableStateOf(TimerMode.COUNTDOWN) }
        var seconds by rememberSaveable { mutableIntStateOf(900) }
        var customSeconds by rememberSaveable { mutableIntStateOf(60) }
        var strategyError by rememberSaveable { mutableStateOf<String?>(null) }
        val overlayAllowed by rememberOverlayPermission()
        var notificationAllowed by remember { mutableStateOf(hasNotificationPermission()) }
        var permissionMessage by rememberSaveable { mutableStateOf<String?>(null) }
        var permissionHelp by rememberSaveable { mutableStateOf(false) }
        var quickStarting by remember { mutableStateOf(false) }
        // Result never auto-starts a timer: start must happen while this activity is visible.
        val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationAllowed = hasNotificationPermission()
        }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    notificationAllowed = hasNotificationPermission()
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(modifier = Modifier.size(40.dp), color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_notification), contentDescription = null,
                                    tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                        }
                        Text("Floating Focus", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java).putExtra("duration_seconds", seconds)) }, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_settings), contentDescription = "设置", modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HomeSection {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("常规计时", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)
                                .putExtra("settings_page", "durations").putExtra("duration_seconds", seconds)) }) { Text("手动设置", style = MaterialTheme.typography.labelMedium) }
                        }
                        if (session.active) {
                            Text(when {
                                session.paused -> "已暂停 · ${formatRemaining(session.remainingMillis)}"
                                session.mode == TimerMode.STOPWATCH -> "正计时 · ${formatElapsed(session.elapsedMillis)}"
                                session.stage == ReminderStage.FINISHED -> "超时 +${formatElapsed(session.overtimeMillis)}"
                                else -> "计时中 · ${formatRemaining(session.remainingMillis)}" },
                                style = MaterialTheme.typography.bodyMedium, color = Color(0xFF667067))
                            Spacer(Modifier.height(8.dp))
                            if (session.mode == TimerMode.COUNTDOWN && session.stage == ReminderStage.FINISHED && !session.automatic && !session.c4Locked)
                                TextButton(onClick = { FloatingService.requestExtension(this@MainActivity, session.timerStartedAt) }) { Text("加时") }
                        }
                        if (monitor.enabled) Text(monitor.reason, style = MaterialTheme.typography.bodySmall, color = Color(0xFF667067))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TimerMode.entries.forEach { option ->
                                FilterChip(mode == option, { mode = option }, label = { Text(option.label) },
                                    enabled = !session.active && !monitor.enabled && !quickStarting)
                            }
                        }
                        if (mode == TimerMode.COUNTDOWN) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            quickDurations.forEach { value ->
                                FilterChip(selected = seconds == value, enabled = !session.active && !monitor.enabled && !quickStarting,
                                    onClick = { seconds = value }, modifier = Modifier.weight(1f),
                                    label = { Text(if (value % 60 == 0) "${value / 60}分" else "%d:%02d".format(java.util.Locale.ROOT, value / 60, value % 60),
                                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) })
                            }
                        }
                        DurationEntry("自定义时长", customSeconds, { customSeconds = it; seconds = it },
                            enabled = !session.active && !monitor.enabled && !quickStarting, minSeconds = 1)

                        }
                        Spacer(Modifier.height(4.dp))
                        if (session.active || monitor.enabled) {
                          if (!session.c4Locked) {
                            Button(onClick = { FloatingService.stop(this@MainActivity) }, modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                Text(if (monitor.enabled) "停止 App 监测" else "结束计时")
                            }
                          }
                        } else {
                            Button(enabled = !quickStarting, onClick = {
                                if (!OverlayPermission.granted(this@MainActivity)) permissionHelp = true
                                else {
                                    strategyError = ReminderSettingsStore.get(this@MainActivity).settings.value.strategy.validationError(seconds * 1000L, mode)
                                    if (strategyError == null) FloatingService.start(this@MainActivity, seconds * 1000L, mode)
                                }
                            }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(if (mode == TimerMode.STOPWATCH) "开始正计时" else "开始倒计时") }
                        }
                        if (!overlayAllowed) {
                            PermissionCard("允许显示悬浮窗", "用于在其他 App 上方显示倒计时。", "去授权") { permissionHelp = true }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HomeSection {
                        TimerPresetsCard(seconds, mode = mode, enabled = !session.active && !monitor.enabled && !quickStarting,
                        onBusyChange = { quickStarting = it }, onPermissionRequired = { permissionHelp = true },
                        onApplied = { selectedSeconds, selectedMode ->
                            seconds = selectedSeconds; mode = selectedMode
                            if (selectedSeconds !in quickDurations) customSeconds = selectedSeconds
                        })
                    }
                    Spacer(Modifier.height(12.dp))
                    HomeSection {
                        QuickLaunchCard(seconds, mode = mode, enabled = !session.active && !monitor.enabled && !quickStarting,
                            onBusyChange = { quickStarting = it }, onPermissionRequired = { permissionHelp = true })
                    }
                    Spacer(Modifier.height(8.dp))
                    HomeLink("时长记录") { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) }
                    if (FeatureFlags.APP_USAGE_AUTO_TIMER) {
                        TextButton(onClick = { startActivity(Intent(this@MainActivity, AutoTriggerActivity::class.java)) }) {
                            Text("指定 App 自动计时 · 可选")
                        }
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
                }
            }
        }
        strategyError?.let { message ->
            StrategyConflictDialog(message, seconds, onDismiss = { strategyError = null })
        }
        if (permissionHelp) {
            AlertDialog(onDismissRequest = { permissionHelp = false }, title = { Text("悬浮窗权限") },
                text = { Text("请在系统设置中允许 Floating Focus「显示在其他应用上层」。授权后返回，再点击开始。我们不会读取屏幕内容。") },
                confirmButton = { TextButton(onClick = {
                    permissionHelp = false
                    if (!OverlayPermission.request(this@MainActivity)) {
                        permissionMessage = "无法打开悬浮窗设置，请在系统应用设置中手动授权。"
                    }
                }) { Text("前往系统设置") } },
                dismissButton = { TextButton(onClick = { permissionHelp = false }) { Text("稍后") } })
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
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Color(0xFF667067))
            OutlinedButton(onClick = onClick) { Text(action) }
        }
    }
}
