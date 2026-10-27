package app.floatingfocus

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject

/** Event deadlines come from the monotonic session clock; UI frames never advance this timeline. */
class C4Runtime(private val context: Context, private val timer: () -> Countdown?, private val now: () -> Long,
                private val paused: () -> Boolean, private val onFrame: (C4Frame) -> Unit,
                private val onDeadline: () -> Unit) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = C4SettingsStore.get(context)
    private val effect = C4EffectState(C4Timeline(readProfile(context)))
    private var audio: C4Audio? = null
    private var closed = false
    private var lastFrame = C4Frame()
    private var leg: Long? = null
    private var deadlineNotified = false
    private val callback = Runnable { refresh() }
    init { scope.launch { store.settings.collect { refresh() } } }
    fun refresh() {
        if (closed) return
        handler.removeCallbacks(callback)
        val timer = timer() ?: return
        if (leg != timer.startedAt) { leg = timer.startedAt; deadlineNotified = false }
        val settings = store.settings.value
        val remaining = timer.remainingAt(now())
        if (settings.enabled && settings.sound && timer.mode == TimerMode.COUNTDOWN && remaining > 0 && audio == null)
            audio = C4Audio(context) { refresh() }
        val sample = effect.update(timer.startedAt, timer.mode, remaining, settings.enabled, paused(),
            consumeCues = !settings.sound || audio?.ready == true)
        if (sample.frame != lastFrame) {
            if (sample.frame.active != lastFrame.active) Log.d("FloatingFocus", "C4 active=${sample.frame.active} remaining=$remaining")
            lastFrame = sample.frame; onFrame(sample.frame)
        }
        if (settings.enabled && settings.sound && timer.mode == TimerMode.COUNTDOWN && !paused() && remaining > 0) {
            // Preload before the 40s boundary. Acquire focus only while the effect is active.
            if (sample.frame.active) audio?.prepare()
        }
        if (!settings.enabled || !settings.sound || paused()) audio?.silence()
        sample.cue?.let { cue ->
            val played = settings.sound && audio?.play(cue.tone) == true
            Log.d("FloatingFocus", "C4 cue=${cue.index} tone=${cue.tone} remaining=$remaining led=${sample.frame.led} played=$played")
        }
        if (timer.mode == TimerMode.COUNTDOWN && remaining == 0L && !paused() && !deadlineNotified) {
            deadlineNotified = true; onDeadline()
        }
        sample.nextDelayMillis?.let { handler.postDelayed(callback, it.coerceAtLeast(1)) }
    }
    fun completed() {
        val settings = store.settings.value
        deadlineNotified = true
        val explosion = effect.complete(settings.enabled && settings.sound)
        handler.removeCallbacks(callback)
        if (lastFrame.active) Log.d("FloatingFocus", "C4 active=false remaining=0; restored standard overlay")
        onFrame(C4Frame()); lastFrame = C4Frame()
        if (explosion) {
            audio?.prepare()
            Log.d("FloatingFocus", "C4 explosion played=${audio?.play(C4Tone.EXPLOSION) == true}")
        } else audio?.silence()
    }
    override fun close() {
        if (closed) return
        closed = true; handler.removeCallbacksAndMessages(null); scope.cancel()
        effect.cancel(); audio?.close(); audio = null
    }
    companion object {
        private fun readProfile(context: Context): C4Profile = try {
            val row = JSONObject(context.assets.open("c4_profile.json").bufferedReader().use { it.readText() })
            val measured = row.optJSONArray("measuredRemainingMillis")
            C4Profile(finalMillis = row.getLong("finalMillis"), normalPulseMillis = row.getLong("normalPulseMillis"),
                finalPulseMillis = row.getLong("finalPulseMillis"), maxLateMillis = row.getLong("maxLateMillis"),
                baseIntervalMillis = row.getLong("baseIntervalMillis"), minimumIntervalMillis = row.getLong("minimumIntervalMillis"),
                intervalSlope = row.getDouble("intervalSlope"),
                measuredRemainingMillis = measured?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList())
        } catch (e: Exception) {
            Log.w("FloatingFocus", "Invalid C4 profile; using documented approximation", e); C4Profile()
        }
    }
}
