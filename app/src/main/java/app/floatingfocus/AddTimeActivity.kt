package app.floatingfocus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** A normal Activity owns keyboard focus; the cross-app overlay keeps its existing touch flags. */
class AddTimeActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        FloatingService.extensionEditor(this, intent.getLongExtra("timer_start", -1L), true)
    }
    override fun onStop() {
        FloatingService.extensionEditor(this, intent.getLongExtra("timer_start", -1L), false)
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val expectedStart = intent.getLongExtra("timer_start", -1L)
        setContent { FocusTheme {
            val session by FloatingService.session.collectAsStateWithLifecycle()
            var minutes by rememberSaveable { mutableStateOf("5") }
            val validSession = session.active && !session.automatic && session.mode == TimerMode.COUNTDOWN &&
                session.stage == ReminderStage.FINISHED && session.timerStartedAt == expectedStart
            Scaffold { insets ->
                Column(Modifier.fillMaxSize().padding(insets).imePadding().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("加时", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { finish() }) { Text("取消") }
                    }
                    if (validSession) {
                        Text("从确认时开始倒计时，保留本次累计记录。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(3) },
                            label = { Text("增加分钟（1—180）") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = minutes.toIntOrNull() !in 1..180)
                        Button(enabled = minutes.toIntOrNull() in 1..180, onClick = {
                            FloatingService.extend(this@AddTimeActivity, expectedStart, minutes.toInt())
                            finish()
                        }) { Text("确认加时") }
                    } else Text("当前计时已结束或已加时。")
                }
            }
        } }
    }
}
