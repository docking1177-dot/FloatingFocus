package app.floatingfocus

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/** Owns dragging for the whole measured window, including Compose buttons and static text. */
@SuppressLint("ViewConstructor") // Created with callbacks by OverlayWindow, never inflated from XML.
internal class OverlayTouchLayout(
    context: Context,
    private val onDragStart: () -> Unit,
    private val onDrag: (Float, Float) -> Unit
) : FrameLayout(context) {
    private val gesture = OverlayDragGesture(ViewConfiguration.get(context).scaledTouchSlop)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture.start(event.rawX, event.rawY)
                onDragStart()
            }
            MotionEvent.ACTION_MOVE -> moveWindow(event)
            MotionEvent.ACTION_UP -> {
                // Account for a final displacement even if MOVE events were coalesced.
                moveWindow(event)
                if (!gesture.finish()) super.dispatchTouchEvent(event)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Cancel the whole gesture when another finger joins; never turn it into a click.
                gesture.cancel()
                cancelChildTouch(event)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                gesture.cancel()
                super.dispatchTouchEvent(event)
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
