package app.floatingfocus

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

private val tutorialTitles = listOf("让时间，始终可见", "提醒，随时间逐渐增强", "按照你的习惯，自由设置", "让倒计时显示在其他应用上")
private val tutorialDescriptions = listOf(
    "在使用其他应用时，也能随时看到计时进度，让时间提醒不再被忽略。",
    "自由选择正计时或倒计时，让悬浮窗随进度改变颜色、大小与提醒强度。",
    "自定义悬浮窗外观与提醒方式，保存常用方案，还可以选择应用并同步开始计时。",
    "FF 需要悬浮窗权限，才能在使用其他应用时持续显示计时器。此权限不会用于读取你的屏幕内容。")

/** One PagerState drives gestures, buttons, dots and Back. No real timer, record or notification request. */
@Composable
internal fun OnboardingScreen(onComplete: () -> Unit, onExit: () -> Unit, onRequestPermission: () -> Boolean) {
    val pager = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val granted by rememberOverlayPermission()
    var requesting by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { resumed = true; requesting = false }
            if (event == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun go(page: Int) { if (!pager.isScrollInProgress) scope.launch { pager.animateScrollToPage(page) } }
    BackHandler {
        if (pager.currentPage > 0) go(pager.currentPage - 1) else onExit()
    }
    FocusTheme {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
          Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().fillMaxHeight().padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Floating Focus", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    if (pager.currentPage < 3) TextButton(onClick = { go(3) }, enabled = !pager.isScrollInProgress) { Text("跳过") }
                    else Spacer(Modifier.height(48.dp))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "使用教程，第 ${pager.currentPage + 1} 页，共 4 页" },
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    repeat(4) { index ->
                        val width by animateDpAsState(if (pager.currentPage == index) 26.dp else 8.dp, label = "tutorial progress")
                        Box(Modifier.width(width).height(8.dp).background(
                            if (pager.currentPage == index) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = .15f), CircleShape))
                    }
                }
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val wide = maxWidth > maxHeight * 1.25f
                        val artHeight = (maxHeight * .55f).coerceIn(140.dp, 330.dp)
                        if (wide) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            TutorialArt(page, resumed && pager.currentPage == page, granted, Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp))
                            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                                TutorialCopy(page, granted, error)
                            }
                        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            TutorialArt(page, resumed && pager.currentPage == page, granted,
                                Modifier.fillMaxWidth().height(artHeight))
                            Spacer(Modifier.height(24.dp))
                            TutorialCopy(page, granted, error)
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
                Button(onClick = {
                    if (pager.currentPage < 3) go(pager.currentPage + 1)
                    else if (granted) onComplete()
                    else {
                        requesting = true
                        if (!onRequestPermission()) {
                            requesting = false
                            error = "无法打开系统设置，请在系统应用设置中允许 FF 显示悬浮窗。"
                        }
                    }
                }, enabled = !pager.isScrollInProgress && !requesting,
                    shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(when (pager.currentPage) { 0 -> "开始了解"; 3 -> if (granted) "开始使用" else "授予权限"; else -> "下一步" })
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 60.dp), contentAlignment = Alignment.Center) {
                    if (pager.currentPage == 3 && !granted) TextButton(onClick = onComplete, enabled = !requesting) { Text("暂不授权") }
                    else if (pager.currentPage > 0) TextButton(onClick = { go(pager.currentPage - 1) }, enabled = !pager.isScrollInProgress) { Text("上一页") }
                }
            }
          }
        }
    }
}

@Composable
private fun TutorialCopy(page: Int, granted: Boolean, error: String?) {
    if (page == 3) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
            Text(if (granted) "已授权" else "必需权限", modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(12.dp))
    }
    Text(tutorialTitles[page], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(14.dp))
    Text(if (page == 3 && granted) "悬浮窗权限已开启，可以开始使用 FF。" else tutorialDescriptions[page],
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    if (page == 3 && !granted && error != null) Text(error, color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
}
