package app.floatingfocus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/** Visible ID-only entry; its separate task never clears FF's existing home/settings drafts. */
class PresetShortcutActivity : ComponentActivity() {
    private var presetId: String? = null
    private var consumed = false
    private var launching by mutableStateOf(false)
    private var issue by mutableStateOf<PresetLaunchIssue?>(null)
    private var message by mutableStateOf("正在启动…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState != null) {
            presetId = savedInstanceState.getString("preset")
            consumed = true // A restored page never replays its incoming click.
            message = savedInstanceState.getString("message") ?: "请点击继续启动。"
            issue = savedInstanceState.getString("issue")?.let { value -> PresetLaunchIssue.entries.firstOrNull { it.name == value } }
        } else presetId = incomingId(intent)
        setContent { FocusTheme {
            val allowed by rememberOverlayPermission()
            Scaffold { insets ->
                Column(Modifier.fillMaxSize().padding(insets).safeDrawingPadding().padding(FocusSpacing.page),
                    verticalArrangement = Arrangement.spacedBy(FocusSpacing.section)) {
                    Text("预设启动", style = MaterialTheme.typography.headlineSmall)
                    Text(message, color = if (issue != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (launching) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        if (issue == PresetLaunchIssue.PERMISSION && !allowed) Button(onClick = {
                            if (!OverlayPermission.request(this@PresetShortcutActivity)) message = "无法打开系统权限页面，请在应用设置中允许显示悬浮窗。"
                        }) { Text("授予悬浮窗权限") }
                        else if (presetId != null && issue !in listOf(PresetLaunchIssue.ACTIVE, PresetLaunchIssue.MISSING, PresetLaunchIssue.INVALID_ID))
                            Button(onClick = ::attempt) { Text("继续启动") }
                        if (issue in listOf(PresetLaunchIssue.BINDING, PresetLaunchIssue.UNAVAILABLE, PresetLaunchIssue.STRATEGY))
                            OutlinedButton(onClick = {
                                startActivity(Intent(this@PresetShortcutActivity, SettingsActivity::class.java)
                                    .putExtra(SettingsActivity.EXTRA_PRESET_ID, presetId))
                            }) { Text("修复预设") }
                        TextButton(onClick = { finish() }) { Text("取消") }
                    }
                }
            }
        } }
    }

    override fun onResume() { super.onResume(); PresetDesktopShortcuts.get(this).scheduleSync() }
    override fun onPostResume() { super.onPostResume(); scheduleInitial() }

    private fun scheduleInitial() {
        lifecycleScope.launch {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            if (!consumed) attempt()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (launching) return
        presetId = incomingId(intent)
        consumed = false
        setIntent(intent)
        scheduleInitial()
    }

    private fun incomingId(intent: Intent): String? = try {
        if (intent.action == PresetDesktopShortcuts.ACTION_LAUNCH)
            intent.getStringExtra(PresetDesktopShortcuts.EXTRA_ID)?.takeIf(PresetShortcutIdentity::valid) else null
    } catch (_: RuntimeException) { null }

    private fun attempt() {
        if (launching) return
        consumed = true
        // No residual external extras survive to become another automatic launch.
        setIntent(Intent(this, PresetShortcutActivity::class.java))
        val check = checkPresetLaunch(this, presetId)
        issue = check.issue
        if (check.issue != null) { message = check.message!!; return }
        launching = true
        message = "正在启动「${check.preset!!.name}」…"
        lifecycleScope.launch {
            try {
                val error = launchPresetWithTimer(this@PresetShortcutActivity, lifecycle, presetId!!)
                if (error == null) finish()
                else { issue = checkPresetLaunch(this@PresetShortcutActivity, presetId).issue; message = error }
            } finally { launching = false }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("preset", presetId)
        outState.putString("message", if (launching) "启动已中断，请点击继续启动。" else message)
        outState.putString("issue", issue?.name)
        super.onSaveInstanceState(outState)
    }
}
