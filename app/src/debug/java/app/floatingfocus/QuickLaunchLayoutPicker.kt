package app.floatingfocus

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

@Composable
internal fun quickLaunchLayoutPicker(): QuickLaunchLayout {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("debug_quick_launch", Context.MODE_PRIVATE) }
    var layout by remember { mutableStateOf(QuickLaunchLayout.entries.firstOrNull {
        it.name == prefs.getString("layout", null)
    } ?: QuickLaunchLayout.LIST) }
    Text("快捷启动布局 · Debug", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(QuickLaunchLayout.LIST to "列表", QuickLaunchLayout.GRID to "双列网格", QuickLaunchLayout.STRIP to "横向快捷栏").forEach { (value, label) ->
            FilterChip(layout == value, onClick = {
                layout = value
                prefs.edit { putString("layout", value.name) }
            }, label = { Text(label) })
        }
    }
    Text(when (layout) {
        QuickLaunchLayout.LIST -> "名称完整、点按清楚，适合少量常用 App。"
        QuickLaunchLayout.GRID -> "同屏展示更多 App，适合多个常用目标。"
        QuickLaunchLayout.STRIP -> "节省纵向空间，左右滑动查看更多 App。"
    }, style = MaterialTheme.typography.bodySmall)
    return layout
}
