package app.floatingfocus

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/** Release uses the baseline; it does not contain the Debug chooser or other candidates. */
@Composable
internal fun OvertimeHeader(readout: OvertimeReadout, style: TextStyle, modifier: Modifier) =
    OvertimeTimeHeader(readout, style, modifier)
