package app.floatingfocus

import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class OvertimeStyle(val label: String) {
    OVERTIME_FIRST("超时主显"), TOTAL_FIRST("累计主显"), SIDE_BY_SIDE("并列读数")
}

internal class OvertimeStyleStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("debug_overtime_style", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(OvertimeStyle.entries.firstOrNull {
        it.name == prefs.getString("style", null)
    } ?: OvertimeStyle.OVERTIME_FIRST)
    val style = current.asStateFlow()
    fun select(style: OvertimeStyle) { prefs.edit { putString("style", style.name) }; current.value = style }
    companion object {
        @Volatile private var instance: OvertimeStyleStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: OvertimeStyleStore(context.applicationContext).also { instance = it }
        }
    }
}

@Composable
internal fun OvertimeHeader(readout: OvertimeReadout, style: TextStyle, modifier: Modifier,
                           showStatus: Boolean = false, reserveStatus: Boolean = false) {
    val selected by OvertimeStyleStore.get(LocalContext.current).style.collectAsState()
    when (selected) {
        OvertimeStyle.OVERTIME_FIRST -> OvertimeTimeHeader(readout, style, modifier, showStatus, reserveStatus)
        OvertimeStyle.TOTAL_FIRST -> Column(modifier.semantics(mergeDescendants = true) {
            contentDescription = "本次累计 ${readout.totalText}，超时 ${readout.overtimeText}"
        }, horizontalAlignment = Alignment.CenterHorizontally) {
            OvertimeDigits(readout.totalText, style, 20.sp, FontWeight.SemiBold)
            OvertimeStatusDigits(readout, style, 12.sp, normalPrefix = "超时 +",
                showStatus = showStatus, reserveStatus = reserveStatus)
        }
        OvertimeStyle.SIDE_BY_SIDE -> FlowRow(modifier.semantics(mergeDescendants = true) {
            contentDescription = "超时 ${readout.overtimeText}，本次累计 ${readout.totalText}"
        }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("超时" to readout.overtimeText, "累计" to readout.totalText).forEach { (label, time) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (label == "超时" && showStatus) "已超时" else label, style = style, fontSize = 11.sp)
                    OvertimeDigits(time, style, 16.sp, FontWeight.Medium, prefix = if (label == "超时") "+" else "")
                }
            }
        }
    }
}
