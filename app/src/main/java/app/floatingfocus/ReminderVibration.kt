package app.floatingfocus

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationAttributes
import android.media.AudioManager
import android.util.Log

object ReminderVibration {
    var lastDiagnostic: String = ""
        private set

    private fun report(message: String) {
        lastDiagnostic = message
        Log.d("FloatingFocus", message)
    }

    fun once(context: Context, intensity: Float = 1f): Boolean = submit(context, intensity, paired = false)

    fun pair(context: Context, intensity: Float): Boolean = submit(context, intensity, paired = true)

    @Suppress("DEPRECATION")
    private fun vibrator(context: Context): Vibrator = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java).defaultVibrator
        else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    fun cancel(context: Context) { runCatching { vibrator(context).cancel() } }

    @Suppress("DEPRECATION")
    private fun submit(context: Context, intensity: Float, paired: Boolean): Boolean {
        val duration = ReminderVibrationPolicy.durationMillis(intensity)
        if (duration == 0L) { report("未提交震动：提醒强度为 0。"); return false }
        return try {
            // An alarm usage describes the timer, but must not override the user's silent mode.
            if (context.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_SILENT) {
                report("未提交震动：系统处于静音模式。")
                return false
            }
            val vibrator = vibrator(context)
            if (!vibrator.hasVibrator()) { report("未提交震动：系统未报告可用振动器。"); return false }
            val amplitude = ReminderVibrationPolicy.amplitude(intensity, vibrator.hasAmplitudeControl())
            val effect = if (paired) VibrationEffect.createWaveform(longArrayOf(0, 75, 90, 75),
                intArrayOf(0, amplitude, 0, amplitude), -1) else VibrationEffect.createOneShot(duration, amplitude)
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build())
            } else {
                vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
            // Submission is not proof of physical vibration: system policy/hardware has the final say.
            report("已提交 ALARM 震动：${if (paired) "两段75ms" else "${duration}ms"}，强度 $intensity；是否执行由系统决定，请确认实际触感。")
            true
        } catch (e: RuntimeException) {
            report("震动调用失败：${e.javaClass.simpleName}。")
            Log.w("FloatingFocus", "Vibration unavailable", e)
            false
        }
    }
}
