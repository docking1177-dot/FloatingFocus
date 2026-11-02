package app.floatingfocus

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** System-bound only while the panel listens. No persistent observer, worker or clock of its own. */
class FocusTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    private var lastClick = Long.MIN_VALUE

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            combine(FloatingService.session, QuickDurationsStore.get(this@FocusTileService).startupDefaults,
                FloatingService.pendingStarts) { session, defaults, pending ->
                TimerTileModel.present(session, defaults, SystemClock.elapsedRealtime(), pending != null)
            }.distinctUntilChanged().collect { render(it) }
        }
    }

    override fun onStopListening() { listening?.cancel(); listening = null; super.onStopListening() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun render(p: TimerTilePresentation) {
        qsTile?.apply {
            label = getString(R.string.tile_label)
            subtitle = p.subtitle
            icon = Icon.createWithResource(this@FocusTileService, R.drawable.ic_qs_timer)
            state = when (p.state) {
                TimerTileState.ACTIVE -> Tile.STATE_ACTIVE
                TimerTileState.INACTIVE -> Tile.STATE_INACTIVE
                TimerTileState.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
            }
            contentDescription = "$label，${p.subtitle}"
            if (Build.VERSION.SDK_INT >= 30) stateDescription = p.subtitle
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val now = SystemClock.elapsedRealtime()
        if (lastClick != Long.MIN_VALUE && now - lastClick < 500) return
        lastClick = now
        val clicked = FloatingService.session.value
        val p = TimerTileModel.present(clicked, QuickDurationsStore.get(this).startupDefaults.value, now, FloatingService.startPending)
        if (p.state == TimerTileState.UNAVAILABLE) return
        val action = Runnable { execute(clicked, p.command) }
        if (isLocked) unlockAndRun(action) else action.run()
    }

    private fun execute(clicked: SessionState, command: WidgetCommand) {
        if (!TimerTileModel.accepts(FloatingService.session.value, command, clicked.startRequestId,
                clicked.timerStartedAt, FloatingService.startPending)) {
            open(MainActivity::class.java)
            return
        }
        try {
            when (command) {
                WidgetCommand.START -> open(WidgetStartActivity::class.java)
                WidgetCommand.PAUSE, WidgetCommand.RESUME -> startService(FloatingService.widgetControlIntent(this, command, clicked))
                else -> open(MainActivity::class.java)
            }
        } catch (e: RuntimeException) {
            Log.w("FloatingFocus", "Quick Settings action unavailable", e)
            open(MainActivity::class.java)
        }
    }

    private fun open(activity: Class<*>) {
        val intent = Intent(this, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).apply {
            // Bring the existing control page forward without destroying a settings/preset draft above it.
            if (activity == MainActivity::class.java) addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this,
                if (activity == WidgetStartActivity::class.java) 710 else 711, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            else openLegacy(intent)
        } catch (e: RuntimeException) { Log.w("FloatingFocus", "Cannot open FF from Quick Settings", e) }
    }

    // API29–33 have no PendingIntent overload. This path is never used on API34+.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openLegacy(intent: Intent) { startActivityAndCollapse(intent) }
}
