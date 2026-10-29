package app.floatingfocus

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun FocusOverlay(
    appearance: ReminderPresentation,
    interactive: Boolean,
    animationRequest: Int = 0,
    controlsEnabled: Boolean = true,
    onConfirm: () -> Unit = {},
    onExtend: ((Int) -> Unit)? = null,
    extensionRequest: Int = 0,
    extensionStrategy: ReminderStrategy = ReminderStrategy(),
    c4: C4Frame = C4Frame(),
    c4Expanded: Boolean = false,
    onC4Toggle: () -> Unit = {},
    onC4Key: (Char) -> Unit = {},
    paused: Boolean = false,
    onTogglePause: (() -> Unit)? = null,
    onStop: () -> Unit
) {
    if (c4.active && c4Expanded) {
        C4DevicePanel(c4, onC4Key, onC4Toggle)
        return
    }
    val finished = !c4.active && appearance.finished
    var expanded by remember(finished) { mutableStateOf(false) }
    var addingTime by remember(finished) { mutableStateOf(false) }
    var extraSeconds by remember(finished) { mutableIntStateOf(120) }
    LaunchedEffect(extensionRequest) {
        if (extensionRequest > 0 && finished && onExtend != null) addingTime = true
    }
    val size = appearance.size
    val showingControls = !c4.active && (expanded || finished || appearance.confirmationRequired)
    val manualPulse = remember { Animatable(0f) }
    LaunchedEffect(animationRequest, interactive, appearance.animationStrength) {
        manualPulse.snapTo(0f)
        if (animationRequest > 0 && interactive && ValueAnimator.areAnimatorsEnabled()) {
            repeat(2) {
                manualPulse.animateTo(ReminderPresenter.HIGHLIGHT_DEPTH * appearance.animationStrength, tween(250))
                manualPulse.animateTo(0f, tween(350))
            }
        }
    }
    val automaticHighlight = if (appearance.pulseDepth > 0f && interactive && ValueAnimator.areAnimatorsEnabled()) {
        val transition = rememberInfiniteTransition(label = "gentle reminder")
        val value by transition.animateFloat(0f, appearance.pulseDepth,
            infiniteRepeatable(tween(ReminderPolicy.BREATH_MILLIS / 2), RepeatMode.Reverse), label = "highlight")
        value
    } else 0f

    val vertical = appearance.shape == OverlayShape.VERTICAL_CAPSULE
    val background = ReminderPresenter.highlight(appearance.colorArgb, maxOf(automaticHighlight, manualPulse.value))
    val foreground = Color(ReminderPresenter.contentColor(background))
    val textStyle = TextStyle(color = foreground,
        shadow = if (foreground == Color.White) Shadow(Color.Black, blurRadius = 2f) else null)
    // The background padding belongs to the same tap target as the time. Native
    // hit testing rejects transparent corners; child controls consume their own taps.
    val toggleControls = Modifier.clickable(enabled = controlsEnabled,
        interactionSource = remember { MutableInteractionSource() }, indication = null) { if (c4.active) onC4Toggle() else expanded = !expanded }
    // One alpha layer for the whole window. No glyph backgrounds or independent opaque children.
    Surface(color = Color(background), shape = CircleShape,
        modifier = Modifier.padding(2.dp).graphicsLayer { this.alpha = appearance.opacity }) {
        Layout(content = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val header = toggleControls
                    .semantics { contentDescription = if (c4.active) "${appearance.title}，点击展开 C4，可拖动" else if (controlsEnabled)
                        "${appearance.title}，点击${if (expanded) "收起" else "展开"}操作，可拖动" else appearance.title }
                    .padding(4.dp)
                val title: @Composable () -> Unit = {
                    Icon(painterResource(R.drawable.ic_notification), contentDescription = null,
                        tint = foreground, modifier = Modifier.size(20.dp))
                    Text(appearance.title.removePrefix("◷").trim().let { if (finished) it.removePrefix("超时 ") else it }, style = textStyle,
                        fontSize = if (showingControls) 16.sp else 18.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
                        maxLines = 1, softWrap = false)
                }
                if (vertical) Column(header, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) { title() }
                else Row(header, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) { title() }
                if (!c4.active && appearance.confirmationRequired) {
                    Text("确认提醒", style = textStyle)
                    TextButton(onClick = onConfirm, enabled = controlsEnabled) { Text("已看到提醒", style = textStyle) }
                }
                if (finished && addingTime && onExtend != null) {
                    DurationWheels(extraSeconds, { extraSeconds = it }, color = foreground, compact = true)
                    val extensionError = extensionStrategy.validationError(extraSeconds * 1000L)
                    extensionError?.let { Text(it, style = textStyle, fontSize = 12.sp, modifier = Modifier.widthIn(max = 210.dp)) }
                    Row {
                        TextButton(onClick = { addingTime = false }, enabled = controlsEnabled) { Text("取消", style = textStyle) }
                        Button(onClick = { onExtend(extraSeconds); addingTime = false }, enabled = controlsEnabled && extensionError == null,
                            colors = ButtonDefaults.buttonColors(containerColor = foreground, contentColor = Color(background)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            Text("确认加时", style = textStyle.copy(color = Color(background), shadow = null), fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else if (finished) {
                    FlowRow(horizontalArrangement = Arrangement.Center) {
                        if (onExtend != null) TextButton(onClick = { extraSeconds = 120; addingTime = true }, enabled = controlsEnabled) { Text("加时", style = textStyle) }
                        TextButton(onClick = onStop, enabled = controlsEnabled) { Text("结束计时", style = textStyle) }
                    }
                } else if (expanded && !c4.active) {
                    FlowRow(horizontalArrangement = Arrangement.Center) {
                        if (onTogglePause != null) TextButton(onClick = onTogglePause, enabled = controlsEnabled) {
                            Text(if (paused) "继续计时" else "暂停计时", style = textStyle)
                        }
                        TextButton(onClick = onStop, enabled = controlsEnabled) { Text("结束计时", style = textStyle) }
                        TextButton(onClick = { expanded = false }) { Text("收起", style = textStyle) }
                    }
                }
            }
        }, modifier = if (c4.active && !c4Expanded) {
            toggleControls.c4CompactArt(appearance.shape, led = c4.led, defused = c4.phase == C4Phase.DEFUSED)
        } else toggleControls) { measurables, constraints ->
            // Measure at a stable base size; apply the current reminder scale exactly once.
            val body = measurables.single().measure(Constraints(maxWidth = 240.dp.roundToPx()))
            val frame = OverlayGeometry.frame(appearance.shape, body.width / density, body.height / density, size,
                constraints.maxWidth / density, constraints.maxHeight / density, expanded = showingControls)
            val width = constraints.constrainWidth((frame.width * density).roundToInt())
            val height = constraints.constrainHeight((frame.height * density).roundToInt())
            layout(width, height) {
                body.placeRelativeWithLayer((width - body.width) / 2, (height - body.height) / 2) {
                    scaleX = frame.scale
                    scaleY = frame.scale
                }
            }
        }
    }
}
