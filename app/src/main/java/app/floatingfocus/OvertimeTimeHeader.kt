package app.floatingfocus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

/** Provisional production baseline: overtime first, session total second. No state or actions. */
@Composable
internal fun OvertimeTimeHeader(readout: OvertimeReadout, style: TextStyle, modifier: Modifier = Modifier,
                                showStatus: Boolean = false, reserveStatus: Boolean = false) {
    Column(modifier.semantics(mergeDescendants = true) {
        contentDescription = "超时 ${readout.overtimeText}，本次累计 ${readout.totalText}"
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        OvertimeStatusDigits(readout, style, 18.sp, FontWeight.Medium, "+", showStatus, reserveStatus)
        OvertimeDigits("累计 ${readout.totalText}", style, 12.sp, prefix = "累计 ")
    }
}

/** A separate fixed prefix slot keeps Chinese font fallback from moving the numerical baseline or controls. */
@Composable
internal fun OvertimeStatusDigits(readout: OvertimeReadout, style: TextStyle, size: TextUnit,
                                  weight: FontWeight = FontWeight.Normal, normalPrefix: String,
                                  showStatus: Boolean, reserveStatus: Boolean) {
    if (!reserveStatus) {
        OvertimeDigits(normalPrefix + formatUsageTime(readout.overtimeMillis), style, size, weight, prefix = normalPrefix)
        return
    }
    val labelStyle = style.copy(fontSize = size, fontFamily = FontFamily.Monospace, fontWeight = weight,
        textAlign = TextAlign.Center)
    val measurer = rememberTextMeasurer()
    val labels = listOf(normalPrefix, "已超时").map {
        measurer.measure(it, style = labelStyle, maxLines = 1, softWrap = false).size
    }
    val labelWidth = with(LocalDensity.current) { labels.maxOf { it.width }.toDp() }
    val labelHeight = with(LocalDensity.current) { labels.maxOf { it.height }.toDp() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(labelWidth).heightIn(min = labelHeight), contentAlignment = Alignment.Center) {
            Text(if (showStatus) "已超时" else normalPrefix, style = labelStyle, maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.width(2.dp))
        OvertimeDigits(formatUsageTime(readout.overtimeMillis), style, size, weight)
    }
}

/** Stable numerical slots across seconds, minutes and hours, without scaling or clipping text. */
@Composable
internal fun OvertimeDigits(text: String, style: TextStyle, size: TextUnit,
                            weight: FontWeight = FontWeight.Normal, prefix: String = "") {
    val digits = style.copy(fontSize = size, fontFamily = FontFamily.Monospace,
        fontWeight = weight, textAlign = TextAlign.Center)
    val measurer = rememberTextMeasurer()
    val width = with(LocalDensity.current) {
        measurer.measure(timeSlotSample(text), style = digits, maxLines = 1, softWrap = false).size.width.toDp()
    }
    // Longer values may grow naturally; never hide a real time value behind an ellipsis.
    Text(text, style = digits, modifier = Modifier.widthIn(min = width), maxLines = 1, softWrap = false)
}
