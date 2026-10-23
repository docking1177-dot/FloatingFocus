package app.floatingfocus

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Hosts only the isolated simulation; reuses the production window and renderer. */
class ReminderLabService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var window: OverlayWindow? = null
    private var interactive by mutableStateOf(true)
    private var watching = false
    private val ops by lazy { getSystemService(AppOpsManager::class.java) }
    private val listener = AppOpsManager.OnOpChangedListener { op, pkg ->
        if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && pkg == packageName) handler.post {
            if (!Settings.canDrawOverlays(this)) fail("悬浮权限已撤销，实验窗口已关闭。")
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!Settings.canDrawOverlays(this@ReminderLabService)) {
                fail("悬浮权限已撤销，实验窗口已关闭。")
                return
            }
            interactive = getSystemService(PowerManager::class.java).isInteractive
            ReminderLabState.simulation = ReminderLabState.simulation.tick(SystemClock.elapsedRealtime())
            handler.postDelayed(this, if (interactive) 100L else 1000L)
        }
    }
    override fun onBind(intent: Intent): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_SHOW) { close(); return START_NOT_STICKY }
        if (window != null) return START_NOT_STICKY
        if (!Settings.canDrawOverlays(this)) { fail("请先授权显示在其他应用上层。"); return START_NOT_STICKY }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "提醒实验室（Debug）", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null); enableVibration(false)
                })
            val open = PendingIntent.getActivity(this, 20, Intent(this, ReminderLabActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 21, Intent(this, ReminderLabService::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("提醒实验室 · 模拟中").setContentText("不影响正式计时；点击返回调参")
                .setContentIntent(open).addAction(0, "关闭实验悬浮窗", stop).setOngoing(true).setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(2, notification)
            val overlay = OverlayWindow(this) { fail("实验悬浮窗无法显示，请检查权限后重试。") }
            window = overlay
            overlay.show { FocusTheme {
                val settings by ReminderSettingsStore.get(this).settings.collectAsState()
                val lab = ReminderLabState.simulation
                val appearance = ReminderPresenter.present(lab.snapshot, settings.copy(style = lab.settings.style), lab.acknowledgedStage)
                SideEffect { overlay.setGeometry(appearance.shape, appearance.size) }
                FocusOverlay(appearance, interactive,
                    animationRequest = ReminderLabState.animationRequest,
                    onConfirm = { ReminderLabState.simulation = ReminderLabState.simulation.confirm() }, onStop = ::close)
            } }
            // show() can report a synchronous failure and clear window via close().
            if (window == null) return START_NOT_STICKY
            ReminderLabState.overlayVisible = true
            ReminderLabState.message = null
            ops.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, listener)
            watching = true
            handler.post(tick)
        } catch (_: RuntimeException) { fail("系统暂时无法显示实验窗口，请保持实验室可见后重试。") }
        return START_NOT_STICKY
    }
    private fun fail(message: String) { ReminderLabState.message = message; close() }
    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        if (watching) { ops.stopWatchingMode(listener); watching = false }
        window?.close()
        window = null
        ReminderLabState.overlayVisible = false
    }
    private fun close() { cleanup(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { cleanup(); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { close() }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); window?.clampPosition() }

    companion object {
        private const val CHANNEL = "reminder_lab"
        private const val ACTION_SHOW = "app.floatingfocus.lab.SHOW"
        fun show(context: Context) {
            try { ContextCompat.startForegroundService(context, Intent(context, ReminderLabService::class.java).setAction(ACTION_SHOW)) }
            catch (_: RuntimeException) { ReminderLabState.message = "实验窗口启动失败，请在页面可见时重试。" }
        }
        fun hide(context: Context) {
            val serviceIntent = Intent(context, ReminderLabService::class.java)
            context.stopService(serviceIntent)
        }
    }
}
