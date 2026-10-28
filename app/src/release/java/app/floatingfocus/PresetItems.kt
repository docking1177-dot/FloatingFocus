package app.floatingfocus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The existing production layout. No Debug style preference or experimental renderer. */
@Composable
internal fun PresetItems(presets: List<TimerPreset>, enabled: Boolean, onCreate: () -> Unit, onLaunch: (TimerPreset) -> Unit) {
        (presets + listOf<TimerPreset?>(null)).chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { preset ->
                    key(preset?.id ?: "new-preset") {
                        OutlinedButton(enabled = enabled, onClick = { if (preset == null) onCreate() else onLaunch(preset) },
                            shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(12.dp),
                            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 108.dp)
                                .semantics { contentDescription = if (preset == null) "新建预设" else "启动预设 ${preset.name}" }) {
                            if (preset == null) Column(horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("+", style = MaterialTheme.typography.headlineSmall)
                                Text("新建预设", style = MaterialTheme.typography.bodySmall)
                            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(preset.name, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (preset.targetApp?.label != preset.name) Text(preset.targetApp?.label ?: "选择 App",
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (preset.mode == TimerMode.STOPWATCH) "正计时" else durationLabel(preset.durationSeconds),
                                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("点击启动", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
}
