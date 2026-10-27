package app.floatingfocus

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.Log

/** One replaceable bank of ORIGINAL clips. No game assets, loops, forced volume, or extra permissions. */
class C4Audio(context: Context, onReady: () -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private var focused = false
    private var requested = false
    private var stream = 0
    private var closed = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
            focused = change == AudioManager.AUDIOFOCUS_GAIN
            if (!focused) stopStream()
        }, handler).build()
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attributes).build()
    private val loaded = mutableSetOf<Int>()
    val ready: Boolean get() = loaded.size == 3
    private val samples: Map<C4Tone, Int>
    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (!closed && status == 0) { loaded.add(id); if (ready) onReady() }
        }
        // Replace the files/IDs here after licensing and duration validation.
        samples = mapOf(C4Tone.NORMAL to pool.load(context, R.raw.c4_beep, 1),
            C4Tone.FINAL to pool.load(context, R.raw.c4_final, 1),
            C4Tone.EXPLOSION to pool.load(context, R.raw.c4_explosion, 1))
    }
    fun prepare() {
        if (closed || requested) return
        requested = true
        focused = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    fun play(tone: C4Tone): Boolean {
        if (closed || !focused || manager.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            manager.isStreamMute(AudioManager.STREAM_MUSIC) || manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 ||
            notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return false
        val sample = samples.getValue(tone)
        if (sample !in loaded) { Log.d("FloatingFocus", "C4 audio not ready: $tone"); return false }
        stopStream()
        stream = pool.play(sample, .65f, .65f, 1, 0, 1f)
        if (tone == C4Tone.EXPLOSION) handler.postDelayed({ silence() }, 1100)
        return stream != 0
    }
    private fun stopStream() { if (stream != 0) pool.stop(stream); stream = 0 }
    fun silence() {
        handler.removeCallbacksAndMessages(null)
        stopStream()
        if (requested) manager.abandonAudioFocusRequest(focus)
        requested = false; focused = false
    }
    override fun close() {
        if (closed) return
        silence(); closed = true; loaded.clear(); pool.release()
    }
}
