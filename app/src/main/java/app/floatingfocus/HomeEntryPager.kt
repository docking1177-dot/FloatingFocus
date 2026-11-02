package app.floatingfocus

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/** Page identity lives outside the pager; changing order cannot reinterpret an old numeric index. */
@Composable
internal fun ColumnScope.HomeEntryPager(
    order: List<HomeEntry>, selectedId: String, enabled: Boolean, states: SaveableStateHolder,
    onSelected: (String) -> Unit, onReorder: (List<HomeEntry>) -> Unit,
    betweenTabsAndPage: @Composable () -> Unit, content: @Composable (HomeEntry) -> Unit
) {
    key(order.map { it.id }) {
        val pager = rememberPagerState(initialPage = order.indexOf(HomeEntries.selected(order, selectedId)), pageCount = { order.size })
        val scope = rememberCoroutineScope()
        var sorting by remember { mutableStateOf(false) }
        val updateSelection by rememberUpdatedState(onSelected)
        LaunchedEffect(pager) {
            snapshotFlow { pager.currentPage }.collect { updateSelection(order[it].id) }
        }
        HomeEntryTabs(order, order[pager.currentPage], enabled = enabled && !pager.isScrollInProgress,
            onSelect = { entry -> scope.launch { pager.animateScrollToPage(order.indexOf(entry), animationSpec = tween(220)) } },
            onReorder = { changed -> onSelected(order[pager.currentPage].id); onReorder(changed) },
            onReorderingChange = { sorting = it })
        betweenTabsAndPage()
        HorizontalPager(pager, modifier = Modifier.fillMaxWidth().weight(1f),
            key = { order[it].id }, userScrollEnabled = enabled && !sorting) { page ->
            val entry = order[page]
            states.SaveableStateProvider(entry.id) { content(entry) }
        }
    }
}
