package app.floatingfocus

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Paint only: the existing Surface owns clipping, alpha, measurement and touch. */
internal fun Modifier.c4CompactArt(led: Boolean, defused: Boolean): Modifier = drawWithCache {
    val horizontal = size.width >= size.height
    val shortSide = minOf(size.width, size.height)
    // Keep the middle clear for the unchanged timer. Transpose the sparse edge
    // details for a tall frame; all positions stay inside the existing contour.
    fun point(along: Float, across: Float) = if (horizontal) {
        Offset(size.width * along, size.height * across)
    } else {
        Offset(size.width * across, size.height * along)
    }
    val trace = Path().apply {
        moveTo(point(.24f, .18f).x, point(.24f, .18f).y)
        lineTo(point(.38f, .18f).x, point(.38f, .18f).y)
        lineTo(point(.43f, .24f).x, point(.43f, .24f).y)
        lineTo(point(.64f, .24f).x, point(.64f, .24f).y)
        moveTo(point(.36f, .82f).x, point(.36f, .82f).y)
        lineTo(point(.59f, .82f).x, point(.59f, .82f).y)
        lineTo(point(.65f, .76f).x, point(.65f, .76f).y)
        lineTo(point(.77f, .76f).x, point(.77f, .76f).y)
    }
    val strokeWidth = minOf(1.dp.toPx(), shortSide * .018f)
    val traceStyle = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val traceColor = Color(0xFFB2B4AA).copy(alpha = .38f)
    val seamColor = Color(0xFF26352B).copy(alpha = .45f)
    val lamp = point(.10f, .50f)
    val lampRadius = minOf(1.8.dp.toPx(), shortSide * .03f)
    val lampColor = when {
        defused -> Color(0xFF9DDE6A)
        led -> Color(0xFFFF5946)
        else -> Color(0xFF6A211B)
    }
    onDrawBehind {
        drawPath(trace, traceColor, style = traceStyle)
        drawLine(seamColor, point(.38f, .10f), point(.70f, .10f), strokeWidth)
        drawLine(seamColor, point(.23f, .90f), point(.56f, .90f), strokeWidth)
        drawCircle(traceColor, strokeWidth, point(.64f, .24f))
        drawCircle(traceColor, strokeWidth, point(.36f, .82f))
        drawCircle(Color(0xFF30372B), lampRadius * 1.7f, lamp)
        drawCircle(lampColor, lampRadius, lamp)
    }
}
