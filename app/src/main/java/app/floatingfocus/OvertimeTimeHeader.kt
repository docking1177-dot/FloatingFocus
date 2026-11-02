package app.floatingfocus

import androidx.compose.foundation.layout.Column
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

/** Provisional production baseline: overtime first, session total second. No state or actions. */
@Composable
internal fun OvertimeTimeHeader(readout: OvertimeReadout, style: TextStyle, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {
        contentDescription = "超时 ${readout.overtimeText}，本次累计 ${readout.totalText}"
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        OvertimeDigits(readout.overtimeText, style, 18.sp, FontWeight.Medium, prefix = "+")
        OvertimeDigits("累计 ${readout.totalText}", style, 12.sp, prefix = "累计 ")
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
        measurer.measure(prefix + "00:00:00", style = digits, maxLines = 1, softWrap = false).size.width.toDp()
    }
    // Longer values may grow naturally; never hide a real time value behind an ellipsis.
    Text(text, style = digits, modifier = Modifier.widthIn(min = width), maxLines = 1, softWrap = false)
}
