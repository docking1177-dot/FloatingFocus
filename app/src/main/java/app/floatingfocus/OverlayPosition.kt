package app.floatingfocus

import kotlin.math.roundToInt

data class OverlayOffset(val x: Int, val y: Int)

/** Keep the user's anchor separate from temporary edge clamping during content resize. */
internal class OverlayPosition {
    private var centerX = 0f
    private var centerY = 0f

    fun anchor(x: Int, y: Int, width: Int, height: Int) {
        centerX = x + width / 2f
        centerY = y + height / 2f
    }

    fun place(width: Int, height: Int, availableWidth: Int, availableHeight: Int): OverlayOffset = OverlayOffset(
        (centerX - width / 2f).roundToInt().coerceIn(0, (availableWidth - width).coerceAtLeast(0)),
        (centerY - height / 2f).roundToInt().coerceIn(0, (availableHeight - height).coerceAtLeast(0))
    )
}
