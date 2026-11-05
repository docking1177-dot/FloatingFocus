package app.floatingfocus

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

internal fun stopwatchTextProbe(text: String,width: Int,height: Int,overflow: Boolean) {
    Log.d("FloatingFocus", "Stopwatch text=$text size=$width,$height overflow=$overflow")
}

/** Debug geometry evidence across real ticks; does not change measurement or state. */
internal fun Modifier.overlayLayoutProbe(role: String, readout: OvertimeReadout? = null, stopwatchElapsed: Long? = null): Modifier = onGloballyPositioned {
    val b = it.boundsInWindow()
    Log.d("FloatingFocus", "Overlay layout t=${SystemClock.elapsedRealtime()} role=$role bounds=${b.left},${b.top},${b.right},${b.bottom}" +
        (readout?.let { " totalMillis=${it.totalMillis} overtimeMillis=${it.overtimeMillis}" } ?: "") +
        (stopwatchElapsed?.let { " stopwatchElapsedMillis=$it" } ?: ""))
}
