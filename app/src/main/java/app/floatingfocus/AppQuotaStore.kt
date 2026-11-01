package app.floatingfocus

import android.content.Context
import android.app.Application
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.util.concurrent.Executors

data class AppQuotaView(val ledger: QuotaLedger? = null, val loading: Boolean = true, val error: String? = null) {
    fun summary(packageName: String): QuotaSummary? = ledger?.apps?.get(packageName)?.let {
        QuotaSummary(it.limitMillis, ledger.day.used[packageName] ?: 0, it.enabled)
    }
}

/** One atomic ledger, no observer service or polling. Only existing UI/service events submit work. */
class AppQuotaStore private constructor(private val context: Application) {
    private val file = AtomicFile(File(context.filesDir, "app_quotas.json"))
    private val metadata = context.getSharedPreferences("app_quota_meta", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    private var journal: QuotaJournal? = null
    private val current = MutableStateFlow(AppQuotaView())
    val state = current.asStateFlow()

    init { io.execute { load() } }

    fun time(elapsed: Long = SystemClock.elapsedRealtime(), wall: Long = System.currentTimeMillis()) =
        QuotaTime(wall, elapsed, bootCount)

    private fun load(): Boolean = try {
        val raw = if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            check(!metadata.getBoolean("initialized", false)) { "Missing quota ledger" }
            QuotaLedger.create(ZoneId.systemDefault().id, time())
        } else {
            val objectRow = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            QuotaLedgerCodec.decode(jsonValue(objectRow) as Map<*, *>) ?: error("Invalid quota ledger")
        }
        journal = QuotaJournal(raw, ::write)
        current.value = AppQuotaView(raw, loading = false)
        true
    } catch (e: Exception) { report(e); false }

    private fun write(ledger: QuotaLedger) {
        if (ledger.apps.isEmpty() && ledger.resetMinute == 0 && ledger.pendingReset == null && !metadata.getBoolean("initialized", false)) return
        val stream = file.startWrite()
        try {
            stream.write(JSONObject(QuotaLedgerCodec.encode(ledger)).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            metadata.edit { putBoolean("initialized", true) }
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }

    private fun submit(configuration: Boolean = false, block: DailyAppQuota.() -> Unit): CompletableDeferred<AppQuotaView> {
        val result = CompletableDeferred<AppQuotaView>()
        io.execute {
            try {
                if (journal == null && !load()) { result.complete(current.value); return@execute }
                val saved = journal!!.change(configuration, block)
                current.value = AppQuotaView(journal!!.ledger, loading = false,
                    error = if (saved) null else "额度保存失败，请检查存储空间后重试；未恢复满额。")
                result.complete(current.value)
            } catch (e: Exception) { report(e); result.complete(current.value) }
        }
        return result
    }

    suspend fun refresh(): AppQuotaView { val now = time(); return submit { refresh(now) }.await() }
    fun refreshSoon() { val now = time(); submit { refresh(now) } }
    suspend fun configure(packageName: String, seconds: Int, enabled: Boolean): Boolean {
        val now = time()
        return submit(configuration = true) { configure(packageName, seconds * 1000L, enabled, now) }.await().error == null
    }
    suspend fun changeReset(minute: Int): Boolean {
        val now = time()
        return submit(configuration = true) { changeReset(minute, now) }.await().error == null
    }
    fun begin(id: String, packageName: String, elapsed: Long, wall: Long) {
        val now = time(elapsed, wall); submit { begin(id, packageName, now) }
    }
    fun checkpoint(id: String, running: Boolean, elapsed: Long, finish: Boolean = false) {
        val now = time(elapsed); submit { checkpoint(id, running, now, finish) }
    }
    private fun report(e: Exception) {
        Log.w("FloatingFocus", "App quota storage failed", e)
        current.value = AppQuotaView(journal?.ledger, loading = false, error = "额度读取或保存失败，请重试；不会按满额启动。")
    }
    companion object {
        @Volatile private var instance: AppQuotaStore? = null
        fun get(context: Context): AppQuotaStore = instance ?: synchronized(this) {
            instance ?: AppQuotaStore(context.applicationContext as Application).also { instance = it }
        }
        private fun jsonValue(value: Any?): Any? = when (value) {
            is JSONObject -> value.keys().asSequence().associateWith { jsonValue(value.get(it)) }
            is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
            JSONObject.NULL -> null
            else -> value
        }
    }
}
