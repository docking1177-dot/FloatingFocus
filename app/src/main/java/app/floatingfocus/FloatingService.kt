/* Notification/service pattern adapted from FloatingCountdownTimer FloatingService.kt.
 * Copyright (c) 2022 Thomas Berghuis, MIT; see licenses/FloatingCountdownTimer-MIT.txt.
 */
package app.floatingfocus

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SessionState(
    val active: Boolean = false,
    val remainingMillis: Long = 0,
    val progress: Float = 0f,
    val stage: ReminderStage = ReminderStage.QUIET,
    val error: String? = null
)

class FloatingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var countdown: Countdown? = null
    private var window: OverlayWindow? = null
    private var overlayState by mutableStateOf(SessionState())
    private var interactive by mutableStateOf(true)
    private var vibrated = false
    private var receiverRegistered = false
    private var watchingOps = false
    private var stopping = false
    private val appOps by lazy { getSystemService(AppOpsManager::class.java) }
    private val opsListener = AppOpsManager.OnOpChangedListener { op, pkg ->
        if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && pkg == packageName) {
            handler.post { if (!Settings.canDrawOverlays(this)) fail("悬浮窗权限已撤销，计时已结束。") }
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            interactive = getSystemService(PowerManager::class.java).isInteractive
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (stopping) return
            if (!Settings.canDrawOverlays(this@FloatingService)) {
                fail("悬浮窗权限已撤销，计时已结束。")
                return
            }
            val timer = countdown ?: return
            val now = SystemClock.elapsedRealtime()
            val remaining = timer.remainingAt(now)
            val progress = timer.progressAt(now)
            val stage = ReminderPolicy.stage(progress)
            val previousStage = overlayState.stage
            overlayState = SessionState(true, remaining, progress, stage)
            mutableSession.value = overlayState
            if (stage == ReminderStage.FINISHED && !vibrated) {
                vibrated = true
                vibrateOnce()
            }
            if (stage != previousStage) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
            // elapsedRealtime is authoritative; this callback only refreshes the display.
            handler.postDelayed(this, if (remaining == 0L) 2000L else (remaining % 1000L).let { if (it == 0L) 1000L else it })
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { finishSession(); return START_NOT_STICKY }
        if (countdown != null) return START_NOT_STICKY // one session and one overlay
        val duration = intent?.getLongExtra(EXTRA_DURATION, 0L) ?: 0L
        if (intent?.action != ACTION_START || duration !in 1..Countdown.MAX_DURATION_MILLIS) {
            finishSession(); return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            fail("请先允许 Floating Focus 显示悬浮窗。")
            return START_NOT_STICKY
        }
        countdown = Countdown(duration, SystemClock.elapsedRealtime())
        overlayState = SessionState(true, duration)
        mutableSession.value = overlayState
        interactive = getSystemService(PowerManager::class.java).isInteractive
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "悬浮计时", NotificationManager.IMPORTANCE_LOW).apply {
            description = "用户主动开启的悬浮倒计时；可随时结束"
            setSound(null, null)
            enableVibration(false)
        })
        try {
            // Started only by a visible activity's explicit button, never by a background receiver.
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIFICATION_ID, notification())
            val overlay = OverlayWindow(this) { fail("悬浮窗不可用，计时已结束。", it) }
            window = overlay
            overlay.show { FocusTheme { FocusOverlay(overlayState, interactive) { finishSession() } } }
            if (stopping) return START_NOT_STICKY
            ContextCompat.registerReceiver(this, screenReceiver,
                IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) },
                ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, opsListener)
            watchingOps = true
            handler.post(tick)
        } catch (e: RuntimeException) {
            fail("系统未允许启动悬浮计时，请返回主界面重试。", e)
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val finished = overlayState.stage == ReminderStage.FINISHED
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(if (finished) "时间到了 · 休息一下吧" else "Floating Focus · 正在计时")
            .setContentText(if (finished) "点击悬浮窗上的「知道了」结束" else "点击悬浮胶囊可展开操作")
            .setContentIntent(open).addAction(0, if (finished) "知道了" else "结束计时", stop)
            .setWhen(System.currentTimeMillis() + overlayState.remainingMillis)
            .setUsesChronometer(!finished).setChronometerCountDown(true)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun vibrateOnce() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
                else getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        } catch (e: RuntimeException) { Log.w(TAG, "Vibration unavailable", e) }
    }

    private fun fail(message: String, cause: RuntimeException? = null) {
        if (stopping) return
        if (cause != null) Log.w(TAG, message, cause)
        mutableSession.value = SessionState(error = message)
        finishSession(preserveError = true)
    }

    private fun finishSession(preserveError: Boolean = false) {
        if (stopping) return
        stopping = true
        cleanup()
        if (!preserveError) mutableSession.value = SessionState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        if (watchingOps) { appOps.stopWatchingMode(opsListener); watchingOps = false }
        if (receiverRegistered) { unregisterReceiver(screenReceiver); receiverRegistered = false }
        window?.close()
        window = null
        countdown = null
    }

    override fun onDestroy() {
        cleanup()
        if (mutableSession.value.active) mutableSession.value = SessionState()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window?.clampPosition()
    }

    override fun onTimeout(startId: Int, fgsType: Int) { finishSession() }

    companion object {
        private const val TAG = "FloatingFocus"
        private const val CHANNEL = "focus_timer"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "app.floatingfocus.START"
        private const val ACTION_STOP = "app.floatingfocus.STOP"
        private const val EXTRA_DURATION = "duration_millis"
        private val mutableSession = MutableStateFlow(SessionState())
        val session = mutableSession.asStateFlow()
        fun start(context: Context, durationMillis: Long) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, FloatingService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_DURATION, durationMillis))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Foreground start rejected", e)
                mutableSession.value = SessionState(error = "系统暂时无法启动计时，请保持主界面可见后重试。")
            }
        }
        fun stop(context: Context) {
            val serviceIntent = Intent(context, FloatingService::class.java)
            context.stopService(serviceIntent)
        }
    }
}
