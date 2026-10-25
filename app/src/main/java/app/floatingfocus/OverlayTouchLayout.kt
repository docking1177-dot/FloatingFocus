package app.floatingfocus

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.os.Build

/** Owns dragging for the whole measured window, including Compose buttons and static text. */
@SuppressLint("ViewConstructor") // Created with callbacks by OverlayWindow, never inflated from XML.
internal class OverlayTouchLayout(
    context: Context,
    private val onDragStart: () -> Unit,
    private val onDrag: (Float, Float) -> Unit,
    private val onDragEnd: () -> Unit = {}
) : FrameLayout(context) {
    private val gesture = OverlayDragGesture(ViewConfiguration.get(context).scaledTouchSlop)
    private var geometry = OverlayShape.ROUNDED_RECTANGLE
    private var scale = 1f
    private var acceptingTouch = false
    private val touchRegion = Region()

    fun setGeometry(shape: OverlayShape, size: Float) {
        if (geometry == shape && scale == size && !touchRegion.isEmpty) return
        geometry = shape
        scale = size
        updateRegion()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateRegion()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { updateRegion() }
    }

    private fun updateRegion() {
        if (width == 0 || height == 0) return
        val density = resources.displayMetrics.density
        val inset = 2 * density
        val rect = RectF(inset, inset, width - inset, height - inset)
        val path = Path().apply {
            if (geometry == OverlayShape.CIRCLE) addOval(rect, Path.Direction.CW)
            else {
                val radius = minOf(rect.width(), rect.height()) / 2
                addRoundRect(rect, radius, radius, Path.Direction.CW)
            }
        }
        touchRegion.setPath(path, Region(0, 0, width, height))
        // Public API from Android 13; older versions still reject corner drags/clicks below.
        if (Build.VERSION.SDK_INT >= 33) rootSurfaceControl?.setTouchableRegion(touchRegion)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                acceptingTouch = touchRegion.contains(event.x.toInt(), event.y.toInt())
                if (!acceptingTouch) return false
                gesture.start(event.rawX, event.rawY)
                onDragStart()
            }
            MotionEvent.ACTION_MOVE -> { if (!acceptingTouch) return false; moveWindow(event) }
            MotionEvent.ACTION_UP -> {
                if (!acceptingTouch) return false
                // Account for a final displacement even if MOVE events were coalesced.
                moveWindow(event)
                if (!gesture.finish()) super.dispatchTouchEvent(event)
                onDragEnd()
                acceptingTouch = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Cancel the whole gesture when another finger joins; never turn it into a click.
                gesture.cancel()
                cancelChildTouch(event)
                onDragEnd()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                acceptingTouch = false
                gesture.cancel()
                super.dispatchTouchEvent(event)
                onDragEnd()
                return true
            }
        }
        // Observe at the root before Compose or AndroidView consumes the event. A drag
        // cancels the original child target; ordinary taps retain native button behavior.
        if (!gesture.suppressClick) super.dispatchTouchEvent(event)
        // Keep receiving moves even when DOWN landed on non-clickable text or padding.
        return true
    }

    private fun moveWindow(event: MotionEvent) {
        val movement = gesture.move(event.rawX, event.rawY) ?: return
        if (movement.cancelChild) cancelChildTouch(event)
        onDrag(movement.dx, movement.dy)
    }

    private fun cancelChildTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        try { super.dispatchTouchEvent(cancel) }
        finally { cancel.recycle() }
    }
}
