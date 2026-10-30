package app.floatingfocus

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Visible default-start entry; never changes FF's saved home draft or replays a restored click. */
class WidgetStartActivity : ComponentActivity() {
    private var consumed = false
    private var message by mutableStateOf("准备开始…")
    private var needsPermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumed = savedInstanceState != null
        needsPermission = savedInstanceState?.getBoolean("needs_permission") ?: false
        if (consumed) message = savedInstanceState?.getString("message") ?: "请点击继续开始。"
        setContent { FocusTheme {
            val allowed by rememberOverlayPermission()
            Scaffold { insets ->
                Column(Modifier.fillMaxSize().padding(insets).safeDrawingPadding().padding(FocusSpacing.page),
                    verticalArrangement = Arrangement.spacedBy(FocusSpacing.section)) {
                    Text("默认计时", style = MaterialTheme.typography.headlineSmall)
                    Text(message)
                    if (needsPermission && !allowed) Button(onClick = {
                        if (!OverlayPermission.request(this@WidgetStartActivity)) message = "无法打开系统设置，请在应用信息中允许悬浮窗。"
                    }) { Text("授予悬浮窗权限") }
                    else Button(onClick = ::attempt) { Text("继续开始") }
                    TextButton(onClick = {
                        startActivity(Intent(this@WidgetStartActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }) { Text("打开 FF") }
                    TextButton(onClick = { finish() }) { Text("取消") }
                }
            }
        } }
    }

    override fun onPostResume() {
        super.onPostResume()
        scheduleInitial()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumed = false
        scheduleInitial()
    }

    private fun scheduleInitial() {
        lifecycleScope.launch {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            if (!consumed) attempt()
        }
    }

    private fun attempt() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        consumed = true
        if (FloatingService.session.value.active || FloatingService.startPending || FloatingService.monitoring.value.enabled) {
            message = "已有计时正在运行，请先结束。"; return
        }
        if (!OverlayPermission.granted(this)) {
            needsPermission = true
            message = "需允许悬浮窗后才能开始计时。授权返回后请点击继续开始。"
            return
        }
        needsPermission = false
        val defaults = QuickDurationsStore.get(this).startupDefaults.value
        val millis = if (defaults.mode == TimerMode.STOPWATCH) 0L else defaults.durationSeconds * 1000L
        ReminderSettingsStore.get(this).settings.value.strategy.validationError(millis, defaults.mode)?.let { message = it; return }
        if (FloatingService.start(this, millis, defaults.mode)) finish()
        else message = FloatingService.session.value.error ?: "已有计时正在运行，请先结束。"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("message", if (needsPermission) message else "请点击继续开始。")
        outState.putBoolean("needs_permission", needsPermission)
        super.onSaveInstanceState(outState)
    }
}
