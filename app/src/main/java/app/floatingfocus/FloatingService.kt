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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
    val error: String? = null,
    val automatic: Boolean = false,
    val paused: Boolean = false
)

class FloatingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var countdown: Countdown? = null
    private var monitor: UsageAppMonitor? = null
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
        if (op == AppOpsManager.OPSTR_GET_USAGE_STATS && pkg == packageName) {
            handler.post { if (monitor != null && !AutoTriggerConfig.hasUsageAccess(this)) fail("使用情况访问权限已撤销，监测已停止。") }
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            interactive = getSystemService(PowerManager::class.java).isInteractive
            monitor?.screenChanged()
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
                ReminderVibration.once(this@FloatingService)
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
        if (countdown != null || monitor != null) return START_NOT_STICKY // modes never replace an active session
        if (intent?.action == ACTION_MONITOR) return startMonitoring()
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
            showOverlay()
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

    private fun showOverlay() {
        if (window != null || stopping) return
        val overlay = OverlayWindow(this) { fail("悬浮窗不可用，计时已结束。", it) }
        window = overlay
        overlay.show { FocusTheme {
            FocusOverlay(ReminderPresenter.present(ReminderSnapshot(overlayState.remainingMillis, overlayState.progress)),
                interactive) { finishSession() }
        } }
    }

    private fun startMonitoring(): Int {
        if (!Settings.canDrawOverlays(this) || !AutoTriggerConfig.hasUsageAccess(this)) {
            fail("请先允许悬浮窗和使用情况访问，再主动开启监测。")
            return START_NOT_STICKY
        }
        val config = AutoTriggerConfig.read(this)
        if (config.packages.isEmpty() || packageName in config.packages) {
            fail("请先选择需要监控的 App。")
            return START_NOT_STICKY
        }
        mutableMonitor.value = AutoMonitorStatus(enabled = true, reason = "等待目标 App", remainingMillis = config.durationMillis)
        overlayState = SessionState(remainingMillis = config.durationMillis, automatic = true, paused = true)
        mutableSession.value = overlayState
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "悬浮计时", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null); enableVibration(false)
                })
            // Only called from the visible settings activity's explicit enable action.
            // Detection NEVER starts another service from the background (Android 12–16).
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification())
            monitor = UsageAppMonitor(this, config, onUpdate = { snapshot, status, started ->
                if (!stopping) {
                    val old = mutableMonitor.value
                    val previousStage = overlayState.stage
                    mutableMonitor.value = status
                    overlayState = SessionState(started, snapshot.remainingMillis, snapshot.progress, snapshot.stage,
                        automatic = true, paused = !status.running)
                    mutableSession.value = overlayState
                    if (status.running) {
                        showOverlay()
                        if (!stopping && snapshot.stage == ReminderStage.FINISHED && !vibrated) {
                            vibrated = true
                            ReminderVibration.once(this)
                            AutoTriggerLog.record(this, "TIMER_FINISHED vibration=once")
                        }
                    } else { window?.close(); window = null }
                    if (!stopping && (old.running != status.running || old.reason != status.reason || previousStage != snapshot.stage)) {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                    }
                }
            }, onFailure = { fail(it) })
            ContextCompat.registerReceiver(this, screenReceiver,
                IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) },
                ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, opsListener)
            appOps.startWatchingMode(AppOpsManager.OPSTR_GET_USAGE_STATS, packageName, opsListener)
            watchingOps = true
            monitor?.start()
        } catch (e: RuntimeException) { fail("系统未允许启动监测，请保持设置页面可见后重试。", e) }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val automatic = mutableMonitor.value.enabled
        val open = PendingIntent.getActivity(this, 0, Intent(this, if (automatic) AutoTriggerActivity::class.java else MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val finished = overlayState.stage == ReminderStage.FINISHED
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(if (automatic) "指定 App 监测 · ${if (mutableMonitor.value.running) "计时中" else "已暂停/等待"}" else if (finished) "时间到了 · 休息一下吧" else "Floating Focus · 正在计时")
            .setContentText(if (automatic) "${mutableMonitor.value.reason} · 点击管理；可随时停止" else if (finished) "点击悬浮窗上的「知道了」结束" else "点击悬浮胶囊可展开操作")
            .setContentIntent(open).addAction(0, if (automatic) "停止监测" else if (finished) "知道了" else "结束计时", stop)
            .setWhen(System.currentTimeMillis() + overlayState.remainingMillis)
            .setUsesChronometer(!finished && (!automatic || mutableMonitor.value.running)).setChronometerCountDown(true)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun fail(message: String, cause: RuntimeException? = null) {
        if (stopping) return
        if (cause != null) Log.w(TAG, message, cause)
        if (monitor != null) AutoTriggerLog.record(this, "MONITOR_ERROR $message")
        mutableSession.value = SessionState(error = message)
        finishSession(preserveError = true)
    }

    private fun finishSession(preserveError: Boolean = false) {
        if (stopping) return
        stopping = true
        cleanup()
        mutableMonitor.value = AutoMonitorStatus(reason = if (preserveError) mutableSession.value.error ?: "已停止" else "未开启")
        if (!preserveError) mutableSession.value = SessionState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        monitor?.close()
        monitor = null
        if (watchingOps) { appOps.stopWatchingMode(opsListener); watchingOps = false }
        if (receiverRegistered) { unregisterReceiver(screenReceiver); receiverRegistered = false }
        window?.close()
        window = null
        countdown = null
    }

    override fun onDestroy() {
        cleanup()
        if (mutableMonitor.value.enabled) mutableMonitor.value = AutoMonitorStatus(reason = "监测已停止，请手动重新开启")
        if (mutableSession.value.active || mutableSession.value.automatic) mutableSession.value = SessionState()
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
        private const val ACTION_MONITOR = "app.floatingfocus.MONITOR"
        private const val EXTRA_DURATION = "duration_millis"
        private val mutableSession = MutableStateFlow(SessionState())
        val session = mutableSession.asStateFlow()
        private val mutableMonitor = MutableStateFlow(AutoMonitorStatus())
        val monitoring = mutableMonitor.asStateFlow()
        fun startMonitoring(context: Context) {
            if (mutableSession.value.active || mutableMonitor.value.enabled) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, FloatingService::class.java).setAction(ACTION_MONITOR))
            } catch (e: RuntimeException) {
                mutableSession.value = SessionState(error = "系统暂时无法开启监测，请保持设置页面可见后重试。")
            }
        }
        fun start(context: Context, durationMillis: Long) {
            if (mutableMonitor.value.enabled) return
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
