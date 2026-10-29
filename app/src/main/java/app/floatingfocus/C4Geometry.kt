package app.floatingfocus

data class C4Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun contains(px: Float, py: Float) = px >= x && py >= y && px < x + width && py < y + height
}

/** One geometry for drawing, Compose layout and the host's public touch region. Coordinates are dp. */
class C4Geometry(val width: Float, val height: Float) {
    val compact = height < 340
    val display = C4Rect(width * .23f, if (compact) 4f else 48f,
        width * .66f, if (height < 240) 24f else if (compact) 40f else 58f)
    val gap = if (height < 240) 2f else if (compact) 6f else 8f
    val rowGap = if (compact) 2f else 6f
    val panelPadding = 6f
    val keyWidth = ((width * .60f - gap * 2) / 3).coerceAtLeast(44f)
    val keyHeight = 44f
    val keypad = C4Rect(((width - keyWidth * 3 - gap * 2) / 2 + 6).coerceAtLeast(0f),
        // Preserve the display-to-keyboard anchor; shorten only the lower keyboard.
        (height - if (compact) 212f else 226f).coerceAtLeast(display.y + display.height + 6),
        keyWidth * 3 + gap * 2, keyHeight * 4 + rowGap * 3)
    val keyboardPanel get() = C4Rect(keypad.x - panelPadding, keypad.y - panelPadding, keypad.width + panelPadding * 2,
        minOf(keypad.height + panelPadding * 2, (height - keypad.y + panelPadding).coerceAtLeast(0f)))
    val keypadViewportHeight get() = minOf(keypad.height, (height - keypad.y).coerceAtLeast(1f))
    val body = C4Rect(width * .14f, 10f, width * .80f, height - 18f)
    fun key(index: Int) = C4Rect(keypad.x + index % 3 * (keyWidth + gap),
        keypad.y + index / 3 * (keyHeight + rowGap), keyWidth, keyHeight)
    companion object {
        val keys = "123456789*0#"
    }
}
