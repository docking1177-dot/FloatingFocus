package app.floatingfocus

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs

data class C4Playback(val clip: String, val offsetMillis: Long)

/** Long local tracks. The monotonic timer remains authoritative; player completion never ends a timer. */
class C4Audio(context: Context, private val desired: () -> C4Playback?) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private var closed = false
    private var requested = false
    private var focused = false
    private var active: String? = null
    private val slots = mutableMapOf<String, Slot>()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
            focused = change == AudioManager.AUDIOFOCUS_GAIN
            if (!focused) silencePlayers() else refresh()
        }, Handler(Looper.getMainLooper())).build()
    private class Slot(val player: MediaPlayer, var ready: Boolean = false, var playing: Boolean = false,
                       var seeking: Boolean = false, var ended: Boolean = false, var failed: Boolean = false,
                       var seekCorrections: Int = 0)

    init {
        for (name in listOf("pre_explosion", "explosion")) {
            val player = MediaPlayer()
            val slot = Slot(player)
            slots[name] = slot
            try {
                player.setAudioAttributes(attributes)
                context.assets.openFd("c4/$name.m4a").use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                player.setVolume(.65f, .65f)
                player.setOnPreparedListener {
                    if (!closed) { slot.ready = true; Log.d(TAG, "C4 media ready=$name duration=${it.duration}"); refresh() }
                }
                player.setOnSeekCompleteListener {
                    slot.seeking = false
                    val next = desired()
                    if (!closed && !slot.failed && next?.clip == name && focused && permitted()) {
                        // Bound stale async seeks. Never chase each LED node with a stop/seek/start loop.
                        if (abs(it.currentPosition - next.offsetMillis) <= 250) start(slot, name, next)
                        else if (slot.seekCorrections++ < 2) {
                            slot.seeking = true; it.seekTo(next.offsetMillis, MediaPlayer.SEEK_CLOSEST)
                        } else {
                            slot.failed = true; abandon()
                            Log.w(TAG, "C4 media seek too late=$name; suppressing this clip, timer/input continue")
                        }
                    }
                }
                player.setOnCompletionListener {
                    slot.playing = false; slot.ended = true
                    if (active == name) { active = null; abandon() }
                    Log.d(TAG, "C4 media completed=$name")
                }
                player.setOnErrorListener { _, what, extra ->
                    slot.failed = true; slot.playing = false; slot.seeking = false
                    if (desired()?.clip == name) abandon()
                    Log.w(TAG, "C4 media failed=$name what=$what extra=$extra"); true
                }
                player.prepareAsync()
            } catch (e: Exception) {
                slot.failed = true
                Log.w(TAG, "C4 local media unavailable=$name; visual/input timeline continues")
            }
        }
    }

    private fun permitted() = manager.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
        !manager.isStreamMute(AudioManager.STREAM_MUSIC) && manager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0 &&
        notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL

    fun refresh() {
        if (closed) return
        val next = desired()
        if (next == null || !permitted()) { silencePlayers(); abandon(); return }
        val slot = slots[next.clip] ?: return
        if (slot.failed || slot.ended) return
        if (!requested) { requested = true; focused = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
        if (!focused || !slot.ready || slot.seeking) return
        try {
            if (slot.playing && active == next.clip) return
            silencePlayers()
            if (abs(slot.player.currentPosition - next.offsetMillis) < 50) start(slot, next.clip, next)
            else {
                slot.seeking = true; slot.seekCorrections = 0
                slot.player.seekTo(next.offsetMillis, MediaPlayer.SEEK_CLOSEST)
            }
        } catch (e: IllegalStateException) { slot.failed = true; abandon(); Log.w(TAG, "C4 media seek failed", e) }
    }

    private fun start(slot: Slot, name: String, next: C4Playback) {
        try {
            slot.player.start(); slot.playing = true; active = name
            Log.d(TAG, "C4 media start=$name offset=${slot.player.currentPosition} target=${next.offsetMillis}")
        } catch (e: IllegalStateException) { slot.failed = true; abandon(); Log.w(TAG, "C4 media start failed", e) }
    }

    private fun silencePlayers() {
        slots.values.forEach { slot -> if (slot.playing) {
            try { slot.player.pause() } catch (_: IllegalStateException) { slot.failed = true }
            slot.playing = false
        } }
        active = null
    }
    private fun abandon() { if (requested) manager.abandonAudioFocusRequest(focus); requested = false; focused = false }
    override fun close() {
        if (closed) return
        closed = true; silencePlayers(); abandon()
        slots.values.forEach { it.player.release() }; slots.clear()
    }
    companion object {
        private const val TAG = "FloatingFocus"
        fun available(context: Context) = try {
            val files = context.assets.list("c4").orEmpty().toSet()
            "pre_explosion.m4a" in files && "explosion.m4a" in files
        } catch (_: Exception) { false }
    }
}
