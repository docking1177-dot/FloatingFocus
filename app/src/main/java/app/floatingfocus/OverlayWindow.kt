/*
 * Adapted from FloatingCountdownTimer TimerViewHolder.kt and OverlayController.kt
 * Copyright (c) 2022 Thomas Berghuis. MIT; see licenses/FloatingCountdownTimer-MIT.txt.
 * Changes: single window, safe bounds, touch slop, explicit composition/store disposal.
 */
package app.floatingfocus

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
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

class OverlayWindow(private val context: Context, private val onFailure: (RuntimeException) -> Unit) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val owner = FloatingLifecycleOwner()
    private val composeView = ComposeView(context)
    private var dragStartX = 0
    private var dragStartY = 0
    private val view = OverlayTouchLayout(context,
        onDragStart = { dragStartX = params.x; dragStartY = params.y },
        onDrag = ::dragTo)
    private var attached = false
    private var closed = false
    private var positioned = false
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
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!positioned && view.width > 0) {
                positioned = true
                params.x = safeSize().x - view.width - dp(16)
                params.y = dp(36)
                update()
            }
            clampPosition()
        }
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
        if (!attached || closed) return
        val size = safeSize()
        val x = params.x.coerceIn(0, (size.x - view.width).coerceAtLeast(0))
        val y = params.y.coerceIn(0, (size.y - view.height).coerceAtLeast(0))
        if (params.x == x && params.y == y) return
        params.x = x
        params.y = y
        update()
    }

    private fun update() {
        if (!attached || closed) return
        try { manager.updateViewLayout(view, params) }
        catch (e: RuntimeException) { onFailure(e) }
    }

    fun close() {
        if (closed) return
        closed = true
        composeView.disposeComposition()
        if (ownerCreated) owner.destroy()
        if (attached) {
            try { manager.removeViewImmediate(view) } catch (_: IllegalArgumentException) { /* already detached */ }
            attached = false
        }
    }

    private fun dragTo(dx: Float, dy: Float) {
        val size = safeSize()
        params.x = (dragStartX + dx).roundToInt().coerceIn(0, (size.x - view.width).coerceAtLeast(0))
        params.y = (dragStartY + dy).roundToInt().coerceIn(0, (size.y - view.height).coerceAtLeast(0))
        update()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
