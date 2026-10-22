package app.floatingfocus

import android.animation.ValueAnimator
import android.graphics.Typeface
import android.view.Gravity
import android.widget.TextView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun FocusOverlay(
    appearance: ReminderPresentation,
    interactive: Boolean,
    animationRequest: Int = 0,
    onConfirm: () -> Unit = {},
    onStop: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val finished = appearance.finished
    val size = appearance.size
    val manualPulse = remember { Animatable(1f) }
    LaunchedEffect(animationRequest, interactive) {
        if (animationRequest > 0 && interactive && ValueAnimator.areAnimatorsEnabled()) {
            repeat(2) {
                manualPulse.animateTo(0.65f, tween(250))
                manualPulse.animateTo(1f, tween(350))
            }
        } else manualPulse.snapTo(1f)
    }
    val alpha = if (appearance.pulseDepth > 0f && interactive && ValueAnimator.areAnimatorsEnabled()) {
        val transition = rememberInfiniteTransition(label = "gentle reminder")
        val value by transition.animateFloat(1f, 1f - appearance.pulseDepth,
            infiniteRepeatable(tween(ReminderPolicy.BREATH_MILLIS / 2), RepeatMode.Reverse), label = "opacity")
        value
    } else 1f

    Surface(color = Color(appearance.colorArgb), shape = RoundedCornerShape((28 * size).dp),
        modifier = Modifier.padding(2.dp).graphicsLayer { this.alpha = alpha * manualPulse.value * appearance.opacity }) {
        Column {
            AndroidView(factory = { context ->
                TextView(context).apply {
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(android.graphics.Color.WHITE)
                    setOnClickListener { expanded = !expanded }
                }
            }, update = { handle ->
                val density = handle.resources.displayMetrics.density
                val h = (20 * size * density).toInt()
                val v = (12 * size * density).toInt()
                handle.minimumHeight = (48 * size * density).toInt()
                handle.setPadding(h, v, h, v)
                handle.text = appearance.title
                handle.textSize = (if (finished) 22f else 18f) * size
                handle.contentDescription = "${handle.text}，点击${if (expanded) "收起" else "展开"}操作，可拖动"
            })
            if (appearance.confirmationRequired) {
                Text("停一下，确认是否继续？", color = Color.White, modifier = Modifier.padding(horizontal = 20.dp))
                TextButton(onClick = onConfirm) { Text("已看到提醒", color = Color.White) }
            }
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
