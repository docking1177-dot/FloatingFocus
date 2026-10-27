package app.floatingfocus

import kotlin.math.roundToInt

data class OverlayOffset(val x: Int, val y: Int)

/** Center in the safe display area, independent of density and screen orientation. */
data class OverlayAnchor(val xFraction: Float, val yFraction: Float) {
    init {
        require(xFraction.isFinite() && xFraction in 0f..1f)
        require(yFraction.isFinite() && yFraction in 0f..1f)
    }
    companion object {
        fun decode(x: Any?, y: Any?): OverlayAnchor? {
            val xf = (x as? Number)?.toFloat()?.takeIf { it.isFinite() && it in 0f..1f } ?: return null
            val yf = (y as? Number)?.toFloat()?.takeIf { it.isFinite() && it in 0f..1f } ?: return null
            return OverlayAnchor(xf, yf)
        }
    }
}

/** Keep the user's anchor separate from temporary edge clamping during content resize. */
internal class OverlayPosition {
    private var centerX = 0f
    private var centerY = 0f

    fun anchor(x: Int, y: Int, width: Int, height: Int) {
        centerX = x + width / 2f
        centerY = y + height / 2f
    }

    fun snapshot(availableWidth: Int, availableHeight: Int) = OverlayAnchor(
        (centerX / availableWidth.coerceAtLeast(1)).coerceIn(0f, 1f),
        (centerY / availableHeight.coerceAtLeast(1)).coerceIn(0f, 1f))

    fun restore(anchor: OverlayAnchor, availableWidth: Int, availableHeight: Int) {
        centerX = anchor.xFraction * availableWidth
        centerY = anchor.yFraction * availableHeight
    }

    fun place(width: Int, height: Int, availableWidth: Int, availableHeight: Int): OverlayOffset = OverlayOffset(
        (centerX - width / 2f).roundToInt().coerceIn(0, (availableWidth - width).coerceAtLeast(0)),
        (centerY - height / 2f).roundToInt().coerceIn(0, (availableHeight - height).coerceAtLeast(0))
    )
}
