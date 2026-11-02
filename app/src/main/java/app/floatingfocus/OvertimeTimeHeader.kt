package app.floatingfocus

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Provisional production baseline: overtime first, session total second. No state or actions. */
@Composable
internal fun OvertimeTimeHeader(readout: OvertimeReadout, style: TextStyle, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {
        contentDescription = "超时 ${readout.overtimeText}，本次累计 ${readout.totalText}"
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(readout.overtimeText, style = style, fontSize = 18.sp,
            fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
        Text("累计 ${readout.totalText}", style = style, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
    }
}
