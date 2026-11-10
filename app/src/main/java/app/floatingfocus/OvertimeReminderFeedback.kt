package app.floatingfocus

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Bounded one-shot resources owned by the existing timer service, never an alarm or new service. */
internal class OvertimeReminderFeedback(private val context: Context) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var tone: ToneGenerator? = null
    private var focused = false
    private var focusRequested = false
    private var vibrating = false
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) stopTone()
        }, handler).build()
    private val endTone = Runnable { stopTone() }
    private val endVibration = Runnable { vibrating = false }

    fun play(cue: OvertimeCue) = play(cue.effects,cue.level.strength)

    fun play(effects: Set<OvertimeEffect>, strength: Float = OvertimeLimit.STANDARD.strength) {
        if (OvertimeEffect.VIBRATION in effects) {
            stopVibration()
            vibrating = ReminderVibration.pair(context, strength)
            if (vibrating) handler.postDelayed(endVibration, 240)
        }
        if (OvertimeEffect.SOUND in effects) playTone()
    }

    /** User feedback reports a request or suppression; it cannot verify physical output. */
    fun preview(effects: Set<OvertimeEffect>, permissions: StopwatchReminderPermissions): String {
        val messages = mutableListOf<String>()
        if (OvertimeEffect.SOUND in effects) messages += if (!permissions.sound) "提示音未请求：总许可关闭" else playTone()
        if (OvertimeEffect.VIBRATION in effects) {
            if (!permissions.vibration) messages += "震动未请求：总许可关闭"
            else {
                stopVibration()
                vibrating = ReminderVibration.pair(context, OvertimeLimit.STANDARD.strength)
                if (vibrating) handler.postDelayed(endVibration, 240)
                messages += if (vibrating) "震动已请求，实际触感由系统决定" else ReminderVibration.lastDiagnostic
            }
        }
        return messages.joinToString("；")
    }

    fun restrict(settings: OvertimeReminderSettings) {
        val audioAllowed = settings.enabled && settings.limit != OvertimeLimit.LIGHT
        if (!audioAllowed || OvertimeEffect.SOUND !in settings.effects) stopTone()
        if (!audioAllowed || OvertimeEffect.VIBRATION !in settings.effects) stopVibration()
    }

    fun restrict(permissions: StopwatchReminderPermissions) {
        if(!permissions.sound) stopTone()
        if(!permissions.vibration) stopVibration()
    }

    private fun playTone(): String {
        stopTone()
        if (manager.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL ||
            manager.isStreamMute(AudioManager.STREAM_MUSIC) || manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            Log.d(TAG, "Overtime tone suppressed by system audio policy")
            return "提示音未请求：系统静音、勿扰或媒体音量限制"
        }
        try {
            focusRequested = true
            focused = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!focused) { Log.d(TAG, "Overtime tone skipped: no audio focus"); stopTone(); return "提示音未请求：无法获取音频焦点" }
            val player = ToneGenerator(AudioManager.STREAM_MUSIC, 20)
            tone = player
            val started = player.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            Log.d(TAG, "Overtime tone requested=$started duration=120ms; listening not verified")
            handler.postDelayed(endTone, 180)
            return if (started) "提示音已请求，实际声音由系统决定" else "提示音不可用：系统未接受请求"
        } catch (e: RuntimeException) {
            stopTone()
            Log.w(TAG, "Overtime tone unavailable; timer continues", e)
            return "提示音不可用"
        }
    }

    private fun stopTone() {
        handler.removeCallbacks(endTone)
        tone?.let { runCatching { it.stopTone() }; runCatching { it.release() } }
        tone = null
        focused = false
        if (focusRequested) { focusRequested = false; manager.abandonAudioFocusRequest(focus) }
    }

    private fun stopVibration() {
        handler.removeCallbacks(endVibration)
        if (vibrating) ReminderVibration.cancel(context)
        vibrating = false
    }

    override fun close() { stopTone(); stopVibration(); handler.removeCallbacksAndMessages(null) }
    companion object { private const val TAG = "FloatingFocus" }
}
