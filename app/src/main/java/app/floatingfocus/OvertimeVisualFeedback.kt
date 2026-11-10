package app.floatingfocus

import android.animation.ValueAnimator
import android.os.SystemClock
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

@Composable
internal fun reminderEffectProgress(id: Long?, active: Boolean, disabledProgress: Float=.5f, kind: String="pulse"): Float {
    var value by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(id,active) {
        value=1f
        if(id!=null && active) {
            if(ValueAnimator.areAnimatorsEnabled()) {
                val start=SystemClock.elapsedRealtime();value=0f
                while(value<1f) withFrameNanos { value=ReminderEffectTimeline.progress(SystemClock.elapsedRealtime()-start) }
            } else { value=disabledProgress;delay(ReminderEffectTimeline.DURATION_MILLIS.toLong());value=1f }
        }
    }
    SideEffect { if(id!=null) reminderEffectProbe(kind,id,value) }
    return value
}

/** One bounded pulse per consumed cue. Never changes alpha, size, position or timing. */
@Composable
internal fun overtimeVisual(cue: OvertimeCue?, interactive: Boolean): OvertimeVisual {
    val progress = reminderEffectProgress(cue?.id,interactive)
    val wave = sin(PI * progress).toFloat().coerceIn(0f, 1f)
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
