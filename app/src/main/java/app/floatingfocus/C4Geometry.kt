package app.floatingfocus

data class C4Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun contains(px: Float, py: Float) = px >= x && py >= y && px < x + width && py < y + height
}

/** One geometry for drawing, Compose layout and the host's public touch region. Coordinates are dp. */
class C4Geometry(val width: Float, val height: Float) {
    // Retain the v1 body and upper LCD/lower keypad structure. Only actual screen
    // constraints compress decoration; ordinary overlay preferences never enter here.
    val compact = height < 340
    private val bodyWidth = (width * .80f).coerceAtLeast(164f).coerceAtMost(width)
    val body = C4Rect(if (width * .80f >= 164f) width * .14f else (width - bodyWidth) / 2, 10f, bodyWidth, height - 18f)
    private val normalPanelHeight = C4Theme.KEY_HEIGHT * 4 + C4Theme.KEY_GAP * 3 + C4Theme.PANEL_PADDING * 2
    private val tight = body.height < C4Theme.LCD_HEIGHT + 10 + C4Theme.KEY_GAP + normalPanelHeight || body.width < 210
    val gap = if (tight) 4f else C4Theme.KEY_GAP
    val rowGap = gap
    val panelPadding = if (tight) 5f else C4Theme.PANEL_PADDING
    private val panelWidth = minOf(188f, body.width - 12)
    val keyWidth = (panelWidth - panelPadding * 2 - gap * 2) / 3
    val keyHeight = C4Theme.KEY_HEIGHT
    private val panelHeight = keyHeight * 4 + rowGap * 3 + panelPadding * 2
    private val displayHeight = if (tight) 50f else C4Theme.LCD_HEIGHT
    private val frameHeight = displayHeight + 10
    private val bodyBottom = body.y + body.height
    private val edge = ((body.height - frameHeight - gap - panelHeight) / 2).coerceIn(0f, 8f)
    private val frameX = body.x + body.width * (20.28f / 233.6f)
    private val frameY = minOf(43f, bodyBottom - edge - panelHeight - gap - frameHeight).coerceAtLeast(body.y + edge)
    val displayFrame = C4Rect(frameX, frameY, body.width * (204.72f / 233.6f), frameHeight)
    val display = C4Rect(displayFrame.x + 6, displayFrame.y + 5, displayFrame.width - 12, displayHeight)
    private val panelX = ((width - panelWidth) / 2 + 6).coerceIn(body.x + 6, maxOf(body.x + 6, body.x + body.width - panelWidth - 6))
    private val panelY = minOf(126f, bodyBottom - edge - panelHeight).coerceAtLeast(displayFrame.y + displayFrame.height + gap)
    val keyboardPanel = C4Rect(panelX, panelY, panelWidth, minOf(panelHeight, bodyBottom - panelY).coerceAtLeast(1f))
    val keypad = C4Rect(panelX + panelPadding, panelY + panelPadding,
        keyWidth * 3 + gap * 2, keyHeight * 4 + rowGap * 3)
    // Extremely short split-screen windows retain the existing keyboard scroll;
    // the full LCD remains visible, and content never draws outside the body.
    val keypadViewportHeight = (keyboardPanel.height - panelPadding * 2).coerceAtLeast(1f)
    fun key(index: Int) = C4Rect(keypad.x + index % 3 * (keyWidth + gap),
        keypad.y + index / 3 * (keyHeight + rowGap), keyWidth, keyHeight)
    companion object {
        val keys = "123456789*0#"
    }
}
