package app.floatingfocus

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Paint only: the existing Surface still owns clipping, alpha, measurement and touch. */
internal fun Modifier.c4CompactArt(shape: OverlayShape, led: Boolean, defused: Boolean): Modifier = drawWithCache {
    val vertical = shape == OverlayShape.VERTICAL_CAPSULE
    val w = if (vertical) size.height else size.width
    val h = if (vertical) size.width else size.height
    val line = 1.dp.toPx()
    val wire = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val rim = Color(0xFF98937D)
    val strap = Color(0xFF293026)
    val board = Color(0xFF71805A)
    // One quiet, dark field behind the unchanged white timer in both designs.
    val lcd = Color(0xFF293321)
    val red = Color(0xFFB74C3D)
    val yellow = Color(0xFFC9B55E)
    val lamp = when {
        defused -> Color(0xFF9DDE6A)
        led -> Color(0xFFFF5946)
        else -> Color(0xFF6A211B)
    }
    val radius = minOf(size.width, size.height) / 2
    val center = Offset(size.width / 2, size.height / 2)
    val lampRadius = minOf(2.6.dp.toPx(), radius * .05f)
    val end = minOf(20.dp.toPx(), h * .3f)
    val inset = minOf(8.dp.toPx(), h * .14f)
    val lcdSize = Size(w - end * 2, h - inset * 2)
    val redWire = Path().apply {
        moveTo(w * .035f, h * .45f)
        cubicTo(w * .055f, h * .16f, w * .13f, h * .11f, w * .28f, h * .11f)
    }
    val yellowWire = Path().apply {
        moveTo(w * .72f, h * .89f)
        cubicTo(w * .88f, h * .89f, w * .955f, h * .82f, w * .965f, h * .55f)
    }
    val screws = listOf(45.0, 135.0, 225.0, 315.0).map {
        val angle = Math.toRadians(it)
        center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (radius * .79f)
    }
    onDrawBehind {
        if (shape == OverlayShape.CIRCLE) {
            // A circular housing has its own radial construction, rather than a
            // squeezed capsule or a miniature of the full keypad.
            drawCircle(rim, radius * .97f, center)
            drawCircle(strap, radius * .87f, center)
            drawCircle(Color(C4Theme.COMPACT_COLOR), radius * .80f, center)
            val wireInset = radius * .24f
            val wireSize = Size(radius * 1.52f, radius * 1.52f)
            drawArc(red, 205f, 118f, false, Offset(wireInset, wireInset), wireSize, style = wire)
            drawArc(yellow, 25f, 118f, false, Offset(wireInset, wireInset), wireSize, style = wire)
            screws.forEach { screw ->
                drawCircle(strap, 2.4.dp.toPx(), screw)
                drawLine(rim, screw - Offset(line, 0f), screw + Offset(line, 0f), line)
            }
            for (x in listOf(.43f, .5f, .57f)) {
                drawLine(board, Offset(size.width * x, size.height * .75f),
                    Offset(size.width * x, size.height * .83f), line)
                drawCircle(yellow, line, Offset(size.width * x, size.height * .83f))
            }
            drawRoundRect(lcd, Offset(size.width * .10f, size.height * .34f),
                Size(size.width * .80f, size.height * .32f), CornerRadius(5.dp.toPx()))
            drawRoundRect(board, Offset(size.width * .10f, size.height * .34f),
                Size(size.width * .80f, size.height * .32f), CornerRadius(5.dp.toPx()), style = Stroke(line))
            val light = Offset(center.x, size.height * .115f)
            drawCircle(strap, lampRadius * 1.8f, light)
            drawCircle(lamp, lampRadius, light)
        } else {
            // Draw one capsule design in a horizontal coordinate system. A real
            // clockwise quarter-turn maps (x,y) to (width-y,x) for the tall case;
            // only the decoration rotates, never the timer or its tap targets.
            withTransform({
                if (vertical) {
                    translate(left = size.width, top = 0f)
                    rotate(90f, pivot = Offset.Zero)
                }
            }) {
                drawRoundRect(rim, Offset(line * 2, line * 2), Size(w - line * 4, h - line * 4),
                    CornerRadius(h / 2), style = Stroke(line))
                val strapWidth = minOf(5.dp.toPx(), h * .08f)
                for (x in listOf(end * .60f, w - end * .60f - strapWidth)) {
                    drawRect(strap, Offset(x, 0f), Size(strapWidth, h))
                }
                drawPath(redWire, red, style = wire)
                drawPath(yellowWire, yellow, style = wire)
                drawRoundRect(lcd, Offset(end, inset), lcdSize, CornerRadius(4.dp.toPx()))
                drawRoundRect(board, Offset(end, inset), lcdSize, CornerRadius(4.dp.toPx()), style = Stroke(line))
                // Small exposed contacts sit on the rails, outside the display.
                for (x in listOf(.40f, .48f, .56f)) {
                    drawLine(yellow, Offset(w * x, h * .04f), Offset(w * x, h * .09f), line)
                }
                val light = Offset(end * .45f, h / 2)
                drawCircle(strap, lampRadius * 1.8f, light)
                drawCircle(lamp, lampRadius, light)
                val screw = Offset(w - end * .40f, h / 2)
                drawCircle(rim, lampRadius * .8f, screw)
                drawLine(strap, screw - Offset(line, 0f), screw + Offset(line, 0f), line)
            }
        }
    }
}
