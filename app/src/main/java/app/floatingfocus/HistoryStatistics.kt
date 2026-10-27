package app.floatingfocus

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

enum class HistoryRange(val label: String) { TODAY("今日"), WEEK("本周"), MONTH("本月") }

data class HistoryBucket(val start: Long, val end: Long, val label: String, val detail: String)
data class HistoryPeriod(val range: HistoryRange, val start: Long, val end: Long,
                         val caption: String, val buckets: List<HistoryBucket>)
data class HistoryApp(val packageName: String, val label: String)
data class HistoryEntry(val record: TimerRecord, val inRangeMillis: Long)
data class HistoryReport(val period: HistoryPeriod, val packageFilter: String?,
                         val totals: List<Long>, val entries: List<HistoryEntry>) {
    val totalMillis: Long get() = totals.fold(0L, ::saturatedSum)
    val hasRunning: Boolean get() = entries.any { it.record.status == RecordStatus.RUNNING }
    val hasApproximate: Boolean get() = entries.any { it.record.status == RecordStatus.INTERRUPTED }
}

private fun saturatedSum(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

/** Read-only aggregation of FF sessions. Never extrapolates a running or recovered checkpoint. */
object HistoryStatistics {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.SIMPLIFIED_CHINESE)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE)
    private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun period(range: HistoryRange, today: LocalDate, zone: ZoneId): HistoryPeriod {
        val first = when (range) {
            HistoryRange.TODAY -> today
            HistoryRange.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            HistoryRange.MONTH -> today.withDayOfMonth(1)
        }
        val last = when (range) {
            HistoryRange.TODAY -> first.plusDays(1)
            HistoryRange.WEEK -> first.plusDays(7)
            HistoryRange.MONTH -> first.plusMonths(1)
        }
        val from = first.atStartOfDay(zone)
        val until = last.atStartOfDay(zone)
        val buckets = buildList {
            var cursor = from
            while (cursor < until) {
                // Timeline hours preserve both instances of a repeated DST hour; days use local midnight.
                val next = (if (range == HistoryRange.TODAY) cursor.plusHours(1) else cursor.toLocalDate().plusDays(1).atStartOfDay(zone))
                    .let { if (it > until) until else it }
                val detail = if (range == HistoryRange.TODAY) {
                    val repeated = zone.rules.getValidOffsets(cursor.toLocalDateTime()).size > 1
                    "${cursor.format(timeFormat)}–${next.format(timeFormat)}" + if (repeated) " (${cursor.offset})" else ""
                } else "${cursor.format(dateFormat)} ${weekdays[cursor.dayOfWeek.value - 1]}"
                add(HistoryBucket(cursor.toInstant().toEpochMilli(), next.toInstant().toEpochMilli(), when (range) {
                    HistoryRange.TODAY -> cursor.hour.toString()
                    HistoryRange.WEEK -> weekdays[cursor.dayOfWeek.value - 1]
                    HistoryRange.MONTH -> cursor.dayOfMonth.toString()
                }, detail))
                cursor = next
            }
        }
        val caption = if (range == HistoryRange.TODAY) first.format(dateFormat)
            else "${first.format(dateFormat)}—${last.minusDays(1).format(dateFormat)}"
        return HistoryPeriod(range, from.toInstant().toEpochMilli(), until.toInstant().toEpochMilli(), caption, buckets)
    }

    fun apps(records: List<TimerRecord>): List<HistoryApp> = records.groupBy { it.packageName }.map { (pkg, rows) ->
        HistoryApp(pkg, if (pkg.isEmpty()) "手动计时" else rows.maxBy { it.startedWallMillis }.appName.ifBlank { "未命名 App" })
    }.sortedWith(compareBy<HistoryApp> { it.packageName.isEmpty() }.thenBy { it.label }.thenBy { it.packageName })

    fun summarize(records: List<TimerRecord>, period: HistoryPeriod, packageFilter: String?, now: Instant): HistoryReport {
        val totals = MutableList(period.buckets.size) { 0L }
        val entries = mutableListOf<HistoryEntry>()
        val cutoff = minOf(period.end, now.toEpochMilli())
        for (record in records) {
            if (packageFilter != null && record.packageName != packageFilter) continue
            val start = maxOf(record.startedWallMillis, period.start)
            val savedEnd = saturatedSum(record.startedWallMillis.coerceAtLeast(0), record.elapsedMillis.coerceAtLeast(0))
            val end = minOf(savedEnd, cutoff)
            if (end > start) {
                val elapsed = end - start
                entries += HistoryEntry(record, elapsed)
                period.buckets.forEachIndexed { i, bucket ->
                    val overlap = (minOf(end, bucket.end) - maxOf(start, bucket.start)).coerceAtLeast(0)
                    totals[i] = saturatedSum(totals[i], overlap)
                }
            } else if (record.elapsedMillis == 0L && record.startedWallMillis >= period.start &&
                record.startedWallMillis < period.end && record.startedWallMillis <= cutoff) {
                // Keep a newly started, zero-length session visible without inventing elapsed time.
                entries += HistoryEntry(record, 0)
            }
        }
        return HistoryReport(period, packageFilter, totals, entries.sortedByDescending { it.record.startedWallMillis })
    }
}

/** Presentation only; raw milliseconds are retained for all sums and boundary allocation. */
fun historyDurationLabel(millis: Long): String {
    if (millis <= 0) return "0秒"
    if (millis < 1000) return "不足1秒"
    val seconds = millis / 1000
    return listOfNotNull((seconds / 3600).takeIf { it > 0 }?.let { "${it}小时" },
        (seconds / 60 % 60).takeIf { it > 0 }?.let { "${it}分钟" },
        (seconds % 60).takeIf { it > 0 }?.let { "${it}秒" }).joinToString(" ")
}

data class HistoryChartScale(val unitMillis: Double, val unitLabel: String, val top: Double) {
    fun tick(fraction: Double): String = String.format(Locale.ROOT, if (top * fraction % 1.0 == 0.0) "%.0f" else "%.1f", top * fraction)
}

fun historyChartScale(maxMillis: Long): HistoryChartScale {
    val unit = when { maxMillis >= 3_600_000 -> 3_600_000.0 to "小时"; maxMillis >= 60_000 -> 60_000.0 to "分钟"; else -> 1000.0 to "秒" }
    val peak = (maxMillis / unit.first).coerceAtLeast(1.0)
    val magnitude = 10.0.pow(floor(log10(peak)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).first { peak / magnitude <= it } * magnitude
    return HistoryChartScale(unit.first, unit.second, ceil(step))
}
