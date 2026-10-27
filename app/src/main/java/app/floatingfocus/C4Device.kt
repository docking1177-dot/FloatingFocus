package app.floatingfocus

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas

/** Original vector illustration: explosive blocks, straps, controller, keypad, wires and LED. */
internal fun DrawScope.drawC4Device(title: String, frame: C4Frame, expanded: Boolean, paused: Boolean) {
    val w = size.width; val h = size.height
    fun block(x: Float, y: Float, width: Float, height: Float, color: Long, radius: Float = .04f) =
        drawRoundRect(Color(color), Offset(w*x, h*y), Size(w*width, h*height), CornerRadius(h*radius))
    block(0f, 0f, 1f, 1f, 0xFF343A28)
    // Expanded controls need a quiet lower area; decoration never sits behind button labels.
    val blocksHeight = if (expanded) .32f else .80f
    repeat(3) { i -> block(.045f + i*.07f, .10f, .06f, blocksHeight, 0xFFB59C6D) }
    block(.035f, .26f, .24f, .10f, 0xFF20241D)
    block(.035f, if (expanded) .34f else .67f, .24f, if (expanded) .06f else .10f, 0xFF20241D)
    block(.29f, .10f, .66f, .80f, 0xFF596143)
    fun wire(y: Float, color: Long) {
        val path = Path().apply {
            moveTo(w*.10f, h*y)
            cubicTo(w*.35f, h*(y-.12f), w*.68f, h*(y+.12f), w*.89f, h*y)
        }
        drawPath(path, Color(color), style = androidx.compose.ui.graphics.drawscope.Stroke((h*.025f).coerceAtLeast(1f)))
    }
    wire(.12f, 0xFFCB3D32); wire(.87f, 0xFFE3C04C)
    repeat(3) { row -> repeat(3) { col ->
        block(.765f+col*.052f, (if (expanded) .37f else .39f)+row*(if (expanded) .065f else .13f),
            .043f, if (expanded) .045f else .10f, 0xFFBFC0A6, .015f)
    } }
    drawCircle(if (frame.led) Color(0xFFFF5344) else Color(0xFF531F19), h*.06f, Offset(w*.86f, h*.25f))
    if (frame.led) drawCircle(Color(0x55FF3828), h*.10f, Offset(w*.86f, h*.25f))
    if (!expanded) {
        block(.32f, .24f, .41f, .53f, 0xFF142018)
        val text = title.removePrefix("◷").trim()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFDAF293.toInt(); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER; textSize = minOf(h*.34f, w*.37f / (text.length*.65f).coerceAtLeast(1f))
        }
        val baseline = h*.51f - (paint.fontMetrics.ascent + paint.fontMetrics.descent)/2
        drawContext.canvas.nativeCanvas.drawText(text, w*.525f, baseline, paint)
        if (paused) {
            paint.textSize = h*.16f
            drawContext.canvas.nativeCanvas.drawText("Ⅱ", w*.525f, h*.94f, paint)
        }
    }
}
