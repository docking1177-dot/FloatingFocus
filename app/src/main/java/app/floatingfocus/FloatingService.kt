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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
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
    val paused: Boolean = false,
    val mode: TimerMode = TimerMode.COUNTDOWN,
    val elapsedMillis: Long = 0,
    val overtimeMillis: Long = 0
)

class FloatingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var countdown: Countdown? = null
    private val recordStore by lazy { TimerRecordStore.get(this) }
    private var record: TimerRecord? = null
    private var recordStartedAt = 0L
    private var awaitingApp: String? = null

    private fun beginRecord(appName: String = "手动计时", appPackage: String = "",
                            wall: Long = System.currentTimeMillis(), elapsed: Long = SystemClock.elapsedRealtime()) {
        val timer = countdown ?: return
        if (record != null) return
        recordStartedAt = elapsed
        record = TimerRecord(java.util.UUID.randomUUID().toString(), appName, appPackage, timer.mode, timer.durationMillis, wall)
        recordStore.save(record!!)
    }

    private fun completeRecord(status: RecordStatus, after: (() -> Unit)? = null) {
        val ended = record?.checkpoint(recordStartedAt, SystemClock.elapsedRealtime())?.copy(status = status)
        record = null
        if (ended != null) recordStore.save(ended, after = after) else after?.invoke()
    }
    private var monitor: UsageAppMonitor? = null
    private var window: OverlayWindow? = null
    private var overlayState by mutableStateOf(SessionState())
    private var interactive by mutableStateOf(true)
    private val completionReminder = CompletionReminder()
    private val settingsStore by lazy { ReminderSettingsStore.get(this) }
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
            overlayState = SessionState(true, remaining, progress, stage, mode = timer.mode,
                elapsedMillis = timer.elapsedAt(now), overtimeMillis = timer.overtimeAt(now))
            record = record?.checkpoint(recordStartedAt, now)
            record?.let { recordStore.save(it, checkpoint = true) }
            mutableSession.value = overlayState
            if (timer.mode == TimerMode.COUNTDOWN && completionReminder.consume(active = true, paused = false, remainingMillis = remaining)) {
                Log.d(TAG, "Timer completed: remaining=$remaining")
                ReminderVibration.once(this@FloatingService, settingsStore.settings.value.intensity)
            }
            if (stage != previousStage) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
            // elapsedRealtime is authoritative; this callback only refreshes the display.
            handler.postDelayed(this, 1000L - timer.elapsedAt(now) % 1000L)
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { finishSession(); return START_NOT_STICKY }
        if (intent?.action == ACTION_APP_OPENED) {
            val target = intent.getStringExtra("app_package")
            if (countdown != null && target != null && target == awaitingApp && !stopping) {
                beginRecord(intent.getStringExtra("app_name") ?: target, target,
                    intent.getLongExtra("opened_wall", System.currentTimeMillis()),
                    intent.getLongExtra("opened_elapsed", SystemClock.elapsedRealtime()))
                awaitingApp = null
            } else if (countdown == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MONITOR && !FeatureFlags.APP_USAGE_AUTO_TIMER) {
            // A stale internal intent must not stop an unrelated manual timer.
            if (countdown == null && monitor == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (countdown != null || monitor != null) return START_NOT_STICKY // modes never replace an active session
        if (intent?.action == ACTION_MONITOR) return startMonitoring()
        val duration = intent?.getLongExtra(EXTRA_DURATION, 0L) ?: 0L
        val mode = TimerMode.entries.firstOrNull { it.name == intent?.getStringExtra("mode") } ?: TimerMode.COUNTDOWN
        if (intent?.action != ACTION_START || (if (mode == TimerMode.STOPWATCH) duration != 0L else duration !in 1..Countdown.MAX_DURATION_MILLIS)) {
            finishSession(); return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            fail("请先允许 Floating Focus 显示悬浮窗。")
            return START_NOT_STICKY
        }
        countdown = Countdown(duration, SystemClock.elapsedRealtime(), mode)
        awaitingApp = intent.getStringExtra("app_package")
        completionReminder.reset()
        overlayState = SessionState(true, duration, mode = mode)
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
            if (awaitingApp == null) beginRecord()
            // Publish success only after the foreground service and overlay are ready.
            mutableSession.value = overlayState
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
            val settings by settingsStore.settings.collectAsState()
            val appearance = ReminderPresenter.present(ReminderSnapshot(overlayState.remainingMillis, overlayState.progress,
                overlayState.overtimeMillis, overlayState.mode, overlayState.elapsedMillis), settings)
            SideEffect { overlay.setGeometry(appearance.shape, appearance.size) }
            FocusOverlay(appearance,
                interactive) { finishSession() }
        } }
    }

    private fun startMonitoring(): Int {
        if (!FeatureFlags.APP_USAGE_AUTO_TIMER) { stopSelf(); return START_NOT_STICKY }
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
                        if (!stopping && completionReminder.consume(started, !status.running, snapshot.remainingMillis)) {
                            ReminderVibration.once(this, settingsStore.settings.value.intensity)
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
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(if (automatic) "指定 App 监测 · ${if (mutableMonitor.value.running) "计时中" else "已暂停/等待"}" else if (finished) "Floating Focus · 超时计时" else "Floating Focus · 正在计时")
            .setContentText(if (automatic) "${mutableMonitor.value.reason} · 点击管理；可随时停止" else if (finished) "正在记录超时时长，可随时结束" else "点击悬浮胶囊可展开操作")
            .setContentIntent(open).addAction(0, if (automatic) "停止监测" else if (finished) "结束计时" else "结束计时", stop)
            .setWhen(if (overlayState.mode == TimerMode.STOPWATCH || finished)
                System.currentTimeMillis() - (if (finished) overlayState.overtimeMillis else overlayState.elapsedMillis)
                else System.currentTimeMillis() + overlayState.remainingMillis)
            .setUsesChronometer(!automatic || mutableMonitor.value.running)
            .setChronometerCountDown(overlayState.mode == TimerMode.COUNTDOWN && !finished)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun fail(message: String, cause: RuntimeException? = null) {
        if (stopping) return
        if (cause != null) Log.w(TAG, message, cause)
        if (monitor != null) AutoTriggerLog.record(this, "MONITOR_ERROR $message")
        mutableSession.value = SessionState(error = message)
        finishSession(preserveError = true, status = RecordStatus.ERROR)
    }

    private fun finishSession(preserveError: Boolean = false, status: RecordStatus = RecordStatus.MANUAL) {
        if (stopping) return
        stopping = true
        cleanup()
        mutableMonitor.value = AutoMonitorStatus(reason = if (preserveError) mutableSession.value.error ?: "已停止" else "未开启")
        // Keep the service alive until the final atomic record is written.
        completeRecord(status) { Handler(Looper.getMainLooper()).post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            if (!preserveError) mutableSession.value = SessionState()
        } }
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        finishSession(status = RecordStatus.TASK_REMOVED)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        completeRecord(RecordStatus.SERVICE_STOPPED)
        cleanup()
        if (mutableMonitor.value.enabled) mutableMonitor.value = AutoMonitorStatus(reason = "监测已停止，请手动重新开启")
        if (mutableSession.value.active || mutableSession.value.automatic) mutableSession.value = SessionState()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window?.clampPosition()
    }

    override fun onTimeout(startId: Int, fgsType: Int) { finishSession(status = RecordStatus.ERROR) }

    companion object {
        private const val TAG = "FloatingFocus"
        private const val CHANNEL = "focus_timer"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "app.floatingfocus.START"
        private const val ACTION_APP_OPENED = "app.floatingfocus.APP_OPENED"
        private const val ACTION_STOP = "app.floatingfocus.STOP"
        private const val ACTION_MONITOR = "app.floatingfocus.MONITOR"
        private const val EXTRA_DURATION = "duration_millis"
        private val mutableSession = MutableStateFlow(SessionState())
        val session = mutableSession.asStateFlow()
        private val mutableMonitor = MutableStateFlow(AutoMonitorStatus())
        val monitoring = mutableMonitor.asStateFlow()
        fun startMonitoring(context: Context) {
            if (!FeatureFlags.APP_USAGE_AUTO_TIMER) return
            if (mutableSession.value.active || mutableMonitor.value.enabled) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, FloatingService::class.java).setAction(ACTION_MONITOR))
            } catch (e: RuntimeException) {
                mutableSession.value = SessionState(error = "系统暂时无法开启监测，请保持设置页面可见后重试。")
            }
        }
        fun start(context: Context, durationMillis: Long, mode: TimerMode = TimerMode.COUNTDOWN, targetPackage: String? = null): Boolean {
            if (mutableMonitor.value.enabled || mutableSession.value.active) return false
            mutableSession.value = SessionState()
            try {
                ContextCompat.startForegroundService(context, Intent(context, FloatingService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_DURATION, if (mode == TimerMode.STOPWATCH) 0L else durationMillis)
                    .putExtra("mode", mode.name).putExtra("app_package", targetPackage))
                return true
            } catch (e: RuntimeException) {
                Log.w(TAG, "Foreground start rejected", e)
                mutableSession.value = SessionState(error = "系统暂时无法启动计时，请保持主界面可见后重试。")
                return false
            }
        }
        fun appOpened(context: Context, app: LaunchableApp) {
            context.startService(Intent(context, FloatingService::class.java).setAction(ACTION_APP_OPENED)
                .putExtra("app_package", app.packageName).putExtra("app_name", app.label)
                .putExtra("opened_wall", System.currentTimeMillis()).putExtra("opened_elapsed", SystemClock.elapsedRealtime()))
        }
        fun stop(context: Context) {
            context.startService(Intent(context, FloatingService::class.java).setAction(ACTION_STOP))
        }
    }
}
