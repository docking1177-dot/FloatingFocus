package app.floatingfocus

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.roundToInt

object ReminderVibration {
    @Suppress("DEPRECATION")
    fun once(context: Context, intensity: Float? = null): Boolean {
        if (intensity != null && intensity <= 0f) return false
        return try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
                else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (!vibrator.hasVibrator()) return false
            val amplitude = intensity?.let { (it.coerceIn(0f, 1f) * 255).roundToInt().coerceAtLeast(1) }
                ?: VibrationEffect.DEFAULT_AMPLITUDE
            vibrator.vibrate(VibrationEffect.createOneShot(180, amplitude),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            true
        } catch (e: RuntimeException) { Log.w("FloatingFocus", "Vibration unavailable", e); false }
    }
}
