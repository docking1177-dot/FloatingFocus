package app.floatingfocus

import androidx.compose.ui.Modifier

@Suppress("UNUSED_PARAMETER")
internal fun stopwatchTextProbe(text: String,width: Int,height: Int,overflow: Boolean) = Unit

@Suppress("UNUSED_PARAMETER")
internal fun Modifier.overlayLayoutProbe(role: String, readout: OvertimeReadout? = null, stopwatchElapsed: Long? = null): Modifier = this
