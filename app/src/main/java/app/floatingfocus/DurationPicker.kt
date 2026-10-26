package app.floatingfocus

import android.view.MotionEvent
import android.widget.NumberPicker
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** Shared native wheels, including inside the existing overlay (no dialog window required). */
@Composable
fun DurationWheels(seconds: Int, onChange: (Int) -> Unit, minSeconds: Int = 60, maxSeconds: Int = 10800,
                   color: Color = MaterialTheme.colorScheme.onSurface, compact: Boolean = false) {
    val minutes = seconds / 60
    Row(Modifier.widthIn(max = 300.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        Wheel(minutes, minSeconds / 60..maxSeconds / 60, "分", color, compact, Modifier.weight(1f)) {
            onChange((it * 60 + seconds % 60).coerceIn(minSeconds, maxSeconds))
        }
        Wheel(seconds % 60, (if (minutes == minSeconds / 60) minSeconds % 60 else 0)..(if (minutes == maxSeconds / 60) maxSeconds % 60 else 59), "秒", color, compact, Modifier.weight(1f)) {
            onChange((minutes * 60 + it).coerceIn(minSeconds, maxSeconds))
        }
    }
}

@Composable
private fun Wheel(value: Int, range: IntRange, label: String, color: Color, compact: Boolean, modifier: Modifier,
                  onChange: (Int) -> Unit) {
    val callback by rememberUpdatedState(onChange)
    val textSize = with(LocalDensity.current) { (if (compact) 18.sp else 22.sp).toPx() }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = color, style = MaterialTheme.typography.labelLarge)
        AndroidView(modifier = Modifier.fillMaxWidth().height(if (compact) 100.dp else 156.dp), factory = { context ->
            object : NumberPicker(context) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
                    return super.dispatchTouchEvent(event) // Native wheel retains click/accessibility handling.
                }
            }.apply {
                minValue = range.first; maxValue = range.last
                wrapSelectorWheel = false
                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                setFormatter { "%02d".format(it) }
                setOnValueChangedListener { _, _, next -> callback(next) }
            }
        }, update = {
            it.maxValue = maxOf(it.maxValue, range.last)
            it.minValue = range.first
            it.maxValue = range.last
            it.contentDescription = "$label 滚轮"
            it.textColor = color.toArgb()
            it.textSize = textSize
            it.selectionDividerHeight = it.resources.displayMetrics.density.toInt().coerceAtLeast(1)
            if (it.value != value) it.value = value
        })
    }
}

@Composable
fun DurationDialog(title: String, initialSeconds: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit,
                   minSeconds: Int = 60, maxSeconds: Int = 10800) {
    var draft by rememberSaveable { mutableIntStateOf(initialSeconds.coerceIn(minSeconds, maxSeconds)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            DurationWheels(draft, { draft = it }, minSeconds, maxSeconds)
            Text("${durationLabel(minSeconds)}—${durationLabel(maxSeconds)}", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(draft) }) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun DurationEntry(label: String, seconds: Int, onChange: (Int) -> Unit, enabled: Boolean = true,
                  minSeconds: Int = 60, maxSeconds: Int = 10800) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled) { choosing = true }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(durationLabel(seconds), color = MaterialTheme.colorScheme.primary)
        Text("  >", color = MaterialTheme.colorScheme.primary)
    }
    if (choosing) DurationDialog(label, seconds, { choosing = false }, { onChange(it); choosing = false }, minSeconds, maxSeconds)
}
