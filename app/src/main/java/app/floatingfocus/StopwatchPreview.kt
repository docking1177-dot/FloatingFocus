package app.floatingfocus

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/** Local editor state only. It owns no timer, service, store writes or records. */
internal class StopwatchPreviewState(private val audio: OvertimeReminderFeedback) {
    var cue by mutableStateOf<StopwatchCue?>(null)
        private set
    var feedback by mutableStateOf("")
        private set
    private var serial = 0L

    fun show(level: StopwatchLevel, rule: StopwatchRule, explicitEffect: OvertimeEffect?,
        permissions: StopwatchReminderPermissions) {
        audio.close()
        cue = StopwatchCue(++serial, level, rule.effects.filterTo(linkedSetOf()) { it.visual },
            rule.intervalSeconds * 1000L, rule.color.sample(),rule.color.mode)
        feedback = audio.preview(stopwatchPreviewAudio(explicitEffect), permissions)
    }

    fun clear() { cue = null; feedback = ""; audio.close() }
    fun finish(id: Long) { if (cue?.id == id) cue = null }
}

@Composable
internal fun rememberStopwatchPreview(): Pair<StopwatchPreviewState, (StopwatchLevel, StopwatchRule, OvertimeEffect?) -> Unit> {
    val context = LocalContext.current
    val audio = remember(context) { OvertimeReminderFeedback(context) }
    val state = remember(audio) { StopwatchPreviewState(audio) }
    val store = remember(context) { StopwatchReminderSettingsStore.get(context) }
    val permissions by store.permissions.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, audio) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { state.clear(); audio.close() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); state.clear(); audio.close() }
    }
    // Tightening permission stops resources. Relaxing permission never replays a cue.
    LaunchedEffect(permissions) { audio.restrict(permissions) }
    LaunchedEffect(state.cue?.id) {
        val id = state.cue?.id ?: return@LaunchedEffect
        delay(StopwatchColorTimeline.DURATION_MILLIS.toLong() + 50)
        state.finish(id)
    }
    val show: (StopwatchLevel, StopwatchRule, OvertimeEffect?) -> Unit = { level, rule, effect ->
        state.show(level, rule, effect, store.permissions.value)
    }
    return state to show
}
