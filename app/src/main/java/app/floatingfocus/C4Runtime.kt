package app.floatingfocus

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*

/** Existing service owns one controller; UI never advances playback, input or deadline consumption. */
class C4Runtime(private val context: Context, private val timer: () -> Countdown?, private val now: () -> Long,
                private val paused: () -> Boolean, private val onFrame: (C4Frame) -> Unit,
                private val onDeadline: () -> Unit) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = C4SettingsStore.get(context)
    val enabledForSession = store.settings.value.enabled
    private val timeline = C4Timeline.parse(context.assets.open("c4_timeline.lrc").bufferedReader().use { it.readText() })
    private var input = C4Input()
    private var leg: Long? = null
    private var deadlineNotified = false
    private var explosionConsumed = false
    private var consumedEvent = -1
    private var closed = false
    private var lastFrame = C4Frame()
    private var audio: C4Audio? = null
    private val callback = Runnable { refresh() }
    init { scope.launch { store.settings.collect { refresh() } } }
    private fun remaining(): Long = timer()?.let { val moment = now(); it.remainingAt(moment) - it.overtimeAt(moment) } ?: Long.MAX_VALUE
    fun locked(): Boolean = !closed && enabledForSession && timer()?.mode == TimerMode.COUNTDOWN &&
        (input.defused || timeline.phase(remaining()) != C4Phase.OFF)

    private fun playback(): C4Playback? {
        if (closed || input.defused || !enabledForSession || !store.settings.value.sound || paused() || timer()?.mode != TimerMode.COUNTDOWN) return null
        val remaining = remaining()
        return when (timeline.phase(remaining)) {
            C4Phase.PLANTING, C4Phase.ARMED -> C4Playback("pre_explosion", C4Timeline.WINDOW_MILLIS - remaining)
            C4Phase.EXPLODING -> C4Playback("explosion", -remaining)
            else -> null
        }
    }

    fun refresh(notifyDeadline: Boolean = true) {
        if (closed) return
        handler.removeCallbacks(callback)
        val timer = timer() ?: return
        if (leg != timer.startedAt) {
            leg = timer.startedAt; input = C4Input(); deadlineNotified = false; explosionConsumed = false; consumedEvent = -1
            audio?.close()
            audio = if (enabledForSession && timer.mode == TimerMode.COUNTDOWN && C4Audio.available(context)) C4Audio(context, ::playback) else null
        }
        val remaining = remaining()
        val phase = if (!enabledForSession || timer.mode != TimerMode.COUNTDOWN) C4Phase.OFF
            else if (input.defused) C4Phase.DEFUSED else timeline.phase(remaining)
        val frame = C4Frame(phase, timeline.led(remaining) && !input.defused, remaining.coerceAtLeast(0), input.digits, input.error(remaining))
        if (frame != lastFrame) {
            if (phase != lastFrame.phase) Log.d(TAG, "C4 phase=$phase remaining=$remaining offset=${C4Timeline.WINDOW_MILLIS - remaining}")
            lastFrame = frame; onFrame(frame)
        }
        if (phase != C4Phase.OFF) {
            val index = timeline.events.indexOfLast { it.atMillis <= C4Timeline.WINDOW_MILLIS - remaining }
            if (index > consumedEvent) { consumedEvent = index
                Log.d(TAG, "C4 event=$index offset=${C4Timeline.WINDOW_MILLIS - remaining} led=${frame.led}") }
        }
        if (enabledForSession && timer.mode == TimerMode.COUNTDOWN && remaining <= 0 && !explosionConsumed && !input.defused) {
            explosionConsumed = true; Log.d(TAG, "C4 explosion consumed sound=${store.settings.value.sound}")
        }
        audio?.refresh()
        if (notifyDeadline && timer.mode == TimerMode.COUNTDOWN && remaining <= 0 && !paused() && !deadlineNotified && !input.defused) {
            deadlineNotified = true; onDeadline()
        }
        if (!input.defused && enabledForSession && timer.mode == TimerMode.COUNTDOWN && !paused()) {
            listOfNotNull(timeline.nextDelay(remaining), input.errorDelay(remaining)).minOrNull()
                ?.let { handler.postDelayed(callback, it.coerceAtLeast(1)) }
        }
    }
    fun completed() { deadlineNotified = true; refresh(notifyDeadline = false) }
    fun key(key: Char): C4KeyResult {
        if (!locked()) return C4KeyResult.IGNORED
        val result = input.key(key, remaining())
        Log.d(TAG, "C4 key result=$result length=${input.digits.length}")
        refresh()
        return result
    }
    override fun close() {
        if (closed) return
        closed = true; handler.removeCallbacksAndMessages(null); scope.cancel(); audio?.close(); audio = null
    }
    companion object { private const val TAG = "FloatingFocus" }
}
