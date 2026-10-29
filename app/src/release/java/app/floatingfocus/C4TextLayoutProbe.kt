package app.floatingfocus

import androidx.compose.ui.text.TextLayoutResult

/** Release does not install Debug glyph measurements or log callbacks. */
@Suppress("UNUSED_PARAMETER")
internal fun c4TextLayoutProbe(field: String, length: Int): ((TextLayoutResult) -> Unit)? = null
