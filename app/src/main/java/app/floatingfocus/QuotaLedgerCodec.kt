package app.floatingfocus

import java.time.ZoneId
import java.util.UUID

/** Storage-independent validation. Invalid data is never interpreted as a new empty budget. */
object QuotaLedgerCodec {
    fun encode(s: QuotaLedger): Map<String, Any> = mapOf(
        "version" to 1, "zoneId" to s.zoneId, "resetMinute" to s.resetMinute,
        "pendingReset" to (s.pendingReset ?: -1),
        "clock" to mapOf("logicalMillis" to s.clock.logicalMillis, "elapsedMillis" to s.clock.elapsedMillis, "boot" to s.clock.boot),
        "day" to day(s.day), "history" to s.history.map(::day),
        "apps" to s.apps.mapValues { (_, a) -> mapOf("limitMillis" to a.limitMillis, "enabled" to a.enabled, "enabledSince" to a.enabledSince) },
        "cursor" to (s.cursor?.let { mapOf("id" to it.id, "packageName" to it.packageName, "owner" to it.owner, "boot" to it.boot,
            "elapsedMillis" to it.elapsedMillis, "logicalMillis" to it.logicalMillis, "running" to it.running, "closed" to it.closed) } ?: emptyMap()))
    private fun day(d: QuotaDay) = mapOf("startMillis" to d.startMillis, "endMillis" to d.endMillis, "used" to d.used)
    private fun Map<*, *>.long(key: String): Long = (get(key) as? Number)?.let {
        val v = it.toDouble(); require(v.isFinite() && v % 1 == 0.0); it.toLong()
    } ?: error("Missing $key")
    private fun Map<*, *>.text(key: String) = (get(key) as? String)?.also { require(it.isNotBlank()) } ?: error("Missing $key")
    private fun Map<*, *>.bool(key: String) = get(key) as? Boolean ?: error("Missing $key")
    private fun Map<*, *>.map(key: String) = get(key) as? Map<*, *> ?: error("Missing $key")
    private fun readDay(m: Map<*, *>): QuotaDay {
        val start = m.long("startMillis"); val end = m.long("endMillis")
        require(start > 0 && end > start)
        val used = m.map("used").entries.associate { (key, value) ->
            require(key is String && key.isNotBlank() && value is Number)
            val amount = value.toLong(); require(amount >= 0 && value.toDouble() == amount.toDouble())
            key to amount
        }
        return QuotaDay(start, end, used)
    }
    fun decode(m: Map<*, *>): QuotaLedger? = try {
        require(m.long("version") == 1L)
        val zone = m.text("zoneId"); ZoneId.of(zone)
        val reset = m.long("resetMinute"); val pending = m.long("pendingReset")
        require(reset in 0..1439 && pending in -1..1439)
        val c = m.map("clock")
        val clock = QuotaClock(c.long("logicalMillis"), c.long("elapsedMillis"), c.long("boot").toInt())
        require(clock.logicalMillis > 0 && clock.elapsedMillis >= 0)
        val day = readDay(m.map("day"))
        val history = (m["history"] as? List<*>)?.map { readDay(it as Map<*, *>) } ?: error("Missing history")
        require((history + day).zipWithNext().all { (a, b) -> a.endMillis <= b.startMillis })
        val apps = m.map("apps").entries.associate { (key, value) ->
            require(key is String && key.isNotBlank()); val a = value as Map<*, *>
            val limit = a.long("limitMillis"); val since = a.long("enabledSince")
            require(limit in 1000..QuotaLedger.MAX_LIMIT_MILLIS && since > 0)
            key to AppQuota(limit, a.bool("enabled"), since)
        }
        require((history + day).all { apps.keys.containsAll(it.used.keys) })
        val v = m.map("cursor")
        val cursor = if (v.isEmpty()) null else QuotaCursor(v.text("id"), v.text("packageName"), v.text("owner"), v.long("boot").toInt(),
            v.long("elapsedMillis"), v.long("logicalMillis"), v.bool("running"), v.bool("closed")).also {
            require(it.elapsedMillis >= 0 && it.logicalMillis > 0)
        }
        QuotaLedger(zone, reset.toInt(), clock, day, apps, history, cursor, pending.takeIf { it >= 0 }?.toInt())
    } catch (_: RuntimeException) { null }
}

/** Called only on serial IO. Failed usage writes retain their cursor for retry; failed edits roll back. */
class QuotaJournal(initial: QuotaLedger, private val persist: (QuotaLedger) -> Unit) {
    private val owner = UUID.randomUUID().toString()
    private var engine = DailyAppQuota(initial, owner)
    val ledger get() = engine.state
    fun change(configuration: Boolean = false, block: DailyAppQuota.() -> Unit): Boolean {
        val before = ledger
        engine.block()
        return try { persist(ledger); true } catch (_: java.io.IOException) {
            if (configuration) engine = DailyAppQuota(before, owner)
            false
        } catch (_: RuntimeException) {
            if (configuration) engine = DailyAppQuota(before, owner)
            false
        }
    }
}
