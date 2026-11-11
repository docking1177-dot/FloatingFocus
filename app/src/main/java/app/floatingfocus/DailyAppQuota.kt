package app.floatingfocus

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

enum class PresetTimeSource { FIXED, APP_DAILY_QUOTA }
data class QuotaTime(val wallMillis: Long, val elapsedMillis: Long, val boot: Int)
data class QuotaClock(val logicalMillis: Long, val elapsedMillis: Long, val boot: Int)
data class AppQuota(val limitMillis: Long, val enabled: Boolean, val enabledSince: Long)
data class QuotaDay(val startMillis: Long, val endMillis: Long, val used: Map<String, Long> = emptyMap())
data class QuotaCursor(val id: String, val packageName: String, val owner: String, val boot: Int,
                       val elapsedMillis: Long, val logicalMillis: Long, val running: Boolean, val closed: Boolean = false)
data class QuotaSummary(val limitMillis: Long, val usedMillis: Long, val enabled: Boolean) {
    val remainingMillis: Long get() = (limitMillis - usedMillis).coerceAtLeast(0)
    val extraMillis: Long get() = (usedMillis - limitMillis).coerceAtLeast(0)
}

data class QuotaLedger(val zoneId: String, val resetMinute: Int, val clock: QuotaClock, val day: QuotaDay,
                       val apps: Map<String, AppQuota> = emptyMap(), val history: List<QuotaDay> = emptyList(),
                       val cursor: QuotaCursor? = null, val pendingReset: Int? = null, val clockWarning: String? = null,
                       val cursors: List<QuotaCursor> = emptyList()) {
    val nextResetMillis: Long get() = pendingReset?.let { QuotaCalendar.atOrAfter(day.endMillis, it, zoneId) } ?: day.endMillis
    companion object {
        const val MAX_LIMIT_MILLIS = Countdown.MAX_DURATION_MILLIS
        fun create(zoneId: String, time: QuotaTime, resetMinute: Int = 0): QuotaLedger {
            require(resetMinute in 0..1439 && time.wallMillis > 0 && time.elapsedMillis >= 0)
            return QuotaLedger(zoneId, resetMinute, QuotaClock(time.wallMillis, time.elapsedMillis, time.boot),
                QuotaCalendar.containing(time.wallMillis, resetMinute, zoneId))
        }
    }
}

/** Calendar days in a fixed zone, including 23/25-hour DST days. */
internal object QuotaCalendar {
    private fun boundary(date: java.time.LocalDate, minute: Int, zone: ZoneId) =
        date.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
    fun containing(wall: Long, minute: Int, zoneId: String): QuotaDay {
        val zone = ZoneId.of(zoneId)
        var date = Instant.ofEpochMilli(wall).atZone(zone).toLocalDate()
        if (boundary(date, minute, zone) > wall) date = date.minusDays(1)
        return QuotaDay(boundary(date, minute, zone), boundary(date.plusDays(1), minute, zone))
    }
    fun atOrAfter(wall: Long, minute: Int, zoneId: String): Long {
        val zone = ZoneId.of(zoneId)
        val date = Instant.ofEpochMilli(wall).atZone(zone).toLocalDate()
        return boundary(date, minute, zone).takeIf { it >= wall } ?: boundary(date.plusDays(1), minute, zone)
    }
    fun after(wall: Long, minute: Int, zoneId: String) = atOrAfter(wall + 1, minute, zoneId)
}

/** Service-owned identity and an atomically saved cursor deduplicate settlement.
 * A fresh process never advances the abandoned process's running interval. */
class DailyAppQuota(initial: QuotaLedger, private val owner: String = UUID.randomUUID().toString()) {
    var state: QuotaLedger = initial
        private set
    fun summary(packageName: String): QuotaSummary? = state.apps[packageName]?.let {
        QuotaSummary(it.limitMillis, state.day.used[packageName] ?: 0, it.enabled)
    }
    fun configure(packageName: String, limitMillis: Long, enabled: Boolean, time: QuotaTime) {
        require(packageName.isNotBlank() && limitMillis in 1000..QuotaLedger.MAX_LIMIT_MILLIS)
        refresh(time) // settle with OLD configuration before editing the limit/enable boundary
        val old = state.apps[packageName]
        state = state.copy(apps = state.apps + (packageName to AppQuota(limitMillis, enabled,
            if (enabled && old?.enabled != true) state.clock.logicalMillis else old?.enabledSince ?: state.clock.logicalMillis)))
    }
    fun changeReset(minute: Int, time: QuotaTime) {
        require(minute in 0..1439)
        refresh(time)
        if (minute == (state.pendingReset ?: state.resetMinute)) return
        // Repeated edits cannot move the already scheduled boundary earlier.
        val end = QuotaCalendar.atOrAfter(state.nextResetMillis, minute, state.zoneId)
        state = state.copy(day = state.day.copy(endMillis = end), pendingReset = minute)
    }
    private fun cursors() = state.cursors.ifEmpty { listOfNotNull(state.cursor) }
    private fun saveCursors(rows: List<QuotaCursor>) {
        state = state.copy(cursors = rows, cursor = rows.lastOrNull())
    }
    fun begin(id: String, packageName: String, time: QuotaTime) {
        if (cursors().any { it.id == id }) return
        // Settle all live intervals before inserting another session. Abandoned
        // process cursors are not charged, including after a reboot.
        refresh(time)
        val live = cursors().filter { it.owner == owner && !it.closed }
        saveCursors(live + QuotaCursor(id, packageName, owner, time.boot, time.elapsedMillis,
            logicalAt(time), running = true))
    }
    fun checkpoint(id: String, running: Boolean, time: QuotaTime, finish: Boolean = false) {
        val cursor = cursors().firstOrNull { it.id == id } ?: return
        if (cursor.owner != owner || cursor.closed || time.boot != cursor.boot || time.elapsedMillis < cursor.elapsedMillis) return
        refresh(time)
        saveCursors(cursors().map { if (it.id == id) it.copy(running = running, closed = finish) else it })
    }
    fun refresh(time: QuotaTime) {
        val rows = cursors()
        moveClock(time)
        val now = logicalAt(time)
        val live = rows.filter { it.owner == owner && !it.closed && time.boot == it.boot && time.elapsedMillis >= it.elapsedMillis }
        // Every mutation settles ALL cursors first, so their next coverage starts
        // at this same boundary. Charge a package once even with two running rows.
        val starts = live.filter { it.running }.groupBy { it.packageName }.mapValues { (_, group) -> group.minOf { it.logicalMillis } }
        // All packages share the calendar. Settle every package's slice BEFORE
        // rolling the day, so an earlier map entry cannot discard another App's past day.
        if (starts.isNotEmpty()) while (starts.values.min() < now) {
            val end = minOf(now, state.nextResetMillis)
            starts.forEach { (pkg, from) -> charge(pkg, from, end) }
            if (end == now) break
            rollOne()
        }
        if (rows.isNotEmpty()) saveCursors(rows.map { if (it in live) it.copy(elapsedMillis = time.elapsedMillis, logicalMillis = now) else it })
        rollTo(now)
    }
    private fun logicalAt(time: QuotaTime): Long = state.clock.logicalMillis -
        if (time.boot == state.clock.boot) (state.clock.elapsedMillis - time.elapsedMillis).coerceAtLeast(0) else 0
    private fun moveClock(time: QuotaTime) {
        val previous = state.clock
        if (time.boot == previous.boot && time.elapsedMillis < previous.elapsedMillis) return
        val logical = if (time.boot == previous.boot) previous.logicalMillis + (time.elapsedMillis - previous.elapsedMillis)
            else maxOf(previous.logicalMillis, time.wallMillis)
        state = state.copy(clock = QuotaClock(logical, time.elapsedMillis, time.boot), clockWarning =
            if (abs(time.wallMillis - logical) > 120_000) "系统时间有变化，额度按已保存时间继续记账，请核对系统时间。" else null)
    }
    private fun charge(packageName: String, from: Long, until: Long) {
        val app = state.apps[packageName]
        if (app?.enabled != true) return
        var point = maxOf(from, app.enabledSince, state.day.startMillis)
        while (point < until) {
            if (point >= state.nextResetMillis) { rollOne(); continue }
            val end = minOf(until, state.nextResetMillis)
            val used = state.day.used[packageName] ?: 0L
            state = state.copy(day = state.day.copy(used = state.day.used + (packageName to (used + end - point))))
            point = end
        }
    }
    private fun rollOne() {
        val end = state.nextResetMillis
        val reset = state.pendingReset ?: state.resetMinute
        state = state.copy(resetMinute = reset, pendingReset = null,
            history = if (state.day.used.isEmpty()) state.history else state.history + state.day.copy(endMillis = end),
            day = QuotaDay(end, QuotaCalendar.after(end, reset, state.zoneId)))
    }
    private fun rollTo(now: Long) {
        if (now < state.nextResetMillis) return
        rollOne()
        // Empty inactive days need no rows. Old consumed days remain immutable.
        if (now >= state.day.endMillis) state = state.copy(day = QuotaCalendar.containing(now, state.resetMinute, state.zoneId))
    }
}
