package app.floatingfocus

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Passive native UI illustrations. Share the presenter/geometry, never touch stored user settings. */
@Composable
internal fun TutorialArt(page: Int, active: Boolean, granted: Boolean, modifier: Modifier = Modifier) {
    val phase = if (active && ValueAnimator.areAnimatorsEnabled()) {
        val transition = rememberInfiniteTransition(label = "tutorial illustration")
        val animated = transition.animateFloat(.05f, .98f,
            infiniteRepeatable(tween(6500, easing = LinearEasing), RepeatMode.Reverse), label = "illustration progress")
        // Slow explanatory motion needs ~12 visible updates/s, not a whole art recomposition every frame.
        val value by remember(animated) { derivedStateOf { (animated.value * 80).roundToInt() / 80f } }
        value
    } else .3f
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.clip(RoundedCornerShape(32.dp)).background(colors.secondaryContainer.copy(alpha = .6f))
        .clearAndSetSemantics { contentDescription = when (page) {
            0 -> "功能示意：其他应用上方的倒计时"
            1 -> "功能示意：提醒随计时进度增强"
            2 -> "功能示意：悬浮窗样式、预设和快捷应用"
            else -> if (granted) "悬浮窗权限已由系统授予" else "系统权限开关示意，图示不可授权"
        } }, contentAlignment = Alignment.Center) {
        val illustrationScale = minOf(1f, maxWidth.value / 320f, maxHeight.value / 320f)
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(colors.primary.copy(alpha = .06f), size.minDimension * .45f, Offset(size.width*.13f, size.height*.15f))
            drawCircle(colors.primary.copy(alpha = .06f), size.minDimension * .35f, Offset(size.width*.95f, size.height*.90f))
        }
        // Measure passive art at one stable size, then fit it once, including short landscape viewports.
        Box(Modifier.requiredSize(320.dp).graphicsLayer { scaleX = illustrationScale; scaleY = illustrationScale },
            contentAlignment = Alignment.Center) {
          when (page) {
            0 -> {
                val phoneHeight = 275.dp
                Box(Modifier.width(phoneHeight*.53f).height(phoneHeight).clip(RoundedCornerShape(24.dp)).background(colors.surface)
                    .padding(8.dp)) {
                    Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(colors.primary.copy(alpha = .24f))) {
                        drawRoundRect(colors.primary.copy(alpha = .22f), Offset(size.width*.12f, size.height*.18f),
                            Size(size.width*.76f, size.height*.48f), CornerRadius(14.dp.toPx()))
                        val play = Path().apply {
                            moveTo(size.width*.43f, size.height*.34f); lineTo(size.width*.43f, size.height*.49f)
                            lineTo(size.width*.64f, size.height*.415f); close()
                        }
                        drawPath(play, colors.surface)
                        repeat(3) { i -> drawRoundRect(colors.surface.copy(alpha = .7f),
                            Offset(size.width*.12f, size.height*(.74f+i*.065f)), Size(size.width*(if (i == 0) .55f else .75f), size.height*.025f), CornerRadius(4f)) }
                        drawRoundRect(colors.onSurface.copy(alpha = .25f), Offset(size.width*.32f, size.height*.025f),
                            Size(size.width*.36f, size.height*.018f), CornerRadius(5f))
                    }
                }
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).graphicsLayer {
                    translationY = (-10f + 8f*phase) * density; alpha = .65f + phase*.35f
                }) { TutorialTimer(ReminderSettings(baseSize = .85f, color = OverlayColor.GREEN), .15f) }
            }
            1 -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
                TutorialTimer(ReminderSettings(baseSize = 1f, color = OverlayColor.GREEN, colorEnabled = true, sizeEnabled = true), phase)
                LinearProgressIndicator(progress = { phase }, modifier = Modifier.width(160.dp), color = colors.primary,
                    trackColor = colors.primary.copy(alpha = .1f))
                Text("计时效果示意", style = MaterialTheme.typography.labelMedium, color = colors.onSecondaryContainer)
            }
            2 -> Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.height(116.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TutorialTimer(ReminderSettings(baseSize = .75f, shape = OverlayShape.ROUNDED_RECTANGLE, color = OverlayColor.BLUE), .2f)
                        TutorialTimer(ReminderSettings(baseSize = .75f, shape = OverlayShape.CIRCLE, color = OverlayColor.GREEN, opacity = .7f + phase*.2f), .2f)
                        TutorialTimer(ReminderSettings(baseSize = .75f, shape = OverlayShape.VERTICAL_CAPSULE, color = OverlayColor.PURPLE), .2f)
                    }
                }
                Surface(shape = RoundedCornerShape(18.dp), color = colors.surface) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("我的预设", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("阅读 · 15 分钟", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = colors.primary)
                        }
                        HorizontalDivider()
                        Text("打开 App 并计时", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    }
                }
            }
            else -> Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Canvas(Modifier.size(48.dp)) {
                    if (granted) {
                        drawCircle(colors.primary)
                        val check = Path().apply { moveTo(size.width*.27f, size.height*.51f); lineTo(size.width*.43f, size.height*.67f); lineTo(size.width*.75f, size.height*.34f) }
                        drawPath(check, colors.onPrimary, style = Stroke(3.dp.toPx()))
                    } else {
                        drawCircle(colors.primary.copy(alpha = .1f + phase*.1f))
                        drawCircle(colors.primary, size.minDimension*.22f, style = Stroke(3.dp.toPx()))
                    }
                }
                Surface(shape = RoundedCornerShape(20.dp), color = colors.surface) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(if (granted) "系统已允许显示" else "系统设置示意", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(painterResource(R.drawable.ic_focus), null, tint = Color.Unspecified, modifier = Modifier.size(32.dp))
                            Text("Floating Focus", style = MaterialTheme.typography.titleSmall)
                        }
                        Text("显示在其他应用上层", style = MaterialTheme.typography.bodyMedium)
                        if (!granted) Canvas(Modifier.size(48.dp, 26.dp)) {
                            // Illustration only: no Switch semantics, touch target or grant mutation.
                            drawRoundRect(colors.primary.copy(alpha = .15f), cornerRadius = CornerRadius(size.height/2))
                            drawRoundRect(colors.primary.copy(alpha = .5f + phase*.5f), cornerRadius = CornerRadius(size.height/2), style = Stroke(2.dp.toPx()))
                            drawCircle(colors.primary, size.height*.31f, Offset(size.height*.5f, size.height*.5f))
                        }
                    }
                }
                if (!granted) Text("示意图 · 需在系统设置中开启", style = MaterialTheme.typography.labelSmall,
                    color = colors.onSecondaryContainer)
            }
          }
        }
    }
}

@Composable
private fun TutorialTimer(settings: ReminderSettings, progress: Float) {
    val remaining = ((1f-progress)*60_000).toLong()
    val appearance = ReminderPresenter.present(ReminderSnapshot(remaining, progress, durationMillis = 60_000), settings)
    val dims = OverlayGeometry.frame(settings.shape, 56f, 22f, settings.baseSize)
    val foreground = Color(ReminderPresenter.contentColor(appearance.colorArgb))
    Surface(shape = CircleShape, color = Color(appearance.colorArgb), modifier = Modifier.size(dims.width.dp, dims.height.dp)
        .graphicsLayer { val scale = appearance.size/settings.baseSize; scaleX = scale; scaleY = scale; alpha = appearance.opacity }) {
        if (settings.shape == OverlayShape.VERTICAL_CAPSULE) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(painterResource(R.drawable.ic_notification), null, tint = foreground, modifier = Modifier.size(18.dp))
            Text(formatRemaining(remaining), color = foreground, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 16.sp)
        } else Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            Icon(painterResource(R.drawable.ic_notification), null, tint = foreground, modifier = Modifier.size(18.dp))
            Text(formatRemaining(remaining), color = foreground, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 18.sp)
        }
    }
}
