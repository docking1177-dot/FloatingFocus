package app.floatingfocus

import android.animation.ValueAnimator
import android.graphics.Typeface
import android.view.Gravity
import android.widget.TextView
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.Constraints
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

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

    val circle = appearance.shape == OverlayShape.CIRCLE
    val textBacking = Color(appearance.colorArgb)
    val textStyle = TextStyle(shadow = Shadow(Color.Black, blurRadius = 3f))
    val body: @Composable () -> Unit = {
        Column(horizontalAlignment = if (circle) Alignment.CenterHorizontally else Alignment.Start) {
            AndroidView(factory = { context ->
                TextView(context).apply {
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(android.graphics.Color.WHITE)
                }
            }, update = { handle ->
                handle.setOnClickListener(if (controlsEnabled) android.view.View.OnClickListener { expanded = !expanded } else null)
                handle.isClickable = controlsEnabled
                val density = handle.resources.displayMetrics.density
                val h = (20 * size * density).toInt()
                val v = (12 * size * density).toInt()
                handle.minimumHeight = (48 * size * density).toInt()
                handle.maxLines = 1
                handle.setHorizontallyScrolling(false)
                handle.setPadding(h, v, h, v)
                handle.setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
                // Back only the glyph run, not the padded TextView (which would make opacity ineffective).
                handle.text = SpannableString(appearance.title).apply {
                    setSpan(BackgroundColorSpan(appearance.colorArgb), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                handle.textSize = (if (finished) 22f else 18f) * size
                handle.setAutoSizeTextTypeUniformWithConfiguration(12,
                    ceil((if (finished) 22f else 18f) * size).toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
                handle.contentDescription = if (controlsEnabled) "${handle.text}，点击${if (expanded) "收起" else "展开"}操作，可拖动" else handle.text
            })
            if (appearance.confirmationRequired) {
                Text("确认提醒", color = Color.White, style = textStyle, modifier = Modifier.padding(horizontal = 20.dp).background(textBacking))
                TextButton(onClick = onConfirm, enabled = controlsEnabled) { Text("已看到提醒", color = Color.White, style = textStyle, modifier = Modifier.background(textBacking)) }
            }
            if (finished) {
                TextButton(onClick = onStop, enabled = controlsEnabled, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("知道了", color = Color.White, style = textStyle, modifier = Modifier.background(textBacking))
                }
            } else if (expanded) {
                FlowRow(modifier = Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = onStop) { Text("结束计时", color = Color.White, style = textStyle, modifier = Modifier.background(textBacking)) }
                    TextButton(onClick = { expanded = false }) { Text("收起", color = Color.White, style = textStyle, modifier = Modifier.background(textBacking)) }
                }
            }
        }
    }
    Surface(color = Color(appearance.colorArgb).copy(alpha = alpha * manualPulse.value * appearance.opacity),
        shape = if (circle) CircleShape else RoundedCornerShape((28 * size).dp), modifier = Modifier.padding(2.dp)) {
        if (circle) CircleContent(body) else body()
    }
}

/** The content rectangle is inscribed in the circle, so buttons never sit in clipped corners. */
@Composable
private fun CircleContent(content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val maxDiameter = min(constraints.maxWidth, constraints.maxHeight)
        val contentLimit = if (maxDiameter == Constraints.Infinity) maxDiameter else (maxDiameter / 1.414214f).toInt()
        val placeable = measurables.single().measure(Constraints(maxWidth = contentLimit))
        val naturalDiameter = ceil(hypot(placeable.width.toDouble(), placeable.height.toDouble())).toInt().coerceAtLeast(1)
        val diameter = naturalDiameter.coerceAtMost(maxDiameter)
        val fit = diameter.toFloat() / naturalDiameter
        layout(diameter, diameter) {
            placeable.placeRelativeWithLayer((diameter - placeable.width) / 2, (diameter - placeable.height) / 2) {
                scaleX = fit
                scaleY = fit
            }
        }
    }
}
