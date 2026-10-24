package app.floatingfocus

import kotlin.math.hypot
import kotlin.math.max

data class OverlayDimensions(val width: Float, val height: Float, val scale: Float)

/** Measured from unscaled content, never from the previous window or auto-sized text. */
object OverlayGeometry {
    fun frame(shape: OverlayShape, contentWidth: Float, contentHeight: Float, size: Float,
              maxWidth: Float = Float.POSITIVE_INFINITY, maxHeight: Float = Float.POSITIVE_INFINITY): OverlayDimensions {
        val (width, height) = when (shape) {
            OverlayShape.ROUNDED_RECTANGLE -> {
                val h = max(56f, contentHeight + 24f)
                max(144f, contentWidth + h) to h
            }
            OverlayShape.VERTICAL_CAPSULE -> {
                val w = max(92f, contentWidth + 24f)
                w to max(152f, contentHeight + w)
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
