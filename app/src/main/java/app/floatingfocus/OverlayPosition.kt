package app.floatingfocus

import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.sqrt

data class OverlayOffset(val x: Int, val y: Int)

/** Measured pixels, including the transparent outer inset. Both directions use the same content. */
data class OverlayContentGeometry(
    val width: Int, val height: Int,
    val headerWidth: Float, val headerHeight: Float,
    val headerBelowY: Float, val headerAboveY: Float,
    val inset: Float = 0f
) {
    fun centered() = OverlayContentPlacement(OverlayOffset(0, 0), width / 2f, headerBelowY, false)
    val hasControls get() = headerBelowY != headerAboveY

    internal fun headerXRange(y: Float): ClosedFloatingPointRange<Float> {
        val radius = minOf(width - inset * 2, height - inset * 2) / 2f
        val edge = minOf(y - headerHeight / 2 - inset, height - inset - y - headerHeight / 2)
        val dy = (radius - edge).coerceIn(0f, radius)
        val capInset = radius - sqrt((radius * radius - dy * dy).coerceAtLeast(0f))
        val minimum = (inset + capInset + headerWidth / 2).coerceAtMost(width / 2f)
        return minimum..(width - minimum)
    }
}

data class OverlayContentPlacement(
    val offset: OverlayOffset, val headerX: Float, val headerY: Float, val controlsAbove: Boolean
)

/** Preferred time center in the safe display area, independent of density and orientation. */
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

/** Keep the user's time anchor separate from temporary edge clamping during content resize. */
internal class OverlayPosition {
    private var centerX = 0f
    private var centerY = 0f
    private var dragOriginX = 0f
    private var dragOriginY = 0f
    private var pinnedX: Float? = null
    private var pinnedY: Float? = null
    private val renderedX get() = pinnedX ?: centerX
    private val renderedY get() = pinnedY ?: centerY

    fun pinContent(x: Float, y: Float) { pinnedX = x; pinnedY = y }
    fun releaseContent() { pinnedX = null; pinnedY = null }

    fun anchor(x: Int, y: Int, width: Int, height: Int,
               contentX: Float = width / 2f, contentY: Float = height / 2f) {
        centerX = x + contentX
        centerY = y + contentY
        if (pinnedX != null) pinContent(centerX, centerY)
    }

    fun snapshot(availableWidth: Int, availableHeight: Int) = OverlayAnchor(
        (centerX / availableWidth.coerceAtLeast(1)).coerceIn(0f, 1f),
        (centerY / availableHeight.coerceAtLeast(1)).coerceIn(0f, 1f))

    fun restore(anchor: OverlayAnchor, availableWidth: Int, availableHeight: Int) {
        centerX = anchor.xFraction * availableWidth
        centerY = anchor.yFraction * availableHeight
    }

    fun place(width: Int, height: Int, availableWidth: Int, availableHeight: Int,
              contentX: Float = width / 2f, contentY: Float = height / 2f): OverlayOffset = OverlayOffset(
        (renderedX - contentX).roundToInt().coerceIn(0, (availableWidth - width).coerceAtLeast(0)),
        (renderedY - contentY).roundToInt().coerceIn(0, (availableHeight - height).coerceAtLeast(0))
    )

    fun beginDrag(x: Int, y: Int, width: Int, height: Int,
                  contentX: Float = width / 2f, contentY: Float = height / 2f) {
        dragOriginX = x + contentX
        dragOriginY = y + contentY
    }

    fun dragTo(dx: Float, dy: Float, width: Int, height: Int, availableWidth: Int, availableHeight: Int,
               contentX: Float = width / 2f, contentY: Float = height / 2f): OverlayOffset {
        // MOVE coordinates are cumulative from DOWN. A remeasured header or a
        // changed frame must never become the next movement's starting point.
        centerX = dragOriginX + dx
        centerY = dragOriginY + dy
        if (pinnedX != null) pinContent(centerX, centerY)
        return place(width, height, availableWidth, availableHeight, contentX, contentY).also {
            anchor(it.x, it.y, width, height, contentX, contentY)
        }
    }

    fun placeContent(content: OverlayContentGeometry, availableWidth: Int, availableHeight: Int): OverlayContentPlacement {
        val maxY = (availableHeight - content.height).coerceAtLeast(0)
        fun overflow(y: Float) = max(0f, -y) + max(0f, y - maxY)
        val above = overflow(renderedY - content.headerAboveY) < overflow(renderedY - content.headerBelowY)
        val headerY = if (above) content.headerAboveY else content.headerBelowY
        val x = (renderedX - content.width / 2f).roundToInt().coerceIn(0, (availableWidth - content.width).coerceAtLeast(0))
        val y = (renderedY - headerY).roundToInt().coerceIn(0, maxY)
        // Edge clamping may move the outer frame. Keep the time at the old X
        // within that frame, as far as the actual rounded boundary allows.
        val range = content.headerXRange(headerY)
        val headerX = (renderedX - x).coerceIn(range.start, range.endInclusive)
        return OverlayContentPlacement(OverlayOffset(x, y), headerX, headerY, above)
    }
}
