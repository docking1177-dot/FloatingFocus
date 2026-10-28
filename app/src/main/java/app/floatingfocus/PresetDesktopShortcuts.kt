package app.floatingfocus

import android.app.PendingIntent
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.min

data class PresetPinResult(val message: String, val needsBinding: TimerPreset? = null)

/** Finite work on edits/foreground/success; never publishes dynamic shortcuts or polls. */
class PresetDesktopShortcuts private constructor(private val context: Application) {
    private val manager = context.getSystemService(ShortcutManager::class.java)
    private val metadata = context.getSharedPreferences("preset_desktop_shortcuts", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val throttle = PresetPinThrottle()

    fun scheduleSync() { scope.launch { sync() } }

    suspend fun requestPin(id: String): PresetPinResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val supported = manager?.isRequestPinShortcutSupported == true
                val check = PresetLaunchPolicy.forPin(id, if (supported) TimerPresetStore.get(context).snapshot() else null,
                    supported, context::isSavedShortcut, { context.packageManager.getLaunchIntentForPackage(it.packageName) != null })
                if (check.issue != null) return@withLock PresetPinResult(check.message!!,
                    check.preset.takeIf { check.issue in listOf(PresetLaunchIssue.BINDING, PresetLaunchIssue.UNAVAILABLE) })
                val preset = check.preset!!
                if (!throttle.acquire(SystemClock.elapsedRealtime())) return@withLock PresetPinResult("请在系统提示中确认添加。")
                val info = info(preset)
                val existing = manager!!.pinnedShortcuts.firstOrNull { it.id == info.id }
                if (existing != null) {
                    manager.enableShortcuts(listOf(info.id))
                    manager.updateShortcuts(listOf(info))
                }
                val callback = Intent(context, PresetPinnedReceiver::class.java).setAction(ACTION_PINNED)
                    .setData(("floatingfocus://pinned/" + android.net.Uri.encode(id)).toUri())
                val sender = PendingIntent.getBroadcast(context, 0, callback, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender
                if (manager.requestPinShortcut(info, sender)) PresetPinResult("请在系统提示中确认添加。")
                else { throttle.reset(); PresetPinResult("当前桌面不支持添加快捷方式。") }
            } catch (e: RuntimeException) {
                throttle.reset()
                Log.w("FloatingFocus", "Shortcut pin request failed", e)
                PresetPinResult("暂时无法请求添加，请返回 FF 后重试。")
            }
        }
    }

    suspend fun sync() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val items = TimerPresetStore.get(context).snapshot()
                val pinned = manager?.pinnedShortcuts.orEmpty().filter { PresetShortcutIdentity.presetId(it.id) != null }
                val plan = PresetShortcutSyncPlan.from(items, pinned.map { it.id }) ?: return@withLock
                for (id in plan.disable) {
                    if (pinned.any { it.id == id && it.isEnabled }) manager?.disableShortcuts(listOf(id), "该预设已删除，请在 FF 中重新添加。")
                    metadata.edit { remove(id) }
                }
                for (preset in plan.update) {
                        val old = pinned.first { it.id == PresetShortcutIdentity.shortcutId(preset.id) }
                        val signature = displaySignature(preset)
                        if (!old.isEnabled) manager?.enableShortcuts(listOf(old.id))
                        if (!old.isEnabled || old.shortLabel.toString() != preset.name || metadata.getString(old.id, null) != signature) {
                            if (manager?.updateShortcuts(listOf(info(preset))) == true) metadata.edit { putString(old.id, signature) }
                        }
                }
            } catch (e: RuntimeException) { Log.w("FloatingFocus", "Shortcut display sync deferred", e) }
        }
    }

    private fun displaySignature(preset: TimerPreset): String {
        val pkg = preset.targetApp?.packageName.orEmpty()
        @Suppress("DEPRECATION")
        val modified = try { context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime } catch (_: Exception) { 0L }
        return "${preset.name}\n$pkg\n$modified"
    }

    private fun info(preset: TimerPreset): ShortcutInfo = ShortcutInfo.Builder(context, PresetShortcutIdentity.shortcutId(preset.id))
        .setShortLabel(preset.name).setLongLabel(preset.name)
        .setActivity(ComponentName(context, MainActivity::class.java))
        .setIcon(icon(preset.targetApp?.packageName.orEmpty()))
        .setIntent(Intent(context, PresetShortcutActivity::class.java).setAction(ACTION_LAUNCH)
            .putExtra(EXTRA_ID, preset.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        .build()

    private fun icon(pkg: String): Icon = try { localIcon(pkg) } catch (_: RuntimeException) {
        Icon.createWithResource(context, R.drawable.ic_app_placeholder)
    }

    private fun localIcon(pkg: String): Icon {
        val drawable = localAppIcon(context, pkg) ?: return Icon.createWithResource(context, R.drawable.ic_app_placeholder)
        val pixels = min(manager?.iconMaxWidth ?: 192, 384).coerceAtLeast(1)
        if (drawable is AdaptiveIconDrawable) {
            // Render layers before the launcher supplies its mask; no double masking.
            return Icon.createWithAdaptiveBitmap(LayerDrawable(arrayOf(drawable.background, drawable.foreground)).toBitmap(pixels, pixels))
        }
        val bitmap = createBitmap(pixels, pixels)
        val width = drawable.intrinsicWidth.coerceAtLeast(1); val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val scale = min(pixels.toFloat() / width, pixels.toFloat() / height)
        val w = (width * scale).toInt().coerceAtLeast(1); val h = (height * scale).toInt().coerceAtLeast(1)
        drawable.setBounds((pixels-w)/2, (pixels-h)/2, (pixels+w)/2, (pixels+h)/2)
        drawable.draw(Canvas(bitmap))
        return Icon.createWithBitmap(bitmap)
    }

    companion object {
        const val ACTION_LAUNCH = "app.floatingfocus.action.LAUNCH_PRESET"
        const val ACTION_PINNED = "app.floatingfocus.action.PRESET_PINNED"
        const val EXTRA_ID = "preset_id"
        @Volatile private var instance: PresetDesktopShortcuts? = null
        fun get(context: Context): PresetDesktopShortcuts = instance ?: synchronized(this) {
            instance ?: PresetDesktopShortcuts(context.applicationContext as Application).also { instance = it }
        }
    }
}

/** Only the explicit immutable PendingIntent reaches this unexported receiver from the launcher. */
class PresetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PresetDesktopShortcuts.ACTION_PINNED || intent.data?.host != "pinned" ||
            !PresetShortcutIdentity.valid(intent.data?.lastPathSegment)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                PresetDesktopShortcuts.get(context).sync()
                val items = TimerPresetStore.get(context).snapshot()
                val message = if (items == null) "已添加到桌面，预设暂时无法读取。"
                    else if (items.none { it.id == intent.data?.lastPathSegment }) "预设已删除，桌面入口已停用。"
                    else "已添加到桌面"
                Handler(Looper.getMainLooper()).post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
            } finally { pending.finish() }
        }
    }
}
