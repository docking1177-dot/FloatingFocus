package app.floatingfocus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** Native drawing plus a full-size, read-aloud list for precise selection on narrow charts. */
@Composable
internal fun HistoryChart(report: HistoryReport, nowMillis: Long, selected: Int?, onSelect: (Int?) -> Unit) {
    var showPeriods by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scale = remember(report.totals) { historyChartScale(report.totals.maxOrNull() ?: 0) }
    val ticks = listOf(1.0, .5, 0.0).map { fraction ->
        fraction to measurer.measure(scale.tick(fraction), style = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant))
    }
    val labels = report.period.buckets.map { measurer.measure(it.label,
        style = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)) }
    val gap = with(density) { 8.dp.toPx() }
    val left = ticks.maxOf { it.second.size.width }.toFloat() + gap
    val top = ticks.maxOf { it.second.size.height } / 2f + gap
    val labelHeight = labels.maxOf { it.size.height }.toFloat()
    val bottom = labelHeight + gap * 2
    var chartSize by remember { mutableStateOf(Size.Zero) }
    val currentReport by rememberUpdatedState(report)
    val currentScale by rememberUpdatedState(scale)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentNow by rememberUpdatedState(nowMillis)
    Text("计时时长（${scale.unitLabel}）", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    Box(Modifier.fillMaxWidth()) {
      Canvas(Modifier.fillMaxWidth().height(220.dp + 12.dp * density.fontScale)
        .onSizeChanged { chartSize = Size(it.width.toFloat(), it.height.toFloat()) }
        .semantics {
            contentDescription = "计时时长条形图，${report.period.range.label}，共 ${report.totals.size} 个时段，累计 ${historyDurationLabel(report.totalMillis)}"
            onClick(label = "查看各时段") { showPeriods = true; true }
        }
        .pointerInput(left, top, bottom, report.period) {
            detectTapGestures { point ->
                val plotWidth = chartSize.width - left - gap
                val plotBottom = chartSize.height - bottom
                val index = ((point.x - left) / (plotWidth / currentReport.totals.size)).toInt()
                if (point.x < left || point.x >= left + plotWidth || index !in currentReport.totals.indices || point.y < top) {
                    currentSelect(null)
                } else {
                    val total = currentReport.totals[index]
                    val height = ((total / currentScale.unitMillis / currentScale.top) * (plotBottom - top)).toFloat()
                    val touchTop = plotBottom - max(height, with(density) { 40.dp.toPx() })
                    currentSelect(if (point.y >= touchTop && currentReport.period.buckets[index].start <= currentNow) index else null)
                }
            }
        }) {
        val width = (size.width - left - gap).coerceAtLeast(1f)
        val floor = size.height - bottom
        val height = (floor - top).coerceAtLeast(1f)
        ticks.forEach { (fraction, text) ->
            val y = floor - (height * fraction).toFloat()
            drawLine(colors.outlineVariant, Offset(left, y), Offset(left + width, y), 1.dp.toPx())
            drawText(text, topLeft = Offset(left - gap - text.size.width, y - text.size.height / 2f))
        }
        val step = width / report.totals.size
        val barWidth = (step * .65f).coerceAtMost(28.dp.toPx())
        val stride = kotlin.math.ceil((labels.maxOf { it.size.width } + gap) / step).toInt().coerceAtLeast(1)
        report.period.buckets.forEachIndexed { index, bucket ->
            val x = left + step * (index + .5f)
            val future = bucket.start > nowMillis
            val barHeight = (report.totals[index] / scale.unitMillis / scale.top * height).toFloat()
            if (future) {
                drawRoundRect(colors.outlineVariant.copy(alpha = .5f), Offset(x - barWidth / 2, floor - 4.dp.toPx()),
                    Size(barWidth, 4.dp.toPx()), CornerRadius(2.dp.toPx()), style = Stroke(1.dp.toPx()))
            } else if (barHeight > 0) {
                drawRoundRect(if (index == selected) colors.primary else colors.primary.copy(alpha = .68f),
                    Offset(x - barWidth / 2, floor - max(barHeight, 1.dp.toPx())),
                    Size(barWidth, max(barHeight, 1.dp.toPx())), CornerRadius(3.dp.toPx()))
            }
            if (index == selected) drawLine(colors.onSurface, Offset(x - barWidth / 2, floor + 3.dp.toPx()),
                Offset(x + barWidth / 2, floor + 3.dp.toPx()), 2.dp.toPx())
            // Label density responds to text size; chart retains the whole week/month.
            if (index == labels.lastIndex || index % stride == 0 && index <= labels.lastIndex - stride)
                drawText(labels[index], topLeft = Offset(x - labels[index].size.width / 2f, floor + gap))
        }
      }
      if (selected != null && selected in report.totals.indices) {
        Surface(color = colors.secondaryContainer, shape = MaterialTheme.shapes.small,
            tonalElevation = 2.dp, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).widthIn(max = 210.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(report.period.buckets[selected].detail, style = MaterialTheme.typography.labelLarge)
                Text(if (report.period.buckets[selected].start > nowMillis) "尚未发生" else historyDurationLabel(report.totals[selected]),
                    style = MaterialTheme.typography.titleMedium)
            }
        }
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("空心标记：尚未发生", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(top = 12.dp))
        TextButton(onClick = { showPeriods = true }) { Text("查看时段") }
    }
    if (showPeriods) AlertDialog(onDismissRequest = { showPeriods = false }, title = { Text("选择时段") }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            itemsIndexed(report.period.buckets) { index, bucket ->
                TextButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = { onSelect(index); showPeriods = false }) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(bucket.detail)
                        Text(if (bucket.start > nowMillis) "尚未发生" else historyDurationLabel(report.totals[index]),
                            color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { showPeriods = false }) { Text("关闭") } })
}
