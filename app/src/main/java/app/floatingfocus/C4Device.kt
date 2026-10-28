package app.floatingfocus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Original native rendition of the supplied proportions; no photo, backing card or modal mask. */
@Composable
internal fun C4DevicePanel(frame: C4Frame, opacity: Float, preferredSize: Float, onKey: (Char) -> Unit, onToggle: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.padding(2.dp)) {
        val width = minOf(maxWidth, (292 * preferredSize).coerceAtLeast(180f).dp)
        val height = minOf(maxHeight, width * (360f / 292f))
        val geometry = C4Geometry(width.value, height.value)
        Box(Modifier.size(width, height).graphicsLayer { alpha = opacity }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                fun rect(x: Float, y: Float, rw: Float, rh: Float, color: Long, radius: Float = 5f) =
                    drawRoundRect(Color(color), Offset(x.dp.toPx(), y.dp.toPx()), Size(rw.dp.toPx(), rh.dp.toPx()), CornerRadius(radius.dp.toPx()))
                val body = geometry.body
                repeat(3) { i ->
                    val bw = (body.width - 8) / 3
                    rect(body.x + i * (bw + 4), body.y, bw, body.height, if (i % 2 == 0) 0xFFA18C65 else 0xFFB39C71, 7f)
                    repeat(5) { line -> rect(body.x + i * (bw + 4) + 4, body.y + 8 + line * body.height / 6,
                        bw - 8, 1f, 0x336A593D, 0f) }
                }
                rect(width.value * .04f, height.value * .15f, width.value * .14f, height.value * .18f, 0xFF91392E)
                fun wire(color: Long, inset: Float, bend: Float) {
                    val path = Path().apply { moveTo(w * .12f, h * .24f)
                        cubicTo(w * inset, h * -.12f, w * bend, h * .02f, w * .91f, h * .18f)
                        cubicTo(w * 1.02f, h * .48f, w * .96f, h * .80f, w * .74f, h * .70f) }
                    drawPath(path, Color(0xFF342E25), style = Stroke(5.dp.toPx()))
                    drawPath(path, Color(color), style = Stroke(3.dp.toPx()))
                }
                wire(0xFFC5483E, .10f, .56f); wire(0xFFD5B347, .55f, .94f)
                rect(body.x - 3, height.value * .32f, body.width + 6, if (geometry.compact) 14f else 26f, 0xFFB2B4AA, 1f)
                rect(body.x - 2, height.value * .88f, body.width + 4, 24f, 0xFFC3C4B9, 1f)
                val screen = geometry.display
                rect(screen.x - 6, screen.y - 5, screen.width + 12, screen.height + 10, 0xFF355647, 2f)
                val keyboard = geometry.keypad
                rect(keyboard.x - 8, keyboard.y - 8, keyboard.width + 16,
                    minOf(keyboard.height + 16, height.value - keyboard.y + 4), 0xFF222824, 3f)
                val lamp = Offset(w * .18f, (screen.y + screen.height / 2).dp.toPx())
                drawCircle(Color(0xFF30372B), 7.dp.toPx(), lamp)
                drawCircle(when { frame.phase == C4Phase.DEFUSED -> Color(0xFF9DDE6A)
                    frame.led -> Color(0xFFFF5946); else -> Color(0xFF6A211B) }, 4.5.dp.toPx(), lamp)
                if (frame.led) drawCircle(Color(0x44FF4934), 9.dp.toPx(), lamp)
            }
            val screen = geometry.display
            val displayColor = when { frame.phase == C4Phase.DEFUSED -> Color(0xFF9ED07A)
                frame.error -> Color(0xFFD56D57); frame.phase == C4Phase.EXPLODING -> Color(0xFF8E4839)
                else -> Color(0xFF555B31) }
            val displayInk = if (frame.phase == C4Phase.DEFUSED || frame.error) Color(0xFF14210E) else Color(0xFFEDF1CD)
            Column(Modifier.offset(screen.x.dp, screen.y.dp).size(screen.width.dp, screen.height.dp)
                .background(displayColor, RoundedCornerShape(2.dp)).semantics(mergeDescendants = true) {
                    contentDescription = "C4 ${frame.phase}，剩余 ${formatRemaining(frame.remainingMillis)}，已输入 ${frame.input.length} 位${if (frame.error) "，输入错误" else ""}"
                }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                val compact = screen.height < 30
                Text(formatRemaining(frame.remainingMillis), color = displayInk, fontFamily = FontFamily.Monospace,
                    fontSize = ((if (compact) 11f else 21f) / fontScale).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(frame.input.padEnd(7, '·'), color = displayInk, fontFamily = FontFamily.Monospace,
                    fontSize = ((if (compact) 10f else 16f) / fontScale).sp, maxLines = 1)
            }
            val panel = geometry.keyboardPanel
            Box(Modifier.offset(panel.x.dp, panel.y.dp).size(panel.width.dp, panel.height.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {})
            val grid = geometry.keypad
            Column(Modifier.offset(grid.x.dp, grid.y.dp).width(grid.width.dp)
                .height((height.value - grid.y - 4).coerceAtLeast(1f).dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(geometry.gap.dp)) {
                repeat(4) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(geometry.gap.dp)) {
                        repeat(3) { col ->
                            val char = C4Geometry.keys[row * 3 + col]
                            C4Key(char, frame.inputEnabled, geometry.keyWidth, geometry.keyHeight, onKey)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun C4Key(key: Char, enabled: Boolean, width: Float, height: Float, onKey: (Char) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val currentEnabled by rememberUpdatedState(enabled)
    val send by rememberUpdatedState(onKey)
    val fontScale = LocalDensity.current.fontScale
    Box(Modifier.size(width.dp, height.dp).clip(RoundedCornerShape(4.dp))

        .semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = when (key) { '*' -> "退格 *"; '#' -> "清空 #"; else -> "数字 $key" }
            if (!enabled) disabled() else onClick { send(key); true }
        }.pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val allowedAtDown = currentEnabled
                down.consume(); pressed = allowedAtDown
                val up = waitForUpOrCancellation()
                pressed = false
                if (up != null && allowedAtDown && currentEnabled) { up.consume(); send(key) }
            }
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(3.dp))
            .background(if (pressed) Color(0xFF8D997F) else if (enabled) Color(0xFFC3C5B2) else Color(0xFF777F70)),
            contentAlignment = Alignment.Center) {
        Text(key.toString(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            fontSize = (21 / fontScale).sp, color = Color(0xFF252D25))
        }
    }
}
