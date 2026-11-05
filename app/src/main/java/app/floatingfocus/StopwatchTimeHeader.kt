package app.floatingfocus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/** Reserve three hour positions before the first hour appears. Larger values are never clipped. */
@Composable
internal fun StopwatchTimeHeader(elapsed: Long,style: TextStyle,status: Boolean,reserveStatus: Boolean) {
    val digits=style.copy(fontSize=18.sp,fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Medium,textAlign=TextAlign.Center)
    val hours=(elapsed.coerceAtLeast(0)/3600000).toString().length.coerceAtLeast(3)
    val measurer=rememberTextMeasurer()
    val reserve=measurer.measure("0".repeat(hours)+":00:00",style=digits,maxLines=1,softWrap=false).size
    Column(horizontalAlignment=Alignment.CenterHorizontally) {
        Text(formatElapsed(elapsed),style=digits,maxLines=1,softWrap=false,
            onTextLayout={ stopwatchTextProbe(it.layoutInput.text.text,it.size.width,it.size.height,it.hasVisualOverflow) },
            modifier=Modifier.widthIn(min=with(LocalDensity.current) { reserve.width.toDp() }))
        if(reserveStatus) {
            val labelStyle=style.copy(fontSize=12.sp)
            val height=measurer.measure("已计时",style=labelStyle,maxLines=1).size.height
            Text(if(status) "已计时" else "",style=labelStyle,maxLines=1,
                modifier=Modifier.heightIn(min=with(LocalDensity.current) { height.toDp() }))
        }
    }
}
