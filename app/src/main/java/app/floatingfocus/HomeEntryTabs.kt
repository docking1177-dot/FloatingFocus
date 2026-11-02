package app.floatingfocus

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Persist only a completed drag. Selection stays attached to the entry ID. */
@Composable
internal fun HomeEntryTabs(order: List<HomeEntry>, selected: HomeEntry, enabled: Boolean,
                           onSelect: (HomeEntry) -> Unit, onReorder: (List<HomeEntry>) -> Unit,
                           onReorderingChange: (Boolean) -> Unit = {}) {
    val latestOrder by rememberUpdatedState(order)
    val saveOrder by rememberUpdatedState(onReorder)
    val reportSorting by rememberUpdatedState(onReorderingChange)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var dragging by remember { mutableStateOf<HomeEntry?>(null) }
    var draft by remember { mutableStateOf<List<HomeEntry>?>(null) }
    var width by remember { mutableFloatStateOf(0f) }
    var pointerX by remember { mutableFloatStateOf(0f) }
    var grabOffsetX by remember { mutableFloatStateOf(0f) }
    val displayed = draft ?: order

    fun slot(x: Float): Int {
        val physical = (x / (width / order.size)).toInt().coerceIn(order.indices)
        return if (rtl) order.lastIndex - physical else physical
    }

    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().selectableGroup().onSizeChanged { width = it.width.toFloat() }
            .pointerInput(enabled, rtl) {
                if (enabled) awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (width <= 0f) return@awaitEachGesture
                    val startOrder = latestOrder
                    val entry = startOrder[slot(down.position.x)]
                    val offset = down.position.x - ((down.position.x / (width / order.size)).toInt() + .5f) * width / order.size
                    // Recognition may be delivered after the finger has moved.
                    // Identity always belongs to DOWN, not the recognition point.
                    val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    try {
                        dragging = entry
                        reportSorting(true)
                        grabOffsetX = offset
                        pointerX = press.position.x.coerceIn(0f, width)
                        draft = HomeEntries.move(startOrder, entry, slot(pointerX))
                        android.util.Log.d("FloatingFocus", "Home reorder begin=${entry.id}")
                        if (drag(press.id) { change ->
                            pointerX = (pointerX + change.positionChange().x).coerceIn(0f, width)
                            draft = HomeEntries.move(draft ?: startOrder, entry, slot(pointerX))
                            change.consume()
                        }) {
                            currentEvent.changes.forEach { if (it.changedToUp()) it.consume() }
                            draft?.takeIf { it != latestOrder }?.let { saveOrder(it) }
                        }
                    } finally {
                        dragging = null
                        draft = null
                        reportSorting(false)
                    }
                }
            }) {
            displayed.forEachIndexed { index, entry ->
                val physicalIndex = if (rtl) order.lastIndex - index else index
                val draggingThis = dragging == entry
                key(entry.id) {
                Surface(modifier = Modifier.weight(1f).zIndex(if (draggingThis) 1f else 0f)
                    .graphicsLayer {
                        translationX = if (draggingThis) pointerX - grabOffsetX - (physicalIndex + .5f) * width / order.size else 0f
                    }.padding(3.dp), shape = RoundedCornerShape(13.dp),
                    color = if (entry == selected || draggingThis) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (entry == selected || draggingThis) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    shadowElevation = if (draggingThis) 3.dp else 0.dp) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(entry == selected, enabled = enabled && dragging == null, role = Role.Tab,
                            onClick = { onSelect(entry) })
                        .semantics {
                            contentDescription = "首页入口 ${entry.label}"
                            customActions = if (!enabled || dragging != null) emptyList() else buildList {
                                if (index > 0) add(CustomAccessibilityAction("前移 ${entry.label}") {
                                    onReorder(HomeEntries.move(order, entry, index - 1)); true
                                })
                                if (index < order.lastIndex) add(CustomAccessibilityAction("后移 ${entry.label}") {
                                    onReorder(HomeEntries.move(order, entry, index + 1)); true
                                })
                            }
                        }.padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(entry.label, style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (entry == selected) FontWeight.Bold else FontWeight.Medium)
                    }
                }
                }
            }
        }
    }
}
