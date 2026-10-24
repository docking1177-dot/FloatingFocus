package app.floatingfocus

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun FocusOverlay(
    appearance: ReminderPresentation,
    interactive: Boolean,
    animationRequest: Int = 0,
    controlsEnabled: Boolean = true,
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

    val vertical = appearance.shape == OverlayShape.VERTICAL_CAPSULE
    val textStyle = TextStyle(color = Color.White, shadow = Shadow(Color.Black, blurRadius = 2f))
    // One alpha layer for the whole window. No glyph backgrounds or independent opaque children.
    Surface(color = Color(appearance.colorArgb), shape = CircleShape,
        modifier = Modifier.padding(2.dp).graphicsLayer { this.alpha = alpha * manualPulse.value * appearance.opacity }) {
        Layout(content = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val header = Modifier.clickable(enabled = controlsEnabled) { expanded = !expanded }
                    .semantics { contentDescription = if (controlsEnabled)
                        "${appearance.title}，点击${if (expanded) "收起" else "展开"}操作，可拖动" else appearance.title }
                    .padding(4.dp)
                val title: @Composable () -> Unit = {
                    Icon(painterResource(R.drawable.ic_notification), contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(20.dp))
                    Text(appearance.title.removePrefix("◷").trim(), style = textStyle,
                        fontSize = 18.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
                        maxLines = 1, softWrap = false)
                }
                if (vertical) Column(header, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) { title() }
                else Row(header, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) { title() }
                if (appearance.confirmationRequired) {
                    Text("确认提醒", style = textStyle)
                    TextButton(onClick = onConfirm, enabled = controlsEnabled) { Text("已看到提醒", style = textStyle) }
                }
                if (finished) {
                    TextButton(onClick = onStop, enabled = controlsEnabled) { Text("知道了", style = textStyle) }
                } else if (expanded) {
                    FlowRow(horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = onStop, enabled = controlsEnabled) { Text("结束计时", style = textStyle) }
                        TextButton(onClick = { expanded = false }) { Text("收起", style = textStyle) }
                    }
                }
            }
        }) { measurables, constraints ->
            // Measure at a stable base size; apply the current reminder scale exactly once.
            val body = measurables.single().measure(Constraints(maxWidth = 240.dp.roundToPx()))
            val frame = OverlayGeometry.frame(appearance.shape, body.width / density, body.height / density, size,
                constraints.maxWidth / density, constraints.maxHeight / density)
            val width = constraints.constrainWidth((frame.width * density).roundToInt())
            val height = constraints.constrainHeight((frame.height * density).roundToInt())
            layout(width, height) {
                body.placeRelativeWithLayer((width - body.width) / 2, (height - body.height) / 2) {
                    scaleX = frame.scale
                    scaleY = frame.scale
                }
            }
        }
    }
}
