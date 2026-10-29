package app.floatingfocus

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.util.Log

/** Owns dragging for the whole measured window, including Compose buttons and static text. */
@SuppressLint("ViewConstructor") // Created with callbacks by OverlayWindow, never inflated from XML.
internal class OverlayTouchLayout(
    context: Context,
    private val onDragStart: () -> Unit,
    private val onDrag: (Float, Float) -> Unit,
    private val onDragEnd: () -> Unit = {},
    private val onMeasured: (Int, Int) -> Unit = { _, _ -> }
) : FrameLayout(context) {
    private val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val gesture = OverlayDragGesture(ViewConfiguration.get(context).scaledTouchSlop)
    private var geometry = OverlayShape.ROUNDED_RECTANGLE
    private var scale = 1f
    private var acceptingTouch = false
    private var childScrolling = false
    private var c4 = false
    private var c4Active = false
    private var keypadGesture = false
    private val touchRegion = Region()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // ViewRoot submits the measured size after this returns. Update the anchor
        // offset in that same traversal, before the new content can be drawn.
        onMeasured(measuredWidth, measuredHeight)
    }

    fun setGeometry(shape: OverlayShape, size: Float, device: Boolean = false, active: Boolean = device) {
        if (geometry == shape && scale == size && c4 == device && c4Active == active && !touchRegion.isEmpty) return
        geometry = shape
        scale = size
        c4 = device
        c4Active = active
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
            if (c4) {
                val device = C4Geometry((width - inset * 2) / density, (height - inset * 2) / density)
                val body = device.body
                addRoundRect(RectF(inset + body.x * density, inset + body.y * density,
                    inset + (body.x + body.width) * density, inset + (body.y + body.height) * density), 7 * density, 7 * density, Path.Direction.CW)
                addRect(inset + device.width * .04f * density, inset + device.height * .15f * density,
                    inset + device.width * .18f * density, inset + device.height * .33f * density, Path.Direction.CW)
                val panel = device.keyboardPanel
                addRoundRect(RectF(inset + panel.x * density, inset + panel.y * density,
                    inset + (panel.x + panel.width) * density, inset + (panel.y + panel.height) * density), 3 * density, 3 * density, Path.Direction.CW)
            } else if (geometry == OverlayShape.CIRCLE) addOval(rect, Path.Direction.CW)
            else {
                val radius = minOf(rect.width(), rect.height()) / 2
                addRoundRect(rect, radius, radius, Path.Direction.CW)
            }
        }
        touchRegion.setPath(path, Region(0, 0, width, height))
        // Public API from Android 13; older versions still reject corner drags/clicks below.
        if (Build.VERSION.SDK_INT >= 33) rootSurfaceControl?.setTouchableRegion(touchRegion)
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Native duration wheels own their gesture until UP; never turn it into a window drag.
        // Compose clickables can request this even for an ordinary body tap. C4
        // body gestures belong to the host; only the keyboard can own a scroll.
        if (debug && c4Active) Log.d("FloatingFocus", "C4 touch disallow=$disallowIntercept key=$keypadGesture childScroll=$childScrolling")
        if (disallowIntercept && (!c4Active || keypadGesture)) childScrolling = true
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                childScrolling = false
                val density = resources.displayMetrics.density
                val device = C4Geometry(width / density - 4, height / density - 4)
                keypadGesture = c4 && device.keyboardPanel.contains(event.x / density - 2, event.y / density - 2)
                if (keypadGesture) childScrolling = true
                acceptingTouch = touchRegion.contains(event.x.toInt(), event.y.toInt())
                if (debug && c4Active) Log.d("FloatingFocus", "C4 touch DOWN xy=${event.x},${event.y} frame=${width}x$height device=$c4 key=$keypadGesture accepted=$acceptingTouch")
                if (!acceptingTouch) return false
                gesture.start(event.rawX, event.rawY)
                onDragStart()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!acceptingTouch) return false
                if (keypadGesture && !keypadOverflows()) cancelKeyDrag(event)
                else if (!childScrolling) moveWindow(event)
            }
            MotionEvent.ACTION_UP -> {
                if (!acceptingTouch) return false
                // Account for a final displacement even if MOVE events were coalesced.
                if (keypadGesture && !keypadOverflows()) cancelKeyDrag(event)
                else if (!childScrolling) moveWindow(event)
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

    private fun keypadOverflows(): Boolean {
        val density = resources.displayMetrics.density
        val device = C4Geometry(width / density - 4, height / density - 4)
        return device.keypad.height > device.keypadViewportHeight
    }

    private fun cancelKeyDrag(event: MotionEvent) {
        val move = gesture.move(event.rawX, event.rawY) ?: return
        if (move.cancelChild) cancelChildTouch(event)
    }

    private fun moveWindow(event: MotionEvent) {
        val movement = gesture.move(event.rawX, event.rawY) ?: return
        if (movement.cancelChild) cancelChildTouch(event)
        if (debug && c4Active) Log.d("FloatingFocus", "C4 touch MOVE dx=${movement.dx} dy=${movement.dy} childScroll=$childScrolling")
        onDrag(movement.dx, movement.dy)
    }

    private fun cancelChildTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        try { super.dispatchTouchEvent(cancel) }
        finally { cancel.recycle() }
    }
}
