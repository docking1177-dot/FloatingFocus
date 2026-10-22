package app.floatingfocus

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AutoMonitorStatus(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val reason: String = "未开启",
    val foreground: String? = null,
    val remainingMillis: Long = 0,
    val detectionDelayMillis: Long? = null
)

/** Queries off the main thread; clock, window and lifecycle changes are serialized on main. */
class UsageAppMonitor(
    private val context: Context,
    private val config: AutoTriggerConfig,
    private val onUpdate: (ReminderSnapshot, AutoMonitorStatus, Boolean) -> Unit,
    private val onFailure: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = AutoTimerEngine(config.packages, config.durationMillis)
    private val startedAt = SystemClock.elapsedRealtime()
    private var cursor = UsageEventCursor(System.currentTimeMillis(), startedAt)
    private var lastObservation = startedAt
    private var blocked = false
    private var lockedAtWall = Long.MIN_VALUE
    private var generation = 0
    private var closed = false
    private var lastDelay: Long? = null

    private fun screenAvailable() = context.getSystemService(PowerManager::class.java).isInteractive &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
        context.getSystemService(UserManager::class.java).isUserUnlocked

    fun screenChanged() {
        if (closed) return
        val unavailable = !screenAvailable()
        if (unavailable == blocked) return
        blocked = unavailable
        generation++
        val now = SystemClock.elapsedRealtime()
        engine.suspend(now, if (blocked) "锁屏或息屏，已暂停" else "已解锁，等待新的前台事件")
        // On unlock only a small recent window, never the time spent locked.
        val wall = System.currentTimeMillis()
        if (blocked) lockedAtWall = wall
        val from = if (blocked) wall else UsageEventCursor.unlockFloor(wall, lockedAtWall)
        cursor = UsageEventCursor(from, now - (wall - from))
        lastObservation = now
        AutoTriggerLog.record(context, "SCREEN blocked=$blocked timer=PAUSED")
        publish(now)
    }

    fun start() {
        if (!FeatureFlags.APP_USAGE_AUTO_TIMER) return
        AutoTriggerLog.record(context, "MONITOR_START intervalMs=${config.intervalMillis} targetCount=${config.packages.size}")
        scope.launch {
            while (!closed) {
                if (!AutoTriggerConfig.hasUsageAccess(context)) { onFailure("使用情况访问权限已撤销，监测已停止。"); return@launch }
                if (!Settings.canDrawOverlays(context)) { onFailure("悬浮窗权限已撤销，监测已停止。"); return@launch }
                screenChanged()
                if (!blocked) {
                    val nowWall = System.currentTimeMillis()
                    val nowElapsed = SystemClock.elapsedRealtime()
                    val query = cursor.query(nowWall, nowElapsed)
                    if (query.discontinuity) {
                        engine.suspend(lastObservation, "事件查询中断或系统时间变化，请重新进入目标 App")
                        AutoTriggerLog.record(context, "QUERY_RESET timer=PAUSED; no gap charged")
                        publish(nowElapsed)
                    }
                    val token = generation
                    try {
                        val events = withContext(Dispatchers.IO) { readEvents(query) }
                        if (closed) return@launch
                        if (token == generation && screenAvailable()) {
                            if (events == null) {
                                engine.suspend(lastObservation, "系统暂未提供使用事件，请重新进入目标 App")
                                cursor = UsageEventCursor(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                                AutoTriggerLog.record(context, "QUERY_UNAVAILABLE timer=PAUSED")
                            } else {
                                for (event in cursor.fresh(events, query)) {
                                    val before = engine.timer.running
                                    val beforeApp = engine.foreground
                                    val delayMs = (System.currentTimeMillis() - event.timestamp).coerceAtLeast(0)
                                    val at = (nowElapsed - (nowWall - event.timestamp).coerceAtLeast(0)).coerceAtLeast(startedAt)
                                    engine.accept(event, at)
                                    if (event.kind == AppEventKind.RESUME && event.packageName in config.packages) {
                                        lastDelay = delayMs
                                        AutoTriggerLog.record(context, "TARGET_RESUME package=${event.packageName} delayMs=$delayMs")
                                    }
                                    if (before != engine.timer.running || beforeApp != engine.foreground) {
                                        AutoTriggerLog.record(context, "STATE ${if (engine.timer.running) "RUNNING" else "PAUSED"} reason=${engine.reason} remainingMs=${engine.timer.snapshot(at).remainingMillis}")
                                    }
                                }
                            }
                            lastObservation = nowElapsed
                            publish(SystemClock.elapsedRealtime())
                        } else screenChanged()
                    } catch (e: CancellationException) { throw e }
                    catch (_: RuntimeException) { onFailure("系统无法查询使用事件，监测已停止，请检查授权后重试。"); return@launch }
                }
                delay(config.intervalMillis)
            }
        }
    }

    private fun readEvents(query: UsageEventCursor.Query): List<AppUsageEvent>? {
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(query.from, query.until) ?: return null
        val result = mutableListOf<AppUsageEvent>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val kind = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> AppEventKind.RESUME
                UsageEvents.Event.ACTIVITY_PAUSED -> AppEventKind.PAUSE
                UsageEvents.Event.ACTIVITY_STOPPED -> AppEventKind.STOP
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> AppEventKind.CLEAR
                else -> continue
            }
            result += AppUsageEvent(event.timeStamp, event.packageName.orEmpty(), event.className.orEmpty(), kind)
        }
        return result
    }

    private fun publish(now: Long) {
        val snapshot = engine.timer.snapshot(now)
        onUpdate(snapshot, AutoMonitorStatus(true, engine.timer.running, engine.reason, engine.foreground,
            snapshot.remainingMillis, lastDelay), engine.timer.started)
    }

    fun close() {
        closed = true
        generation++
        scope.cancel()
        AutoTriggerLog.record(context, "MONITOR_STOP")
    }
}
