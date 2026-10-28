package app.floatingfocus

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToLong

class ReminderLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FocusTheme { LabScreen() } }
    }

    @Composable
    private fun LabScreen() {
        val sharedSettings by ReminderSettingsStore.get(this).settings.collectAsStateWithLifecycle()
        val lab = ReminderLabState.simulation.copy(settings = sharedSettings)
        var durationInput by rememberSaveable { mutableStateOf("900") }
        var remainingInput by rememberSaveable { mutableStateOf("900") }
        var inputError by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    // Both hosts sample the same anchored clock; no delta accumulation or double ticking.
                    ReminderLabState.simulation = ReminderLabState.simulation.tick(SystemClock.elapsedRealtime())
                    delay(100)
                }
            }
        }
        Scaffold { insets ->
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("开发者模式", style = MaterialTheme.typography.headlineSmall)
                Text("Debug · 仅模拟，不修改正式计时。返回退出会关闭实验悬浮窗；按 Home 可跨应用查看。",
                    style = MaterialTheme.typography.bodySmall)
                Text("实时预览", style = MaterialTheme.typography.titleMedium)
                FocusOverlay(lab.appearance, interactive = true,
                    animationRequest = ReminderLabState.animationRequest,
                    onConfirm = { ReminderLabState.simulation = ReminderLabState.simulation.confirm(sharedSettings.strategy) },
                    onStop = { ReminderLabService.hide(this@ReminderLabActivity) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (Settings.canDrawOverlays(this@ReminderLabActivity)) ReminderLabService.show(this@ReminderLabActivity)
                        else ReminderLabState.message = "尚未获得悬浮权限，请点击下方授权，返回后再显示。"
                    }, modifier = Modifier.weight(1f)) { Text(if (ReminderLabState.overlayVisible) "已显示" else "显示浮窗", maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { ReminderLabService.hide(this@ReminderLabActivity) }, modifier = Modifier.weight(1f)) { Text("关闭浮窗", maxLines = 1, softWrap = false) }
                }
                TextButton(onClick = {
                    if (!OverlayPermission.request(this@ReminderLabActivity)) ReminderLabState.message = "无法打开设置，请在系统应用设置中手动授权。"
                }) { Text("悬浮权限设置") }
                ReminderLabState.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                HorizontalDivider()
                Text("模拟时间（秒）", style = MaterialTheme.typography.titleMedium)
                Text("负数表示超时。播放速度为真实 1×；拖动进度或应用时间会暂停播放。", style = MaterialTheme.typography.bodySmall)
                DurationEntry("模拟总时长", durationInput.toIntOrNull() ?: 900, { durationInput = it.toString() }, minSeconds = 1)
                var overtime by rememberSaveable { mutableStateOf(false) }
                Row {
                    FilterChip(!overtime, { overtime = false; remainingInput = remainingInput.removePrefix("-") }, label = { Text("剩余") })
                    FilterChip(overtime, { overtime = true; remainingInput = "-" + remainingInput.removePrefix("-") }, label = { Text("超时") })
                }
                DurationEntry(if (overtime) "模拟超时" else "模拟剩余", kotlin.math.abs(remainingInput.toIntOrNull() ?: 900),
                    { remainingInput = (if (overtime) -it else it).toString() }, minSeconds = 0)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val total = durationInput.toLongOrNull()
                        val left = remainingInput.toLongOrNull()
                        if (total == null || total !in 1..10800 || left == null || left !in -10800..total) {
                            inputError = "总时长须为 1—10800；剩余须为 -10800 至总时长的整数秒。"
                        } else {
                            ReminderLabState.simulation = LabSimulation(total * 1000, left * 1000, lab.settings)
                            inputError = null
                        }
                    }) { Text("应用模拟时间") }
                    OutlinedButton(onClick = {
                        ReminderLabState.simulation = if (lab.playback == null) lab.play(SystemClock.elapsedRealtime()) else lab.copy(playback = null)
                    }) { Text(if (lab.playback == null) "播放模拟" else "暂停模拟") }
                }
                inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val elapsed = 1f - lab.signedRemainingMillis.toFloat() / lab.durationMillis
                Text("已用 ${"%.1f".format(Locale.ROOT, elapsed * 100)}% · ${lab.settings.strategy.stage(lab.snapshot)}")
                Slider(value = elapsed.coerceIn(0f, 2f), onValueChange = {
                    ReminderLabState.simulation = lab.seek(((1f - it) * lab.durationMillis).roundToLong())
                }, valueRange = 0f..2f, modifier = Modifier.semantics { contentDescription = "模拟已用时间百分比" })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 50, 80, 100, 120).forEach { percent ->
                        OutlinedButton(onClick = { ReminderLabState.simulation = lab.seek(lab.durationMillis * (100 - percent) / 100) }) {
                            Text("$percent%")
                        }
                    }
                }
                HorizontalDivider()
                Text("外观与提醒策略使用正式设置。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { startActivity(Intent(this@ReminderLabActivity, SettingsActivity::class.java)) }) { Text("打开设置") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { ReminderLabState.animationRequest++ }) { Text("触发提醒动画") }
                    OutlinedButton(onClick = {
                        ReminderVibration.once(this@ReminderLabActivity, lab.settings.intensity)
                        ReminderLabState.message = ReminderVibration.lastDiagnostic
                    }) { Text("触发一次振动") }
                }
                Text("模拟跨越零点不会自动振动。动画遵循系统动画开关；手动动画播放两轮。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    ReminderLabService.hide(this@ReminderLabActivity)
                    ReminderLabState.reset(sharedSettings)
                    durationInput = "900"; remainingInput = "900"; overtime = false; inputError = null
                }) { Text("重置模拟状态") }
                HorizontalDivider()
                PresetStylePicker()
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            ReminderLabService.hide(this)
            ReminderLabState.simulation = ReminderLabState.simulation.copy(playback = null)
        }
        super.onDestroy()
    }
}
