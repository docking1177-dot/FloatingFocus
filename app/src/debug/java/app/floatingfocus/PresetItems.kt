package app.floatingfocus

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Only this source set knows the comparison preference. Business actions stay in TimerPresetsCard. */
@Composable
internal fun PresetItems(presets: List<TimerPreset>, enabled: Boolean, onCreate: () -> Unit, onLaunch: (TimerPreset) -> Unit) {
    val style by PresetStyleStore.get(LocalContext.current).style.collectAsStateWithLifecycle()
    PresetComparisonItems(style, presets, enabled, onCreate, onLaunch)
}

@Composable
internal fun PresetComparisonItems(style: PresetStyle, presets: List<TimerPreset>, enabled: Boolean,
                                   onCreate: () -> Unit, onLaunch: (TimerPreset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("使用保存的计时与外观配置", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val items = presets + listOf<TimerPreset?>(null)
        if (style == PresetStyle.COMPACT_ROW) {
            items.forEachIndexed { index, preset ->
                key(preset?.id ?: "new-preset") {
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PresetOption(style, preset, enabled, Modifier.fillMaxWidth(), onCreate, onLaunch)
                }
            }
        } else items.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { preset ->
                    key(preset?.id ?: "new-preset") {
                        PresetOption(style, preset, enabled, Modifier.weight(1f).fillMaxHeight(), onCreate, onLaunch)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PresetOption(style: PresetStyle, preset: TimerPreset?, enabled: Boolean, modifier: Modifier,
                         onCreate: () -> Unit, onLaunch: (TimerPreset) -> Unit) {
    val row = style == PresetStyle.COMPACT_ROW
    val tile = style == PresetStyle.ICON_TILE
    val largeText = LocalDensity.current.fontScale >= 1.3f
    Surface(onClick = { if (preset == null) onCreate() else onLaunch(preset) }, enabled = enabled,
        shape = RoundedCornerShape(if (row) 8.dp else 14.dp),
        color = if (tile) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (tile) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        border = if (!row && !tile) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        modifier = modifier.heightIn(min = if (row) 64.dp else 96.dp).alpha(if (enabled) 1f else .38f)
            .semantics { contentDescription = if (preset == null) "新建预设" else "启动预设 ${preset.name}" }) {
        if (preset == null) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("+", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Text("新建预设", style = MaterialTheme.typography.bodyMedium)
            }
        } else when (style) {
            PresetStyle.ICON_CARD -> Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (largeText) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppIcon(preset.targetApp?.packageName.orEmpty(), 28.dp)
                    Text(preset.name, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppIcon(preset.targetApp?.packageName.orEmpty(), 28.dp)
                        Text(preset.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                    }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AppName(preset)
                    PresetTime(preset)
                }
            }
            PresetStyle.ICON_TILE -> Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppIcon(preset.targetApp?.packageName.orEmpty(), 36.dp)
                Text(preset.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center)
                Text("${preset.targetApp?.label ?: "选择 App"} · ${timeLabel(preset)}",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            PresetStyle.COMPACT_ROW -> Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(preset.targetApp?.packageName.orEmpty(), 32.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(preset.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    AppName(preset)
                }
                Text(timeLabel(preset), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
            }
        }
    }
}

private fun timeLabel(preset: TimerPreset) = if (preset.mode == TimerMode.STOPWATCH) "正计时" else durationLabel(preset.durationSeconds)

@Composable
private fun AppName(preset: TimerPreset) {
    Text(preset.targetApp?.label ?: "选择 App", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PresetTime(preset: TimerPreset) {
    Text(timeLabel(preset), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
}
