package app.floatingfocus

import androidx.compose.runtime.Composable

@Composable
internal fun stopwatchColor(base: Int, cue: StopwatchCue?, interactive: Boolean): Int {
    val progress=reminderEffectProgress(cue?.id,interactive && cue?.colors?.isNotEmpty()==true,2f/3f,"color")
    return StopwatchColorTimeline.color(base, if(interactive) cue?.colors.orEmpty() else emptyList(),progress,
        cue?.colorMode ?: StopwatchColorMode.NONE)
}
