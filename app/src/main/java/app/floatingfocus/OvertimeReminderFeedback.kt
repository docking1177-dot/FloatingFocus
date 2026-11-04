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

    fun play(cue: OvertimeCue) {
        if (OvertimeEffect.VIBRATION in cue.effects) {
            stopVibration()
            vibrating = ReminderVibration.pair(context, cue.level.strength)
            if (vibrating) handler.postDelayed(endVibration, 240)
        }
        if (OvertimeEffect.SOUND in cue.effects) playTone()
    }

    fun restrict(settings: OvertimeReminderSettings) {
        val audioAllowed = settings.enabled && settings.limit != OvertimeLimit.LIGHT
        if (!audioAllowed || OvertimeEffect.SOUND !in settings.effects) stopTone()
        if (!audioAllowed || OvertimeEffect.VIBRATION !in settings.effects) stopVibration()
    }

    private fun playTone() {
        stopTone()
        if (manager.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL ||
            manager.isStreamMute(AudioManager.STREAM_MUSIC) || manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            Log.d(TAG, "Overtime tone suppressed by system audio policy")
            return
        }
        try {
            focusRequested = true
            focused = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!focused) { Log.d(TAG, "Overtime tone skipped: no audio focus"); stopTone(); return }
            val player = ToneGenerator(AudioManager.STREAM_MUSIC, 20)
            tone = player
            val started = player.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            Log.d(TAG, "Overtime tone requested=$started duration=120ms; listening not verified")
            handler.postDelayed(endTone, 180)
        } catch (e: RuntimeException) {
            stopTone()
            Log.w(TAG, "Overtime tone unavailable; timer continues", e)
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
