package app.floatingfocus

data class C4Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun contains(px: Float, py: Float) = px >= x && py >= y && px < x + width && py < y + height
}

/** One geometry for drawing, Compose layout and the host's public touch region. Coordinates are dp. */
class C4Geometry(val width: Float, val height: Float) {
    val compact = height < 340
    val display = C4Rect(width * .23f, if (compact) 4f else 48f,
        width * .66f, if (height < 240) 24f else if (compact) 40f else 58f)
    val gap = if (height < 240) 2f else if (compact) 8f else 10f
    val keyWidth = ((width * .64f - gap * 2) / 3).coerceAtLeast(44f)
    val keyHeight = if (compact) 44f else 46f
    val keypad = C4Rect(((width - keyWidth * 3 - gap * 2) / 2 + 6).coerceAtLeast(0f),
        (height - keyHeight * 4 - gap * 3 - 12).coerceAtLeast(display.y + display.height + 6),
        keyWidth * 3 + gap * 2, keyHeight * 4 + gap * 3)
    val keyboardPanel get() = C4Rect(keypad.x - 8, keypad.y - 8, keypad.width + 16,
        (height - keypad.y + 4).coerceAtLeast(0f))
    val body = C4Rect(width * .14f, 10f, width * .80f, height - 18f)
    fun key(index: Int) = C4Rect(keypad.x + index % 3 * (keyWidth + gap),
        keypad.y + index / 3 * (keyHeight + gap), keyWidth, keyHeight)
    companion object {
        val keys = "123456789*0#"
    }
}
