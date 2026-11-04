package app.floatingfocus

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

internal data class OvertimeVisual(val brightening: Float = 0f, val outline: Float = 0f, val status: Boolean = false)

/** One bounded pulse per consumed cue. Never changes alpha, size, position or timing. */
@Composable
internal fun overtimeVisual(cue: OvertimeCue?, interactive: Boolean): OvertimeVisual {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(cue?.id) {
        progress.snapTo(1f)
        if (cue != null && interactive) {
            if (ValueAnimator.areAnimatorsEnabled()) {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(1600))
            } else {
                progress.snapTo(.5f); delay(1000); progress.snapTo(1f)
            }
        }
    }
    val wave = sin(PI * progress.value).toFloat().coerceIn(0f, 1f)
    val effects = if (interactive) cue?.effects.orEmpty() else emptySet()
    return OvertimeVisual(
        if (OvertimeEffect.BRIGHTEN in effects) wave * (cue?.level?.strength ?: 0f) else 0f,
        if (OvertimeEffect.OUTLINE in effects) wave else 0f,
        OvertimeEffect.STATUS in effects)
}

internal fun Modifier.overtimeOutline(amount: Float, color: Color): Modifier = drawWithContent {
    drawContent()
    if (amount > 0f) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2f
        val radius = (minOf(size.width, size.height) - stroke) / 2f
        drawRoundRect(color.copy(alpha = amount), Offset(inset, inset),
            Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
            CornerRadius(radius.coerceAtLeast(0f)), style = Stroke(stroke))
    }
}
