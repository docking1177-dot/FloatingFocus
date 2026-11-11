package app.floatingfocus

/** Native opacity carries the obscuring limit; relative paints apply each requested alpha once. */
data class OverlayOpacity(val requestedBackground: Float, val requestedForeground: Float) {
    val window = maxOf(requestedBackground, requestedForeground)
    val background get() = if (window > 0f) requestedBackground / window else 0f
    val foreground get() = if (window > 0f) requestedForeground / window else 0f
}
