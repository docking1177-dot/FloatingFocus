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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    val overtimeMillis: Long = 0,
    val timerStartedAt: Long = 0,
    val c4Locked: Boolean = false,
    val c4Active: Boolean = false,
    val startRequestId: String? = null,
    val durationMillis: Long = 0,
    val sampledAt: Long = 0,
    /** Effective countdown setting captured at admission, including the ordinary pre-C4 stage. */
    val c4Enabled: Boolean = false,
    val overtimeReminder: OvertimeReminderInfo? = null,
    val screenDriven: Boolean = false,
    /** Session-only touch lock; never part of settings, presets or recovery checkpoints. */
    val overlayLocked: Boolean = false
)

class FloatingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var countdown: Countdown? = null
    private var manualClock = SessionClock()
    private var screenTracker: ScreenOnTracker? = null
    private var screenObserver: ScreenStateObserver? = null
    private var successAudio: C4Audio? = null
    private var successGeneration = 0L
    private var successPending = false
    private var successWindowClosed = false
    private var c4Runtime: C4Runtime? = null
    private var c4Expanded by mutableStateOf(false)
    private var c4Frame by mutableStateOf(C4Frame())
    private val recordStore by lazy { TimerRecordStore.get(this) }
    private val quotaStore by lazy { AppQuotaStore.get(this) }
    private var record: TimerRecord? = null
    private var recordStartedAt = 0L
    private var sessionPhysicalStart = 0L
    private var sessionWallStart = 0L
    private var recoverySource: TimerRecord? = null
    private var initializing = false
    private var awaitingApp: String? = null
    private var activeStartRequestId: String? = null
    private var activePresetId: String? = null
    private var extensionRequest by mutableIntStateOf(0)

    private fun beginRecord(appName: String = "手动计时", appPackage: String = "",
                            wall: Long = System.currentTimeMillis(), elapsed: Long = SystemClock.elapsedRealtime()) {
        val timer = countdown ?: return
        if (record != null) return
        recordStartedAt = if (timer.mode == TimerMode.STOPWATCH) sessionPhysicalStart else elapsed
        val activity = screenTracker?.snapshot(SystemClock.elapsedRealtime())
        val checkpoint = activity?.let { StopwatchCheckpoint(
            if (overlayState.screenDriven) StopwatchBehavior.SCREEN_ON else StopwatchBehavior.CONTINUOUS, it,
            carriedMillis = recoverySource?.displayElapsedMillis ?: 0,
            continuationOf = recoverySource?.id,
            rootId = recoverySource?.let { source -> source.stopwatch?.rootId ?: source.id },
            presetId = recoverySource?.stopwatch?.presetId ?: activePresetId,
            positions = window?.positionsSnapshot() ?: OverlayPositions(),stopwatchReminders = stopwatchRules) }
        record = TimerRecord(java.util.UUID.randomUUID().toString(), appName, appPackage, timer.mode, timer.durationMillis,
            if (activity != null) sessionWallStart else wall, elapsedMillis = activity?.activeMillis ?: 0, stopwatch = checkpoint)
        recordStore.save(record!!)
        if (appPackage.isNotBlank()) {
            quotaStore.begin(record!!.id, appPackage, elapsed, wall)
            quotaStore.checkpoint(record!!.id,!manualClock.paused,elapsed)
        }
    }

    private fun checkpointRecord(now: Long) {
        record?.takeIf { it.packageName.isNotBlank() }?.let { quotaStore.checkpoint(it.id, !manualClock.paused, now) }
        val screen = screenTracker?.snapshot(now)
        record = if (screen != null) record?.let { row -> row.copy(elapsedMillis = screen.activeMillis,
            stopwatch = row.stopwatch?.copy(activity=screen,positions=window?.positionsSnapshot() ?: row.stopwatch.positions)) }
            else record?.checkpoint(recordStartedAt, now)
        countdown?.let { timer -> record = record?.copy(plannedMillis = timer.totalPlannedMillis,
            savedOvertimeMillis = timer.totalOvertimeAt(manualClock.at(now))) }
    }

    private fun completeRecord(status: RecordStatus, after: (() -> Unit)? = null) {
        checkpointRecord(SystemClock.elapsedRealtime())
        record?.takeIf { it.packageName.isNotBlank() }?.let { quotaStore.checkpoint(it.id, false, SystemClock.elapsedRealtime(), finish = true) }
        val ended = record?.copy(status = status)
        record = null
        screenTracker = null
        if (ended != null) recordStore.save(ended, after = after) else after?.invoke()
    }
    private var monitor: UsageAppMonitor? = null
    private var window: OverlayWindow? = null
    private var overlayState by mutableStateOf(SessionState())
    private var interactive by mutableStateOf(true)
    private val completionReminder = CompletionReminder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val overtimeStore by lazy { OvertimeReminderSettingsStore.get(this) }
    private val overtimeEngine = OvertimeReminderEngine()
    private var overtimeFeedback: OvertimeReminderFeedback? = null
    private var overtimeCue by mutableStateOf<OvertimeCue?>(null)
    private var scheduledOvertimeAt: Long? = null
    private val clearOvertimeCue = Runnable { overtimeCue = null }
    private val overtimeDue = Runnable { scheduledOvertimeAt = null; refreshOvertime() }
    private val stopwatchStore by lazy { StopwatchReminderSettingsStore.get(this) }
    private var stopwatchRules = StopwatchReminderRules()
    private var stopwatchEngine: StopwatchReminderEngine? = null
    private var stopwatchFeedback: OvertimeReminderFeedback? = null
    private var stopwatchCue by mutableStateOf<StopwatchCue?>(null)
    private var scheduledStopwatchElapsed: Long? = null
    private val clearStopwatchCue = Runnable { stopwatchCue = null }
    private val stopwatchDue = Runnable { scheduledStopwatchElapsed = null; refreshStopwatchReminders() }
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
            overtimeCue = null // Do not replay an old visual cue when the screen becomes visible again.
            stopwatchCue = null
            if (overlayState.screenDriven) { screenObserver?.refresh(); return }
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
            val physicalNow = SystemClock.elapsedRealtime()
            val now = manualClock.at(physicalNow)
            val remaining = timer.remainingAt(now)
            val progress = timer.progressAt(now)
            val stage = settingsStore.settings.value.strategy.stage(ReminderSnapshot.at(timer, now))
            val previousStage = overlayState.stage
            overlayState = SessionState(true, remaining, progress, stage, mode = timer.mode,
                elapsedMillis = timer.elapsedAt(now), overtimeMillis = timer.overtimeAt(now), timerStartedAt = timer.startedAt,
                paused = manualClock.paused, c4Locked = c4Frame.requiresCode, c4Active = c4Frame.active, startRequestId = activeStartRequestId,
                durationMillis = timer.durationMillis, sampledAt = physicalNow,
                c4Enabled = overlayState.c4Enabled, overtimeReminder = overlayState.overtimeReminder,
                screenDriven = overlayState.screenDriven, overlayLocked = overlayState.overlayLocked)
            checkpointRecord(physicalNow)
            record?.let { recordStore.save(it, checkpoint = true) }
            if (!initializing) mutableSession.value = overlayState
            completeCountdown()
            c4Runtime?.refresh()
            refreshOvertime()
            refreshStopwatchReminders()
            if (stage != previousStage || (timer.mode == TimerMode.STOPWATCH && !initializing)) {
                Log.d(TAG, "Reminder stage: $previousStage -> $stage")
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
            // elapsedRealtime is authoritative; this callback only refreshes the display.
            if (!manualClock.paused) handler.postDelayed(this, 1000L - timer.elapsedAt(now) % 1000L)
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            overtimeStore.settings.collect { settings ->
                overtimeFeedback?.restrict(settings)
                overtimeCue = overtimeCue?.let { OvertimeFeedbackPolicy.filter(it, overtimeContext(), settings) }
                refreshOvertime()
            }
        }
        serviceScope.launch {
            stopwatchStore.permissions.collect { permission ->
                stopwatchFeedback?.restrict(permission)
                stopwatchCue=stopwatchCue?.allowed(permission)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OVERLAY_LOCK) {
            val id = intent.getStringExtra(EXTRA_REQUEST_ID)
            if (id != null && intent.hasExtra(EXTRA_OVERLAY_LOCKED)) {
                applyOverlayLock(OverlayLockRequest(id, intent.getLongExtra("timer_start", -1L),
                    intent.getBooleanExtra(EXTRA_OVERLAY_LOCKED, false)))
            }
            if (countdown == null && monitor == null && !successPending && !startPending) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_OVERTIME_SNOOZE) {
            val id = intent.getStringExtra(EXTRA_REQUEST_ID)
            if (id != null) snoozeOvertime(OvertimeSnoozeRequest(
                OvertimeKey(id, intent.getLongExtra("timer_start", -1L)), intent.getIntExtra("seconds", 0),
                intent.getStringExtra("operation_id").orEmpty()))
            if (countdown == null && monitor == null && !successPending && !startPending) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_WIDGET_CONTROL) {
            val command = intent.getStringExtra("widget_command")?.let { name -> WidgetCommand.entries.firstOrNull { it.name == name } }
            if (command != null && c4Runtime?.locked() != true && TimerWidgetModel.accepts(overlayState, command,
                    intent.getStringExtra(EXTRA_REQUEST_ID), intent.getLongExtra("timer_start", -1L))) {
                when (command) {
                    WidgetCommand.END -> finishSession()
                    WidgetCommand.PAUSE -> setPaused(overlayState.timerStartedAt, true)
                    WidgetCommand.RESUME -> setPaused(overlayState.timerStartedAt, false)
                    else -> Unit
                }
            }
            if (countdown == null && monitor == null && !successPending && !startPending) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_EXTENSION_EDITOR) {
            if (c4Runtime?.stageActive() == true) return START_NOT_STICKY
            val timer = countdown
            if (timer == null) { if (monitor == null) stopSelf(startId); return START_NOT_STICKY }
            if (!stopping && timer.mode == TimerMode.COUNTDOWN &&
                timer.startedAt == intent.getLongExtra("timer_start", -1L) && timer.remainingAt(manualClock.at(SystemClock.elapsedRealtime())) == 0L) {
                extensionRequest++
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            if (c4Runtime?.locked() != true && SessionControls.acceptsStop(overlayState,
                    intent.getStringExtra(EXTRA_REQUEST_ID), intent.getLongExtra("timer_start", -1L))) finishSession()
            else Log.d(TAG, "Stale or restricted normal stop rejected")
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_LAUNCH_FAILED) {
            val requested = intent.getStringExtra(EXTRA_REQUEST_ID)
            if (requested != null && requested == activeStartRequestId && awaitingApp != null && record == null)
                finishSession(status = RecordStatus.ERROR)
            else if (startAdmission.owns(requested)) {
                startAdmission.complete(requested)
                if (countdown == null && monitor == null) stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_APP_OPENED) {
            val target = intent.getStringExtra("app_package")
            if (countdown != null && target != null && target == awaitingApp && !stopping &&
                intent.getStringExtra(EXTRA_REQUEST_ID) == activeStartRequestId) {
                beginRecord(intent.getStringExtra("app_name") ?: target, target,
                    intent.getLongExtra("opened_wall", System.currentTimeMillis()),
                    intent.getLongExtra("opened_elapsed", SystemClock.elapsedRealtime()))
                awaitingApp = null
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MONITOR && !FeatureFlags.APP_USAGE_AUTO_TIMER) {
            // A stale internal intent must not stop an unrelated manual timer.
            if (countdown == null && monitor == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (countdown != null || monitor != null) return START_NOT_STICKY // modes never replace an active session
        if (intent?.action == ACTION_MONITOR) return startMonitoring()
        val requested = intent?.getStringExtra(EXTRA_REQUEST_ID)
        if (intent?.action != ACTION_START || !startAdmission.owns(requested)) {
            if (countdown == null && monitor == null && !startPending) stopSelf(startId)
            return START_NOT_STICKY
        }
        activeStartRequestId = requested
        val recoveryId=intent.getStringExtra(EXTRA_RECOVERY_ID)
        val source=recoveryId?.let(recordStore::recoverySource)
        if (recoveryId!=null && source==null) {
            fail("无法读取可续计的进度，记录可能已续计或读取失败，请重试。"); return START_NOT_STICKY
        }
        val mode = if (source!=null) TimerMode.STOPWATCH else TimerMode.entries.firstOrNull { it.name == intent.getStringExtra("mode") } ?: TimerMode.COUNTDOWN
        val screenDriven = mode == TimerMode.STOPWATCH && (source?.stopwatchBehavior?.let { it==StopwatchBehavior.SCREEN_ON }
            ?: intent.getBooleanExtra(EXTRA_SCREEN_DRIVEN, false))
        val duration = if (mode == TimerMode.STOPWATCH) 0L else intent.getLongExtra(EXTRA_DURATION, 0L)
        val sessionC4Enabled = intent.getBooleanExtra(EXTRA_C4_ENABLED, false) && mode == TimerMode.COUNTDOWN
        val reminders=when {
            mode!=TimerMode.STOPWATCH -> StopwatchReminderRules()
            source!=null -> source.stopwatch?.stopwatchReminders ?: StopwatchReminderRules()
            !intent.hasExtra(EXTRA_STOPWATCH_REMINDERS) -> StopwatchReminderRules()
            else -> StopwatchReminderSettingsStore.decodeSnapshot(intent.getStringExtra(EXTRA_STOPWATCH_REMINDERS).orEmpty())
        }
        if(reminders==null) { fail("正计时提醒配置无效，请检查后重试。");return START_NOT_STICKY }
        if (if (mode == TimerMode.STOPWATCH) duration != 0L else duration !in 1..Countdown.MAX_DURATION_MILLIS) {
            fail("计时时长无效，请重新选择。"); return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            fail("请先允许 Floating Focus 显示悬浮窗。")
            return START_NOT_STICKY
        }
        ReminderSettingsStore.get(this).settings.value.strategy.validationError(duration, mode)?.let {
            fail(it); return START_NOT_STICKY
        }
        // A new explicit session takes priority over the old result voice.
        cancelSuccessAudio()
        stopping = false
        initializing = true
        manualClock = SessionClock()
        val physicalStart = SystemClock.elapsedRealtime()
        val wallStart = System.currentTimeMillis()
        sessionPhysicalStart=physicalStart; sessionWallStart=wallStart; recoverySource=source
        screenTracker = if (mode == TimerMode.STOPWATCH) ScreenOnTracker(physicalStart).apply { update(physicalStart,!screenDriven) } else null
        if (screenDriven) manualClock.pause(physicalStart)
        c4Frame = C4Frame()
        countdown = Countdown(duration, manualClock.at(physicalStart), mode, elapsedBeforeMillis=source?.displayElapsedMillis ?: 0)
        stopwatchRules=reminders
        stopwatchEngine=if(mode==TimerMode.STOPWATCH) StopwatchReminderEngine(reminders,source?.displayElapsedMillis ?: 0) else null
        awaitingApp = if (source!=null) null else intent.getStringExtra("app_package")
        activePresetId = source?.stopwatch?.presetId ?: intent.getStringExtra("preset_id")?.takeIf { id ->
            TimerPresetStore.get(this).presets.value.any { it.id == id && it.targetApp?.packageName == awaitingApp }
        }
        completionReminder.reset()
        overlayState = SessionState(true, duration, mode = mode, timerStartedAt = countdown!!.startedAt, startRequestId = requested,
            elapsedMillis = source?.displayElapsedMillis ?: 0,
            durationMillis = countdown!!.durationMillis, sampledAt = SystemClock.elapsedRealtime(), c4Enabled = sessionC4Enabled,
            screenDriven = screenDriven, paused = manualClock.paused)
        interactive = getSystemService(PowerManager::class.java).isInteractive
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "悬浮计时", NotificationManager.IMPORTANCE_LOW).apply {
            description = "用户主动开启的悬浮计时"
            setSound(null, null)
            enableVibration(false)
        })
        try {
            // Started only by a visible activity's explicit button, never by a background receiver.
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIFICATION_ID, notification())
            Log.d(TAG, "C4 session enabled=$sessionC4Enabled mode=$mode preset=${activePresetId ?: "current"}")
            c4Runtime = if (sessionC4Enabled)
                C4Runtime(this, { countdown }, { manualClock.at(SystemClock.elapsedRealtime()) },
                    { manualClock.paused }, ::publishC4Frame, ::completeCountdown, enabledForSession = sessionC4Enabled)
            else null
            showOverlay()
            if (stopping) return START_NOT_STICKY
            ContextCompat.registerReceiver(this, screenReceiver,
                IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) },
                ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, opsListener)
            watchingOps = true
            val ready=TimerStartupGate.initialize(prepare={
                if (screenDriven) {
                    screenObserver=ScreenStateObserver(this,handler,::applyScreenState)
                    screenObserver!!.start()
                }
            },valid={ !stopping && Settings.canDrawOverlays(this) },commit={
                if (awaitingApp==null) {
                    if (source!=null) beginRecord(source.appName,source.packageName,wallStart,physicalStart)
                    else if (screenDriven) beginRecord(appName="亮屏计时",wall=wallStart,elapsed=physicalStart) else beginRecord()
                }
            })
            if (!ready) {
                if (!stopping) fail("悬浮窗权限已撤销，启动已取消。")
                return START_NOT_STICKY
            }
            // Publish success only after the foreground service and overlay are ready.
            initializing=false
            mutableSession.value = overlayState
            startAdmission.complete(requested)
            handler.removeCallbacks(tick)
            handler.post(tick)
        } catch (e: RuntimeException) {
            fail("系统未允许启动悬浮计时，请返回主界面重试。", e)
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (window != null || stopping) return
        val presetStore = TimerPresetStore.get(this)
        val presetId = activePresetId
        val preset=presetStore.presets.value.firstOrNull { it.id == presetId }
        val ordinary=OverlayPositionStore.get(this)
        val initial = if (preset!=null) preset.positions.copy(legacy=preset.positions.legacy ?: preset.lastPosition)
            else if (presetId!=null) recoverySource?.stopwatch?.positions ?: OverlayPositions()
            else ordinary.snapshot()
        val overlay = OverlayWindow(this,
            initialPositions = initial, onPositionsChanged = { positions ->
                if (presetId!=null) presetStore.rememberPositions(presetId,positions) else ordinary.save(positions)
                checkpointRecord(SystemClock.elapsedRealtime())
                record?.let { recordStore.save(it) }
            }) {
            fail("悬浮窗不可用，计时已结束。", it)
        }
        window = overlay
        overlay.show { FocusTheme {
            val settings by settingsStore.settings.collectAsState()
            val overtimeSettings by overtimeStore.settings.collectAsState()
            val normal = ReminderPresenter.present(ReminderSnapshot(overlayState.remainingMillis, overlayState.progress,
                overlayState.overtimeMillis, overlayState.mode, overlayState.elapsedMillis, countdown?.durationMillis ?: 0), settings)
            val appearance = if (c4Frame.active) ReminderPresenter.forC4(c4Frame, settings.shape)
                else if (manualClock.paused) normal.copy(pulseDepth = 0f, animationStrength = 0f) else normal
            SideEffect {
                overlay.setGeometry(appearance.shape, appearance.size, c4Frame.active && c4Expanded,
                    appearance.opacity, c4Frame.active, nativeOpacity = true)
                overlay.setTouchLocked(overlayState.overlayLocked)
            }
            val timerStart = overlayState.timerStartedAt
            val requestId = overlayState.startRequestId
            // Native alpha controls obscuring opacity; render alpha must not apply it a second time.
            FocusOverlay(appearance.copy(opacity = 1f), interactive, controlsEnabled = !overlayState.overlayLocked,
                overlayLocked=overlayState.overlayLocked,
                onLock={ requestId?.let { setOverlayLocked(this,it,timerStart,true) } },
                extensionRequest = extensionRequest,
                onContentMeasured = overlay::measureContent,
                overtimeCue = overtimeCue,
                stopwatchCue = stopwatchCue, reserveStopwatchStatus = stopwatchRules.reserveStatus,
                reserveOvertimeStatus = overtimeSettings.enabled && OvertimeEffect.STATUS in overtimeSettings.effects,
                overtimeInfo = overlayState.overtimeReminder,
                onSnooze = if (overtimeSettings.enabled && overtimeSettings.interaction == OvertimeInteraction.CHOICE &&
                    !c4Frame.active && overlayState.overtimeReminder != null) ({ seconds, operationId ->
                    requestId?.let { id -> snoozeOvertime(OvertimeSnoozeRequest(OvertimeKey(id, timerStart), seconds, operationId)) }
                }) else null,
                extensionStrategy = settings.strategy, c4 = c4Frame, c4Expanded = c4Expanded,
                onC4Toggle = { if (!stopping && !overlayState.overlayLocked) {
                    c4Expanded = !c4Expanded
                    // A skipped compact effect must not reappear when the device is collapsed later.
                    if (c4Expanded) overtimeCue = null
                } }, paused = overlayState.paused,
                onC4Key = { key ->
                    if (!stopping && !overlayState.overlayLocked && c4Runtime?.key(key) == C4KeyResult.DEFUSED) finishSession(defusedFeedback = true)
                },
                // Preserve the ordinary overlay's existing resume affordance;
                // starting a pause remains on the established widget/tile controls.
                onTogglePause = if (SessionControls.canResume(overlayState))
                    ({ if (!overlayState.overlayLocked) setPaused(timerStart, false) }) else null,
                onExtend = if (countdown != null && !c4Frame.active)
                    ({ seconds -> if (!overlayState.overlayLocked) extendTimer(timerStart, seconds) }) else null) {
                if (!overlayState.overlayLocked && c4Runtime?.locked() != true) finishSession()
            }
        } }
    }

    private fun publishC4Frame(frame: C4Frame) {
        val changed = frame.active != c4Frame.active
        val phaseChanged = frame.phase != c4Frame.phase
        if (changed) c4Expanded = false
        c4Frame = frame
        overlayState = overlayState.copy(c4Locked = frame.requiresCode, c4Active = frame.active)
        if (window != null && !stopping && !initializing) mutableSession.value = overlayState
        if (phaseChanged && !stopping) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun completeCountdown() {
        val timer = countdown ?: return
        val physicalNow = SystemClock.elapsedRealtime()
        val now = manualClock.at(physicalNow)
        if (timer.mode != TimerMode.COUNTDOWN || !completionReminder.consume(true, manualClock.paused, timer.remainingAt(now))) return
        overlayState = overlayState.copy(remainingMillis = 0, progress = 1f, stage = ReminderStage.FINISHED,
            elapsedMillis = timer.elapsedAt(now), overtimeMillis = timer.overtimeAt(now), sampledAt = physicalNow)
        mutableSession.value = overlayState
        c4Runtime?.completed()
        Log.d(TAG, "Timer completed: remaining=0")
        ReminderVibration.once(this, settingsStore.settings.value.intensity)
        refreshOvertime()
    }

    private fun overtimeContext(physicalNow: Long = SystemClock.elapsedRealtime()): OvertimeContext {
        val timer = countdown
        val now = manualClock.at(physicalNow)
        return OvertimeContext(activeStartRequestId?.let { id -> timer?.let { OvertimeKey(id, it.startedAt) } },
            inOvertime = !stopping && monitor == null && timer?.mode == TimerMode.COUNTDOWN && timer.remainingAt(now) == 0L,
            paused = manualClock.paused, overtimeMillis = timer?.overtimeAt(now) ?: 0,
            elapsedMillis = timer?.elapsedAt(now) ?: 0, plannedMillis = timer?.totalPlannedMillis ?: 0,
            c4 = c4Frame.active, c4Expanded = c4Expanded, c4AudioBusy = successPending || c4Runtime?.audioBusy() == true)
    }

    private fun refreshOvertime() {
        if (stopping) return
        val now = manualClock.at(SystemClock.elapsedRealtime())
        val context = overtimeContext()
        val settings = overtimeStore.settings.value
        val cue = overtimeEngine.update(now, context, settings)
        if (!context.inOvertime || !settings.enabled || context.paused) {
            overtimeCue = null
            overtimeFeedback?.close(); overtimeFeedback = null
        }
        if (cue != null) {
            Log.d(TAG, "Overtime reminder round=${cue.round} effects=${cue.effects} level=${cue.level} overtime=${context.overtimeMillis} elapsed=${context.elapsedMillis} planned=${context.plannedMillis} next=${overtimeEngine.state.nextAt}")
            if (cue.effects.isNotEmpty()) {
                overtimeCue = cue
                handler.removeCallbacks(clearOvertimeCue)
                handler.postDelayed(clearOvertimeCue, 2200)
                val feedback = overtimeFeedback ?: OvertimeReminderFeedback(this).also { overtimeFeedback = it }
                feedback.play(cue)
            }
        }
        val state = overtimeEngine.state
        val next = state.nextAt.takeUnless { context.paused }
        if (scheduledOvertimeAt != next) {
            handler.removeCallbacks(overtimeDue)
            scheduledOvertimeAt = next
            Log.d(TAG, "Overtime schedule next=$next now=$now exact=${state.exact} count=${state.snoozeCount}")
            next?.let { handler.postDelayed(overtimeDue, (it - now).coerceAtLeast(1L)) }
        }
        val info = if (settings.enabled && context.inOvertime && state.nextAt != null)
            OvertimeReminderInfo((state.nextAt!! - now).coerceAtLeast(0), state.snoozeCount,
                state.lastSnoozeSeconds ?: settings.intervalSeconds, settings.interaction == OvertimeInteraction.CHOICE,
                context.plannedMillis) else null
        if (overlayState.overtimeReminder != info) {
            overlayState = overlayState.copy(overtimeReminder = info)
            if (overlayState.active) mutableSession.value = overlayState
        }
    }

    private fun snoozeOvertime(request: OvertimeSnoozeRequest) {
        if (stopping) return
        val context = overtimeContext()
        val settings = overtimeStore.settings.value
        val now = manualClock.at(SystemClock.elapsedRealtime())
        if (!request.accepts(context, settings) || !overtimeEngine.snooze(now, context, settings, request.seconds, request.operationId)) {
            Log.d(TAG, "Overtime snooze rejected: stale, duplicate or inactive request")
            return
        }
        overtimeCue = null
        overtimeFeedback?.close(); overtimeFeedback = null
        Log.d(TAG, "Overtime snoozed seconds=${request.seconds} count=${overtimeEngine.state.snoozeCount} timerStart=${countdown?.startedAt} elapsed=${context.elapsedMillis} planned=${context.plannedMillis}")
        refreshOvertime()
    }

    private fun cancelOvertime() {
        handler.removeCallbacks(overtimeDue); handler.removeCallbacks(clearOvertimeCue)
        scheduledOvertimeAt = null; overtimeCue = null; overtimeEngine.clear()
        overtimeFeedback?.close(); overtimeFeedback = null
    }

    private fun refreshStopwatchReminders() {
        if(stopping || initializing) return
        val timer=countdown?.takeIf { it.mode==TimerMode.STOPWATCH } ?: return
        val engine=stopwatchEngine ?: return
        val elapsed=timer.elapsedAt(manualClock.at(SystemClock.elapsedRealtime()))
        if(manualClock.paused) {
            stopwatchCue=null;handler.removeCallbacks(clearStopwatchCue)
            stopwatchFeedback?.close();stopwatchFeedback=null
        }
        engine.update(elapsed,manualClock.paused)?.let { raw ->
            val cue=raw.allowed(stopwatchStore.permissions.value)
            Log.d(TAG,"Stopwatch reminder level=${cue.level} elapsed=$elapsed effects=${cue.effects} next=${engine.nextElapsed}")
            if(cue.effects.isNotEmpty() || cue.colors.isNotEmpty()) {
                stopwatchCue=cue
                handler.removeCallbacks(clearStopwatchCue);handler.postDelayed(clearStopwatchCue,2200)
                if(cue.effects.any { !it.visual }) {
                    val feedback=stopwatchFeedback ?: OvertimeReminderFeedback(this).also { stopwatchFeedback=it }
                    feedback.play(cue.effects)
                }
            }
        }
        val next=engine.nextElapsed.takeUnless { manualClock.paused }
        if(scheduledStopwatchElapsed!=next) {
            handler.removeCallbacks(stopwatchDue);scheduledStopwatchElapsed=next
            next?.let { handler.postDelayed(stopwatchDue,(it-elapsed).coerceAtLeast(1)) }
        }
    }

    private fun cancelStopwatchReminders() {
        handler.removeCallbacks(stopwatchDue);handler.removeCallbacks(clearStopwatchCue)
        scheduledStopwatchElapsed=null;stopwatchCue=null;stopwatchEngine=null
        stopwatchFeedback?.close();stopwatchFeedback=null
    }

    private fun applyScreenState(lit: Boolean) {
        if (stopping || !overlayState.screenDriven || countdown == null) return
        val now = SystemClock.elapsedRealtime()
        if (screenTracker?.update(now, lit) != true) return
        if (lit) manualClock.resume(now) else manualClock.pause(now)
        handler.removeCallbacks(tick)
        tick.run()
        // A transition checkpoint cannot be dropped behind an already queued periodic save.
        record?.let { recordStore.save(it) }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        Log.d(TAG, "Screen timer lit=$lit session=$activeStartRequestId elapsed=${overlayState.elapsedMillis}")
    }

    private fun applyOverlayLock(request: OverlayLockRequest) {
        val timer = countdown ?: return
        val overlay = window ?: return
        if (stopping || initializing || timer.startedAt != request.legStartedAt || overlayState.overlayLocked == request.locked) return
        val next = OverlayLockPolicy.apply(overlayState, request) ?: return
        // Synchronous cancellation/flag update precedes publishing a new Compose state.
        overlay.setTouchLocked(request.locked)
        if (stopping) return
        overlayState = next
        mutableSession.value = overlayState
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        Log.d(TAG, "Overlay touch locked=${request.locked} session=${request.sessionId} leg=${request.legStartedAt}")
    }

    private fun setPaused(expectedStart: Long, paused: Boolean) {
        if (if (paused) !SessionControls.canPause(overlayState) else !SessionControls.canResume(overlayState)) return
        val timer = countdown ?: return
        val physicalNow = SystemClock.elapsedRealtime()
        if (stopping || timer.startedAt != expectedStart || manualClock.paused == paused ||
            (timer.mode == TimerMode.COUNTDOWN && timer.remainingAt(manualClock.at(physicalNow)) == 0L)) return
        screenTracker?.update(physicalNow,!paused)
        if (paused) manualClock.pause(physicalNow) else manualClock.resume(physicalNow)
        handler.removeCallbacks(tick)
        tick.run()
        record?.let { recordStore.save(it) }
        Log.d(TAG, "Timer paused=${manualClock.paused} remaining=${overlayState.remainingMillis}")
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun extendTimer(expectedStart: Long, seconds: Int) {
        if (c4Runtime?.stageActive() == true) return
        val timer = countdown ?: return
        if (stopping || timer.startedAt != expectedStart || seconds !in 60..10800) return
        if (ReminderSettingsStore.get(this).settings.value.strategy.validationError(seconds * 1000L) != null) return
        val next = timer.extendAt(manualClock.at(SystemClock.elapsedRealtime()), seconds * 1000L) ?: return
        countdown = next
        cancelOvertime();cancelStopwatchReminders()
        completionReminder.reset()
        handler.removeCallbacks(tick)
        tick.run()
        Log.d(TAG, "Timer extended: extra=${next.durationMillis} totalPlanned=${next.totalPlannedMillis}")
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
        val stopIntent = if (overlayState.screenDriven) widgetControlIntent(this, WidgetCommand.END, overlayState)
            .setData("floatingfocus://screen-stop/${overlayState.startRequestId}/${overlayState.timerStartedAt}".toUri())
            else Intent(this, FloatingService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val finished = overlayState.stage == ReminderStage.FINISHED
        // Runtime construction can publish its first frame before the property is assigned.
        val c4Locked = c4Runtime?.locked() ?: c4Frame.requiresCode
        val lock = overlayState.takeIf { it.active && !it.automatic && it.startRequestId != null }?.let { state ->
            PendingIntent.getService(this, 2, overlayLockIntent(this, state.startRequestId!!,
                state.timerStartedAt, !state.overlayLocked), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(if (overlayState.screenDriven) "亮屏计时 · ${if (overlayState.paused) "等待亮屏" else "计时中"}" else if (automatic) "指定 App 监测 · ${if (mutableMonitor.value.running) "计时中" else "已暂停/等待"}" else if (overlayState.paused) "Floating Focus · 已暂停" else if (finished) "Floating Focus · 超时计时" else "Floating Focus · 正在计时")
            .setContentText(if(overlayState.mode==TimerMode.STOPWATCH && !automatic)
                "${formatElapsed(overlayState.elapsedMillis)} · ${if(overlayState.screenDriven) "息屏暂停，可随时结束" else if(overlayState.paused) "已暂停，可继续或结束" else "正计时，可随时结束"}" else if (c4Locked) "C4 · ${formatRemaining(overlayState.remainingMillis)}" else if (c4Frame.expired) "C4 超时 +${formatElapsed(c4Frame.overtimeMillis)} · 可结束，不可加时" else if (automatic) "${mutableMonitor.value.reason} · 点击管理；可随时停止" else if (overlayState.paused) "${if (overlayState.mode == TimerMode.STOPWATCH) formatElapsed(overlayState.elapsedMillis) else formatRemaining(overlayState.remainingMillis)} · 点击浮窗继续或结束" else if (finished) "正在记录超时时长，可随时结束" else "点击悬浮胶囊可展开操作")
            .setContentIntent(open).apply {
                if (lock != null) addAction(0, if (overlayState.overlayLocked) "解锁浮窗" else "锁定浮窗", lock)
                if (!c4Locked) addAction(0, if (automatic) "停止监测" else "结束计时", stop)
            }
            .setWhen(if (overlayState.mode == TimerMode.STOPWATCH || finished)
                System.currentTimeMillis() - (if (finished) overlayState.overtimeMillis else overlayState.elapsedMillis)
                else System.currentTimeMillis() + overlayState.remainingMillis)
            .setUsesChronometer(overlayState.mode != TimerMode.STOPWATCH && (!automatic || mutableMonitor.value.running) && !overlayState.paused)
            .setShowWhen(overlayState.mode != TimerMode.STOPWATCH)
            .setChronometerCountDown(overlayState.mode == TimerMode.COUNTDOWN && !finished)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun fail(message: String, cause: RuntimeException? = null) {
        if (successPending) { cancelSuccessAndStop(); return }
        if (stopping) return
        if (cause != null) Log.w(TAG, message, cause)
        if (monitor != null) AutoTriggerLog.record(this, "MONITOR_ERROR $message")
        startAdmission.complete(activeStartRequestId)
        mutableSession.value = SessionState(error = message, startRequestId = activeStartRequestId)
        finishSession(preserveError = true, status = RecordStatus.ERROR)
    }

    private fun cancelSuccessAudio() {
        successGeneration++
        successAudio?.close(); successAudio = null
        successPending = false
    }

    private fun cancelSuccessAndStop() {
        cancelSuccessAudio()
        cleanup()
        mutableSession.value = SessionState()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun finishSuccessIfReady(generation: Long) {
        if (generation != successGeneration || !successPending || !successWindowClosed || successAudio != null) return
        successPending = false
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun finishSession(preserveError: Boolean = false, status: RecordStatus = RecordStatus.MANUAL, defusedFeedback: Boolean = false) {
        if (successPending && !defusedFeedback) { cancelSuccessAndStop(); return }
        if (stopping) return
        stopping = true
        screenObserver?.close(); screenObserver = null
        screenTracker?.finish(SystemClock.elapsedRealtime())
        cancelOvertime();cancelStopwatchReminders()
        checkpointRecord(SystemClock.elapsedRealtime())
        record?.takeIf { it.packageName.isNotBlank() }?.let { quotaStore.checkpoint(it.id, false, SystemClock.elapsedRealtime(), finish = true) }
        mutableMonitor.value = AutoMonitorStatus(reason = if (preserveError) mutableSession.value.error ?: "已停止" else "未开启")
        if (defusedFeedback) {
            successPending = true; successWindowClosed = false
            val generation = ++successGeneration
            successAudio = c4Runtime?.releaseForDefuse {
                if (generation == successGeneration) {
                    successAudio = null
                    finishSuccessIfReady(generation)
                }
            }
            c4Runtime?.close(); c4Runtime = null
            handler.removeCallbacksAndMessages(null)
            // Freeze and persist NOW. Audio ownership outlives the visible timer only.
            completeRecord(status) { Handler(Looper.getMainLooper()).postDelayed({
                if (generation != successGeneration) return@postDelayed
                window?.close(); window = null; countdown = null; c4Frame = C4Frame()
                Log.d(TAG, "C4 success window closed")
                successWindowClosed = true
                mutableSession.value = SessionState()
                stopping = false
                finishSuccessIfReady(generation)
            }, 220) }
            return
        }
        cleanup()
        val finishedRequestId = activeStartRequestId
        completeRecord(status) { Handler(Looper.getMainLooper()).post {
            // A later accepted start owns its own notification/session; an old write callback cannot stop it.
            if (activeStartRequestId != finishedRequestId || startPending) return@post
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            if (!preserveError) mutableSession.value = SessionState()
        } }
    }

    private fun cleanup() {
        initializing=false
        screenObserver?.close(); screenObserver = null
        cancelOvertime();cancelStopwatchReminders()
        c4Runtime?.close(); c4Runtime = null
        c4Frame = C4Frame()
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
        val ends = TimerTaskPolicy.endsSession(rootIntent?.component?.className)
        Log.d(TAG, "Task removed root=${rootIntent?.component?.className} endsSession=$ends")
        if (ends) finishSession(status = RecordStatus.TASK_REMOVED)
        else {
            checkpointRecord(SystemClock.elapsedRealtime())
            record?.let { recordStore.save(it) }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        screenObserver?.close(); screenObserver = null
        screenTracker?.finish(SystemClock.elapsedRealtime())
        startAdmission.complete(activeStartRequestId)
        cancelSuccessAudio()
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
        private const val EXTRA_REQUEST_ID = "start_request_id"
        private val startAdmission = TimerStartAdmission()
        val pendingStarts = startAdmission.pending
        val startPending: Boolean get() = startAdmission.pendingId != null
        private const val ACTION_EXTENSION_EDITOR = "app.floatingfocus.EXTENSION_EDITOR"
        private const val ACTION_OVERTIME_SNOOZE = "app.floatingfocus.OVERTIME_SNOOZE"
        private const val ACTION_OVERLAY_LOCK = "app.floatingfocus.OVERLAY_LOCK"
        private const val EXTRA_OVERLAY_LOCKED = "overlay_locked"
        private const val ACTION_APP_OPENED = "app.floatingfocus.APP_OPENED"
        private const val ACTION_STOP = "app.floatingfocus.STOP"
        private const val ACTION_WIDGET_CONTROL = "app.floatingfocus.WIDGET_CONTROL"
        private const val ACTION_LAUNCH_FAILED = "app.floatingfocus.LAUNCH_FAILED"
        private const val ACTION_MONITOR = "app.floatingfocus.MONITOR"
        private const val EXTRA_DURATION = "duration_millis"
        private const val EXTRA_C4_ENABLED = "c4_enabled_for_session"
        private const val EXTRA_SCREEN_DRIVEN = "screen_driven"
        private const val EXTRA_RECOVERY_ID = "recovery_record_id"
        private const val EXTRA_STOPWATCH_REMINDERS = "stopwatch_reminders_for_session"
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
        fun start(context: Context, durationMillis: Long, mode: TimerMode = TimerMode.COUNTDOWN, targetPackage: String? = null,
                  presetId: String? = null, requestId: String = java.util.UUID.randomUUID().toString(),
                  c4Enabled: Boolean? = null, stopwatchBehavior: StopwatchBehavior? = null,
                  recoveryId: String? = null, stopwatchReminders: StopwatchReminderRules? = null): Boolean {
            if (mutableMonitor.value.enabled || mutableSession.value.active || startPending) return false
            ReminderSettingsStore.get(context).settings.value.strategy.validationError(durationMillis, mode)?.let {
                mutableSession.value = SessionState(error = it); return false
            }
            val reminders=resolveStopwatchRules(mode) { stopwatchReminders ?: StopwatchReminderSettingsStore.get(context).snapshot() }
            if(reminders==null) { mutableSession.value=SessionState(error="正计时提醒读取失败，请在设置中重新保存。");return false }
            if (!startAdmission.reserve(requestId, mutableMonitor.value.enabled || mutableSession.value.active)) return false
            mutableSession.value = SessionState()
            try {
                val sessionC4Enabled = C4SettingsStore.get(context).settings.value.appliesTo(mode, c4Enabled)
                val screenDriven = StopwatchBehavior.resolve(mode, StopwatchBehaviorStore.get(context).behavior.value,
                    stopwatchBehavior) == StopwatchBehavior.SCREEN_ON
                ContextCompat.startForegroundService(context, Intent(context, FloatingService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_DURATION, if (mode == TimerMode.STOPWATCH) 0L else durationMillis)
                    .putExtra("mode", mode.name).putExtra("app_package", targetPackage).putExtra("preset_id", presetId)
                    .putExtra(EXTRA_REQUEST_ID, requestId).putExtra(EXTRA_C4_ENABLED, sessionC4Enabled)
                    .putExtra(EXTRA_SCREEN_DRIVEN, screenDriven).putExtra(EXTRA_RECOVERY_ID,recoveryId)
                    .putExtra(EXTRA_STOPWATCH_REMINDERS,StopwatchReminderSettingsStore.encodeSnapshot(reminders)))
                return true
            } catch (e: RuntimeException) {
                Log.w(TAG, "Foreground start rejected", e)
                startAdmission.complete(requestId)
                mutableSession.value = SessionState(error = "系统暂时无法启动计时，请保持主界面可见后重试。", startRequestId = requestId)
                return false
            }
        }
        fun startScreenTimer(context: Context): Boolean = start(context, 0, TimerMode.STOPWATCH,
            c4Enabled = false, stopwatchBehavior = StopwatchBehavior.SCREEN_ON)
        fun resumeStopwatch(context: Context, recordId: String): Boolean {
            if (session.value.active || startPending || monitoring.value.enabled || appTimerLaunchGate.busy) return false
            val store=TimerRecordStore.get(context)
            val source=store.recoverySource(recordId)
            if (source==null || !OverlayPermission.granted(context)) {
                mutableSession.value=SessionState(error=if (source==null) store.error.value ?: "进度正在读取或已续计，请重试。"
                    else "请先授予悬浮窗权限，再点击继续计时。")
                return false
            }
            return start(context,0,TimerMode.STOPWATCH,c4Enabled=false,stopwatchBehavior=source.stopwatchBehavior,recoveryId=recordId,
                stopwatchReminders=source.stopwatch?.stopwatchReminders ?: StopwatchReminderRules())
        }
        fun requestExtension(context: Context, expectedStart: Long) {
            if (!session.value.active) return
            context.startService(Intent(context, FloatingService::class.java).setAction(ACTION_EXTENSION_EDITOR)
                .putExtra("timer_start", expectedStart))
        }
        /** Home remains the unlock route when notification permission is unavailable. */
        fun setOverlayLocked(context: Context, expectedSessionId: String, expectedStart: Long, locked: Boolean) {
            if (!OverlayLockPolicy.accepts(session.value, OverlayLockRequest(expectedSessionId, expectedStart, locked))) return
            context.startService(overlayLockIntent(context, expectedSessionId, expectedStart, locked))
        }
        private fun overlayLockIntent(context: Context, sessionId: String, leg: Long, locked: Boolean): Intent =
            Intent(context, FloatingService::class.java).setAction(ACTION_OVERLAY_LOCK)
                .setData("floatingfocus://overlay-lock/$sessionId/$leg/$locked".toUri())
                .putExtra(EXTRA_REQUEST_ID, sessionId).putExtra("timer_start", leg).putExtra(EXTRA_OVERLAY_LOCKED, locked)
        fun requestOvertimeSnooze(context: Context, expectedSessionId: String, expectedStart: Long,
                                  seconds: Int, operationId: String) {
            if (!session.value.active) return
            context.startService(Intent(context, FloatingService::class.java).setAction(ACTION_OVERTIME_SNOOZE)
                .putExtra(EXTRA_REQUEST_ID, expectedSessionId).putExtra("timer_start", expectedStart)
                .putExtra("seconds", seconds).putExtra("operation_id", operationId))
        }
        fun appOpened(context: Context, app: LaunchableApp, requestId: String) {
            context.startService(Intent(context, FloatingService::class.java).setAction(ACTION_APP_OPENED)
                .putExtra("app_package", app.packageName).putExtra("app_name", app.label).putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra("opened_wall", System.currentTimeMillis()).putExtra("opened_elapsed", SystemClock.elapsedRealtime()))
        }
        fun stop(context: Context) {
            context.startService(Intent(context, FloatingService::class.java).setAction(ACTION_STOP))
        }
        internal fun widgetControlIntent(context: Context, command: WidgetCommand, state: SessionState): Intent =
            Intent(context, FloatingService::class.java).setAction(ACTION_WIDGET_CONTROL)
                .putExtra("widget_command", command.name).putExtra(EXTRA_REQUEST_ID, state.startRequestId)
                .putExtra("timer_start", state.timerStartedAt)
        fun cancelFailedLaunch(context: Context, requestId: String) {
            context.startService(Intent(context, FloatingService::class.java).setAction(ACTION_LAUNCH_FAILED).putExtra(EXTRA_REQUEST_ID, requestId))
        }
    }
}
