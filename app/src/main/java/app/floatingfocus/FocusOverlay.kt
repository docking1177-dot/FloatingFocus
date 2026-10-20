package app.floatingfocus

import android.animation.ValueAnimator
import android.graphics.Typeface
import android.view.Gravity
import android.widget.TextView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun FocusOverlay(state: SessionState, interactive: Boolean, window: OverlayWindow, onStop: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val finished = state.stage == ReminderStage.FINISHED
    val color = reminderColor(state.progress)
    val alpha = if (state.stage == ReminderStage.URGENT && interactive && ValueAnimator.areAnimatorsEnabled()) {
        val transition = rememberInfiniteTransition(label = "gentle reminder")
        val value by transition.animateFloat(1f, 0.82f,
            infiniteRepeatable(tween(ReminderPolicy.BREATH_MILLIS / 2), RepeatMode.Reverse), label = "opacity")
        value
    } else 1f

    Surface(color = color.copy(alpha = 0.94f), shape = RoundedCornerShape(28.dp),
        modifier = Modifier.padding(2.dp).graphicsLayer { this.alpha = alpha }) {
        Column {
            AndroidView(factory = { context ->
                TextView(context).apply {
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(android.graphics.Color.WHITE)
                    val h = (20 * resources.displayMetrics.density).toInt()
                    val v = (12 * resources.displayMetrics.density).toInt()
                    minimumHeight = (48 * resources.displayMetrics.density).toInt()
                    setPadding(h, v, h, v)
                    window.installDrag(this) { expanded = !expanded }
                }
            }, update = { handle ->
                handle.text = if (finished) "◷  时间到了" else "◷  ${formatRemaining(state.remainingMillis)}"
                handle.textSize = if (finished) 22f else 18f
                handle.contentDescription = "${handle.text}，点击${if (expanded) "收起" else "展开"}操作，可拖动"
            })
            if (finished) {
                Text("给眼睛一个休息的机会", color = Color.White, modifier = Modifier.padding(horizontal = 20.dp))
                TextButton(onClick = onStop, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("知道了", color = Color.White)
                }
            } else if (expanded) {
                Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = onStop) { Text("结束计时", color = Color.White) }
                    TextButton(onClick = { expanded = false }) { Text("收起", color = Color.White) }
                }
            }
        }
    }
}

private fun reminderColor(progress: Float): Color {
    val grey = Color(0xFF303833)
    val orange = Color(0xFFAF580D)
    val red = Color(0xFFB52E36)
    return when {
        progress < ReminderPolicy.WARM_START -> grey
        progress < ReminderPolicy.URGENT_START -> lerp(grey, orange,
            ((progress - ReminderPolicy.WARM_START) / (ReminderPolicy.URGENT_START - ReminderPolicy.WARM_START)).coerceIn(0f, 1f))
        else -> lerp(orange, red,
            ((progress - ReminderPolicy.URGENT_START) / (1f - ReminderPolicy.URGENT_START)).coerceIn(0f, 1f))
    }
}
