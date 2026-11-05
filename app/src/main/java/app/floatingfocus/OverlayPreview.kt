package app.floatingfocus

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/** Measure the real renderer naturally, then fit its preview without changing its geometry. */
@Composable
internal fun OverlayPreview(height: Dp,content: @Composable ()->Unit) {
    Layout(modifier=Modifier.fillMaxWidth().height(height),content=content) { measurables,constraints ->
        val overlay=measurables.single().measure(Constraints(maxWidth=constraints.maxWidth))
        val width=constraints.maxWidth;val heightPx=constraints.maxHeight
        val fit=minOf(1f,width.toFloat()/overlay.width.coerceAtLeast(1),heightPx.toFloat()/overlay.height.coerceAtLeast(1))
        layout(width,heightPx) {
            overlay.placeRelativeWithLayer((width-overlay.width)/2,(heightPx-overlay.height)/2) { scaleX=fit;scaleY=fit }
        }
    }
}
