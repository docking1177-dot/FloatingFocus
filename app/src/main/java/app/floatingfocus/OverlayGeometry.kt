package app.floatingfocus

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

data class OverlayDimensions(val width: Float, val height: Float, val scale: Float)

/** Measured from unscaled content, never from the previous window or auto-sized text. */
object OverlayGeometry {
    fun frame(shape: OverlayShape, contentWidth: Float, contentHeight: Float, size: Float,
              maxWidth: Float = Float.POSITIVE_INFINITY, maxHeight: Float = Float.POSITIVE_INFINITY,
              expanded: Boolean = false): OverlayDimensions {
        val (width, height) = when (shape) {
            OverlayShape.ROUNDED_RECTANGLE -> {
                val h = max(56f, contentHeight + if (expanded) 16f else 24f)
                // Fit the content rectangle inside the round ends instead of adding
                // an entire window height to its width when controls are shown.
                val ends = if (expanded) h - sqrt((h*h - contentHeight*contentHeight).coerceAtLeast(0f)) + 12f else h
                max(144f, contentWidth + ends) to h
            }
            OverlayShape.VERTICAL_CAPSULE -> {
                val w = max(92f, contentWidth + 24f)
                val ends = if (expanded) w - sqrt((w*w - contentWidth*contentWidth).coerceAtLeast(0f)) + 12f else w
                w to max(max(152f, w + 24f), contentHeight + ends)
            }
            OverlayShape.CIRCLE -> {
                val diameter = max(124f, hypot(contentWidth + 24f, contentHeight + 24f))
                diameter to diameter
            }
        }
        val fit = minOf(size, maxWidth / width, maxHeight / height).coerceAtLeast(0f)
        return OverlayDimensions(width * fit, height * fit, fit)
    }
}
