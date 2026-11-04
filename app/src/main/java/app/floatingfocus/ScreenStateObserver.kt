package app.floatingfocus

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import android.view.Display
import java.util.concurrent.Executor

/** Observes only the existing screen-driven session. No polling, wake lock, or service startup. */
internal class ScreenStateObserver(context: Context, private val handler: Handler,
                                   private val onChanged: (Boolean) -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(DisplayManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var active = false
    private var registered = false
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) refresh() }
        override fun onDisplayRemoved(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) refresh() }
        override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) refresh() }
    }

    fun start() {
        if (active) return
        active = true
        if (Build.VERSION.SDK_INT >= 36) manager.registerDisplayListener(Executor { handler.post(it) },
            DisplayManager.EVENT_TYPE_DISPLAY_STATE or DisplayManager.EVENT_TYPE_DISPLAY_ADDED or DisplayManager.EVENT_TYPE_DISPLAY_REMOVED, listener)
        else manager.registerDisplayListener(listener, handler)
        registered = true
        refresh() // Register before sampling, so a transition cannot fall into the registration gap.
    }

    fun refresh() {
        if (!active) return
        val lit = runCatching { ScreenOnPolicy.counts(power.isInteractive,
            manager.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON) }.getOrElse {
            Log.w("FloatingFocus", "Screen state unavailable; count remains paused", it)
            false
        }
        onChanged(lit)
    }

    override fun close() {
        active = false
        if (registered) { registered = false; manager.unregisterDisplayListener(listener) }
    }
}
