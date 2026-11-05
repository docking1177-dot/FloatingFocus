/*
 * Adapted from FloatingCountdownTimer TimerViewHolder.kt and OverlayController.kt
 * Copyright (c) 2022 Thomas Berghuis. MIT; see licenses/FloatingCountdownTimer-MIT.txt.
 * Changes: single window, safe bounds, touch slop, explicit composition/store disposal.
 */
package app.floatingfocus

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.roundToInt

private class FloatingLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle = registry
    override val savedStateRegistry: SavedStateRegistry = saved.savedStateRegistry

    fun create() {
        saved.performAttach()
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
    fun destroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}

class OverlayWindow(private val context: Context,
                    private val initialPosition: OverlayAnchor? = null,
                    private val onPositionChanged: (OverlayAnchor) -> Unit = {},
                    initialPositions: OverlayPositions? = null,
                    private val onPositionsChanged: (OverlayPositions) -> Unit = {},
                    private val onFailure: (RuntimeException) -> Unit) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val owner = FloatingLifecycleOwner()
    private val composeView = ComposeView(context)
    private var dragStartX = 0
    private var dragStartY = 0
    private val view = OverlayTouchLayout(context,
        onDragStart = ::beginDrag,
        onDrag = ::dragTo, onDragEnd = ::persistPosition, onDragCancel = ::cancelDrag,
        onMeasured = ::placeMeasured, measureBounds = ::measureBounds)
    private var attached = false
    private var closed = false
    private var positioned = false
    private val position = OverlayPosition()
    private var contentGeometry: OverlayContentGeometry? = null
    private var contentPlacement: OverlayContentPlacement? = null
    private var c4Active = false
    private var rememberedPositions = initialPositions ?: OverlayPositions(legacy=initialPosition)
    // Session-only defaults are not user drags. Keep them across direction/size
    // changes so returning while expanded cannot redefine the compact anchor.
    private val defaultAnchors = mutableMapOf<OverlayOrientation, OverlayAnchor>()
    private var viewport: Point? = null
    private var direction = OverlayOrientation.PORTRAIT
    private val dragCheckpoint=OverlayDragCheckpoint()
    private var ownerCreated = false
    // Raw drag coordinates use the physical left edge, also in RTL locales.
    @SuppressLint("RtlHardcoded")
    private val params = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 34) {
            // Resize and its content-anchored offset are one visual change, not
            // a new-size buffer followed by WindowManager's position animation.
            setCanPlayMoveAnimation(false)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        }
    }

    fun show(content: @Composable () -> Unit) {
        if (attached || closed) return
        owner.create()
        ownerCreated = true
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent(content)
        view.addView(composeView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        try {
            manager.addView(view, params)
            attached = true
        } catch (e: RuntimeException) { onFailure(e) }
    }

    @Suppress("DEPRECATION")
    private fun safeSize(): Point {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return Point(metrics.bounds.width() - insets.left - insets.right,
                metrics.bounds.height() - insets.top - insets.bottom)
        }
        // API 29 exposes the visible system-decor frame without internal resource lookup.
        val frame = Rect()
        view.getWindowVisibleDisplayFrame(frame)
        if (!frame.isEmpty) return Point(frame.width(), frame.height())
        // Only needed before attachment; the first layout clamps again using the frame.
        return Point().also { manager.defaultDisplay.getSize(it) }
    }

    fun clampPosition() {
        // Re-measure content as well as the frame after the safe area changes.
        val size=safeSize()
        if (viewport?.x!=size.x || viewport?.y!=size.y) {
            view.cancelGesture();cancelDrag();position.releaseContent()
            contentGeometry=null;contentPlacement=null
        }
        view.requestLayout()
    }

    private fun measureBounds(): Point = safeSize().also { size ->
        if (viewport?.x != size.x || viewport?.y != size.y) {
            view.cancelGesture()
            cancelDrag()
            position.releaseContent()
            contentGeometry=null; contentPlacement=null; positioned=false
            direction=OverlayOrientation.at(size.x,size.y)
            viewport=Point(size)
            val migrated=rememberedPositions.migrate(direction)
            if (migrated!=rememberedPositions) { rememberedPositions=migrated; onPositionsChanged(migrated) }
        }
    }

    fun positionsSnapshot(): OverlayPositions = rememberedPositions

    private fun restorePosition(width: Int, height: Int, size: Point) {
        val preferred = dragCheckpoint.pending(OverlayViewport(size.x,size.y))
            ?: rememberedPositions.anchor(direction) ?: defaultAnchors[direction]
        if (!positioned) {
            positioned = true
            position.anchor(size.x - width - dp(16), dp(36), width, height)
            if (preferred == null) defaultAnchors[direction] = position.snapshot(size.x,size.y)
        }
        preferred?.let { position.restore(it,size.x,size.y) }
    }

    fun measureContent(content: OverlayContentGeometry): OverlayContentPlacement {
        val size = viewport ?: measureBounds()
        if (content.hasControls && contentGeometry?.hasControls == false) {
            currentContentPlacement()?.let { position.pinContent(params.x + it.headerX, params.y + it.headerY) }
        } else if (!content.hasControls) position.releaseContent()
        restorePosition(content.width, content.height, size)
        contentGeometry = content
        return position.placeContent(content, size.x, size.y).also { contentPlacement = it }
    }

    private fun placeMeasured(width: Int, height: Int) {
        if (!attached || closed || width <= 0 || height <= 0) return
        val size = viewport ?: measureBounds()
        restorePosition(width, height, size)
        val content = contentGeometry?.takeIf { !c4Active && it.width == width && it.height == height }
        // Compose may reuse a same-constraint measurement. Position the header
        // that was actually placed, instead of independently choosing a new local layout.
        val placed = contentPlacement?.takeIf { content != null }
        val (x, y) = position.place(width, height, size.x, size.y,
            placed?.headerX ?: width / 2f, placed?.headerY ?: height / 2f)
        // Do not leave a content-sized window in WRAP_CONTENT across text ticks:
        // system relayout can otherwise re-resolve its frame before anchoring it.
        // Size and position are submitted together, only when either really changes.
        if (params.x == x && params.y == y && params.width == width && params.height == height) return
        params.width = width
        params.height = height
        params.x = x
        params.y = y
        update()
    }

    private fun update() {
        if (!attached || closed) return
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            Log.d("FloatingFocus", "Overlay window xy=${params.x},${params.y} measured=${view.measuredWidth}x${view.measuredHeight} c4=$c4Active")
        try { manager.updateViewLayout(view, params) }
        catch (e: RuntimeException) { onFailure(e) }
    }

    fun close() {
        if (closed) return
        view.cancelGesture()
        cancelDrag()
        closed = true
        composeView.disposeComposition()
        if (ownerCreated) owner.destroy()
        if (attached) {
            try { manager.removeViewImmediate(view) } catch (_: IllegalArgumentException) { /* already detached */ }
            attached = false
        }
    }

    private fun beginDrag() {
        cancelDrag()
        val size=safeSize()
        if (viewport?.x!=size.x || viewport?.y!=size.y) { clampPosition();return }
        dragCheckpoint.begin(OverlayViewport(size.x,size.y))
        dragStartX = params.x
        dragStartY = params.y
        val content = currentContentPlacement()
        position.beginDrag(params.x, params.y, view.width, view.height,
            content?.headerX ?: view.width / 2f, content?.headerY ?: view.height / 2f)
    }

    private fun currentContentPlacement(): OverlayContentPlacement? = contentPlacement?.takeIf {
        !c4Active && contentGeometry?.width == view.width && contentGeometry?.height == view.height
    }

    private fun dragTo(dx: Float, dy: Float) {
        val size = safeSize()
        if (!dragCheckpoint.accepts(OverlayViewport(size.x,size.y))) { view.cancelGesture();cancelDrag();view.requestLayout();return }
        val content = currentContentPlacement()
        val (x, y) = position.dragTo(dx, dy, view.width, view.height, size.x, size.y,
            content?.headerX ?: view.width / 2f, content?.headerY ?: view.height / 2f)
        params.x = x
        params.y = y
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d("FloatingFocus", "Overlay drag from=$dragStartX,$dragStartY delta=$dx,$dy placed=${params.x},${params.y} view=${view.width}x${view.height} safe=${size.x}x${size.y}")
        dragCheckpoint.move(OverlayViewport(size.x,size.y),position.snapshot(size.x,size.y))
        update()
    }

    private fun persistPosition() {
        val size=safeSize()
        val anchor=dragCheckpoint.finish(OverlayViewport(size.x,size.y)) ?: return
        if (viewport?.x!=size.x || viewport?.y!=size.y) return
        rememberedPositions=rememberedPositions.remember(direction,anchor)
        onPositionsChanged(rememberedPositions)
        onPositionChanged(anchor)
    }

    private fun cancelDrag() { dragCheckpoint.cancel() }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    fun setGeometry(shape: OverlayShape, size: Float, device: Boolean = false, opacity: Float = 1f, c4Active: Boolean = device) {
        this.c4Active = c4Active
        if (c4Active) { contentGeometry = null; contentPlacement = null; position.releaseContent() }
        view.setGeometry(shape, size, device, c4Active)
        val windowAlpha = if (c4Active) opacity else 1f
        if (params.alpha != windowAlpha) { params.alpha = windowAlpha; update() }
    }
}
