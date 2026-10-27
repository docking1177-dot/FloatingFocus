package app.floatingfocus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeSection(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun HomeLink(label: String, expanded: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(if (expanded) "⌄" else ">", color = MaterialTheme.colorScheme.primary)
    }
}
