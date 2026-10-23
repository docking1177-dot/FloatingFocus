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
    if (layout == QuickLaunchLayout.STRIP) {
        Text("左右滑动查看更多 App", style = MaterialTheme.typography.bodySmall)
    }
    return layout
}
