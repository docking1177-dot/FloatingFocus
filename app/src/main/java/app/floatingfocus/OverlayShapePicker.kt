package app.floatingfocus

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun OverlayShapePicker(current: OverlayShape, onChange: (OverlayShape) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OverlayShape.entries.forEach { shape ->
            val selected = current == shape
            val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Surface(modifier = Modifier.weight(1f).selectable(selected, role = Role.RadioButton) { onChange(shape) },
                shape = MaterialTheme.shapes.medium,
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(if (selected) 2.dp else 1.dp, color)) {
                Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.size(56.dp)) {
                        val w = when (shape) { OverlayShape.ROUNDED_RECTANGLE -> size.width * .9f; OverlayShape.VERTICAL_CAPSULE -> size.width * .36f; OverlayShape.CIRCLE -> size.width * .72f }
                        val h = when (shape) { OverlayShape.ROUNDED_RECTANGLE -> size.height * .36f; OverlayShape.VERTICAL_CAPSULE -> size.height * .9f; OverlayShape.CIRCLE -> size.height * .72f }
                        val origin = Offset((size.width - w) / 2, (size.height - h) / 2)
                        val radius = CornerRadius(minOf(w, h) / 2)
                        if (selected) drawRoundRect(color.copy(alpha = .2f), origin, Size(w, h), radius)
                        drawRoundRect(color, origin, Size(w, h), radius, style = Stroke(2.dp.toPx()))
                    }
                    Text(shape.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
