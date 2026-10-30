package app.floatingfocus

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

open class TimerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        TimerWidgets.observe(context); TimerWidgets.refresh(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        TimerWidgets.observe(context); TimerWidgets.refresh(context)
    }
    override fun onDisabled(context: Context) { TimerWidgets.stopIfEmpty(context) }
}
class PresetsWidgetProvider : TimerWidgetProvider()

/** A single process-local observer. Chronometer animates seconds in the launcher, not a polling job. */
object TimerWidgets {
    private var observer: Job? = null
    private var lastKey: Any? = null
    private fun ids(context: Context, manager: AppWidgetManager) =
        listOf(TimerWidgetProvider::class.java, PresetsWidgetProvider::class.java).flatMap {
            manager.getAppWidgetIds(ComponentName(context, it)).toList()
        }

    fun observe(context: Context) {
        val app = context.applicationContext
        if (observer?.isActive == true || ids(app, AppWidgetManager.getInstance(app)).isEmpty()) return
        observer = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            combine(FloatingService.session, QuickDurationsStore.get(app).startupDefaults, TimerPresetStore.get(app).presets) {
                    state, defaults, presets -> Triple(state, defaults, presets)
            }.collect { (state, defaults, presets) ->
                val p = TimerWidgetModel.present(state, defaults, SystemClock.elapsedRealtime())
                // Exclude per-second samples and draggable positions. Rebase only at real state changes.
                val key = listOf(state.active, state.paused, state.mode, state.c4Locked, state.startRequestId,
                    state.timerStartedAt, state.durationMillis, state.error, p.primary, p.overtime, defaults,
                    presets.map { listOf(it.id, it.name, it.durationSeconds, it.mode, it.targetApp) })
                if (key != lastKey) { lastKey = key; refresh(app) }
            }
        }
    }

    fun stopIfEmpty(context: Context) {
        if (ids(context, AppWidgetManager.getInstance(context)).isEmpty()) {
            observer?.cancel(); observer = null; lastKey = null
        }
    }

    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val widgets = ids(context, manager)
        if (widgets.isEmpty()) return
        val state = FloatingService.session.value
        val display = TimerWidgetModel.present(state, QuickDurationsStore.get(context).startupDefaults.value, SystemClock.elapsedRealtime())
        val presets = TimerPresetStore.get(context).snapshot()
        for (id in widgets) try {
            val font = context.resources.configuration.fontScale
            val views = if (Build.VERSION.SDK_INT >= 31) RemoteViews(mapOf(
                SizeF(110f, 40f) to layout(context, id, WidgetLayout.COMPACT, display, state, presets),
                SizeF(110f, 110f) to layout(context, id, WidgetLayout.SHORT_PRESETS, display, state, presets),
                SizeF(110f, TimerWidgetModel.tallHeight(font)) to layout(context, id, WidgetLayout.TALL_PRESETS, display, state, presets)))
            else layout(context, id, TimerWidgetModel.layoutForHeight(
                manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT), font), display, state, presets)
            manager.updateAppWidget(id, views)
        } catch (error: RuntimeException) { Log.w("FloatingFocus", "Widget update failed id=$id", error) }
    }

    private fun activity(context: Context, id: Int, path: String, target: Class<*>, action: String? = null, preset: String? = null): PendingIntent {
        val intent = Intent(context, target).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = Uri.Builder().scheme("floatingfocus").authority("widget").appendPath(id.toString()).appendPath(path).apply {
                if (preset != null) appendPath(preset)
            }.build()
            if (preset != null) putExtra(PresetDesktopShortcuts.EXTRA_ID, preset)
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun control(context: Context, id: Int, command: WidgetCommand, state: SessionState) = PendingIntent.getService(context, 0,
        FloatingService.widgetControlIntent(context, command, state).setData(Uri.Builder().scheme("floatingfocus").authority("widget")
            .appendPath(id.toString()).appendPath(command.name).appendPath(state.startRequestId.orEmpty()).appendPath(state.timerStartedAt.toString()).build()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun layout(context: Context, id: Int, kind: WidgetLayout, p: TimerWidgetPresentation, state: SessionState, presets: List<TimerPreset>?): RemoteViews {
        val large = kind != WidgetLayout.COMPACT
        val v = RemoteViews(context.packageName, when (kind) {
            WidgetLayout.COMPACT -> R.layout.widget_timer_compact
            WidgetLayout.SHORT_PRESETS -> R.layout.widget_timer_presets_short
            WidgetLayout.TALL_PRESETS -> R.layout.widget_timer_presets
        })
        val open = activity(context, id, "open", MainActivity::class.java)
        v.setOnClickPendingIntent(R.id.widget_root, open)
        v.setTextViewText(R.id.widget_status, p.status)
        v.setContentDescription(R.id.widget_status, "Floating Focus，${p.status}")
        v.setViewVisibility(R.id.widget_clock, if (p.running) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.widget_time, if (p.running) View.GONE else View.VISIBLE)
        v.setChronometerCountDown(R.id.widget_clock, p.countDown)
        v.setChronometer(R.id.widget_clock, p.base, if (p.overtime) "+%s" else null, p.running)
        v.setTextViewText(R.id.widget_time, p.time)
        val label = when (p.primary) {
            WidgetCommand.START -> "开始"; WidgetCommand.PAUSE -> "暂停"; WidgetCommand.RESUME -> "继续"; else -> "查看"
        }
        v.setTextViewText(R.id.widget_primary, label)
        v.setContentDescription(R.id.widget_primary, "$label${if (state.active) "当前计时" else "默认计时"}")
        v.setOnClickPendingIntent(if (kind == WidgetLayout.SHORT_PRESETS) R.id.widget_primary_hit else R.id.widget_primary, when (p.primary) {
            WidgetCommand.START -> activity(context, id, "start", WidgetStartActivity::class.java)
            WidgetCommand.OPEN -> open
            else -> control(context, id, p.primary, state)
        })
        if (large) {
            v.setBoolean(R.id.widget_end, "setEnabled", p.canEnd)
            v.setFloat(R.id.widget_end, "setAlpha", if (p.canEnd) 1f else .38f)
            v.setOnClickPendingIntent(R.id.widget_end, if (p.canEnd) control(context, id, WidgetCommand.END, state) else null)
            val slots = listOf(Triple(R.id.widget_preset_one, R.id.widget_preset_name_one, R.id.widget_preset_icon_one),
                Triple(R.id.widget_preset_two, R.id.widget_preset_name_two, R.id.widget_preset_icon_two))
            slots.forEachIndexed { index, (button, text, icon) ->
                val preset = presets?.getOrNull(index)
                val name = preset?.name ?: if (presets == null) "读取失败" else "添加预设"
                v.setTextViewText(text, name)
                val enabled = !state.active && !FloatingService.startPending
                v.setBoolean(button, "setEnabled", enabled)
                v.setFloat(button, "setAlpha", if (enabled) 1f else .38f)
                v.setContentDescription(button, preset?.let { "启动预设 ${it.name}，${if (it.mode == TimerMode.STOPWATCH) "正计时" else durationLabel(it.durationSeconds)}" } ?: name)
                val pixels = (24 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                val bitmap = preset?.targetApp?.let { localAppIcon(context, it.packageName)?.toBitmap(pixels, pixels) }
                if (bitmap != null) v.setImageViewBitmap(icon, bitmap) else v.setImageViewResource(icon, R.drawable.ic_app_placeholder)
                v.setOnClickPendingIntent(button, if (!enabled) null else if (preset == null) open else
                    activity(context, id, "preset", PresetShortcutActivity::class.java, PresetDesktopShortcuts.ACTION_LAUNCH, preset.id))
            }
        }
        return v
    }
}
