package app.floatingfocus

import kotlin.math.abs

/** Screen-coordinate gesture state; independent of the overlay's changing size and position. */
internal class OverlayDragGesture(private val touchSlop: Int) {
    private var startX = 0f
    private var startY = 0f
    private var active = false
    var suppressClick = false
        private set

    fun start(rawX: Float, rawY: Float) {
        startX = rawX
        startY = rawY
        active = true
        suppressClick = false
    }

    fun move(rawX: Float, rawY: Float): Movement? {
        if (!active) return null
        val dx = rawX - startX
        val dy = rawY - startY
        if (!suppressClick && abs(dx) <= touchSlop && abs(dy) <= touchSlop) return null
        val firstMove = !suppressClick
        suppressClick = true
        return Movement(dx, dy, cancelChild = firstMove)
    }

    fun cancel() {
        active = false
        suppressClick = true
    }

    /** True means the child must not receive UP and accidentally execute a button click. */
    fun finish(): Boolean {
        val consumed = suppressClick
        active = false
        suppressClick = false
        return consumed
    }

    data class Movement(val dx: Float, val dy: Float, val cancelChild: Boolean)
}
