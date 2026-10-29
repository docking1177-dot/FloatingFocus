package app.floatingfocus

import android.util.Log
import androidx.compose.ui.text.TextLayoutResult

/** Observe real glyph bounds in Debug, including nonempty LCD and large-font layouts. */
internal fun c4TextLayoutProbe(field: String, length: Int): (TextLayoutResult) -> Unit = { layout ->
    val fits = !layout.hasVisualOverflow && layout.layoutInput.text.indices.all { index ->
        val glyph = layout.getBoundingBox(index)
        glyph.left >= -.5f && glyph.top >= -.5f && glyph.right <= layout.size.width + .5f && glyph.bottom <= layout.size.height + .5f
    }
    Log.d("FloatingFocus", "C4 text field=$field length=$length fits=$fits size=${layout.size.width}x${layout.size.height}")
}
