package app.floatingfocus

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class HistoryStatisticsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun instant(value: String) = Instant.parse(value)
    private fun record(id: String, start: String, millis: Long, pkg: String = "video", name: String = "视频") =
        TimerRecord(id, name, pkg, TimerMode.COUNTDOWN, 300_000, instant(start).toEpochMilli(), millis, RecordStatus.MANUAL)
    private fun report(rows: List<TimerRecord>, date: String = "2026-10-27", range: HistoryRange = HistoryRange.TODAY,
                       filter: String? = null, now: String = "2026-10-27T15:59:59.999Z") =
        HistoryStatistics.summarize(rows, HistoryStatistics.period(range, LocalDate.parse(date), zone), filter, instant(now))

    @Test fun `cross midnight and hour splits conserve every millisecond and one source record`() {
        val row = record("cross", "2026-10-26T15:50:00Z", 1_200_123)
        val yesterday = report(listOf(row), "2026-10-26")
        val today = report(listOf(row))
        assertEquals(600_000L, yesterday.totalMillis)
        assertEquals(600_123L, today.totalMillis)
        assertEquals(row.elapsedMillis, yesterday.totalMillis + today.totalMillis)
        assertEquals(600_123L, today.totals[0])
        assertEquals(1, today.entries.size)
        assertEquals(row, today.entries.single().record)
        assertEquals(today.totalMillis, today.entries.sumOf { it.inRangeMillis })
    }

    @Test fun `half open boundaries do not leak the previous day or a future record`() {
        val rows = listOf(record("ended", "2026-10-26T15:59:00Z", 60_000),
            record("begin", "2026-10-26T16:00:00Z", 60_000),
            record("future", "2026-10-27T16:00:00Z", 60_000))
        assertEquals(listOf("begin"), report(rows).entries.map { it.record.id })
    }

    @Test fun `all includes manual while package filter distinguishes identical labels`() {
        val rows = listOf(record("a", "2026-10-27T01:00:00Z", 1234, "one"),
            record("b", "2026-10-27T02:00:00Z", 2345, "two"),
            record("c", "2026-10-27T03:00:00Z", 3456, "", "手动计时"))
        assertEquals(7035L, report(rows).totalMillis)
        assertEquals(1234L, report(rows, filter = "one").totalMillis)
        assertEquals(3456L, report(rows, filter = "").totalMillis)
        assertEquals(3, HistoryStatistics.apps(rows).size)
        assertTrue(report(rows, filter = "uninstalled").entries.isEmpty())
    }

    @Test fun `week is Monday to Monday and month length follows the local calendar`() {
        val week = HistoryStatistics.period(HistoryRange.WEEK, LocalDate.parse("2026-11-01"), zone)
        assertEquals(instant("2026-10-25T16:00:00Z").toEpochMilli(), week.start)
        assertEquals(7, week.buckets.size)
        for ((date, days) in listOf("2024-02-20" to 29, "2025-02-20" to 28, "2026-04-01" to 30, "2026-10-27" to 31)) {
            assertEquals(days, HistoryStatistics.period(HistoryRange.MONTH, LocalDate.parse(date), zone).buckets.size)
        }
    }

    @Test fun `cross month is allocated by overlap rather than session start date`() {
        val row = record("cross", "2026-09-30T15:50:00Z", 1_200_000)
        val oct = report(listOf(row), range = HistoryRange.MONTH)
        assertEquals(600_000L, oct.totalMillis)
        assertEquals(600_000L, oct.totals[0])
    }

    @Test fun `running recovery stopwatch and overtime do not invent or double count time`() {
        val row = record("base", "2026-10-27T00:00:00Z", 400_000).copy(savedOvertimeMillis = 100_000)
        val rows = listOf(row, row.copy(id = "stopwatch", mode = TimerMode.STOPWATCH),
            row.copy(id = "live", status = RecordStatus.RUNNING), row.copy(id = "approx", status = RecordStatus.INTERRUPTED))
        val result = report(rows)
        assertEquals(1_600_000L, result.totalMillis)
        assertTrue(result.hasRunning)
        assertTrue(result.hasApproximate)
        assertEquals(rows, result.entries.map { it.record })
        assertEquals(400_000L, report(listOf(rows.last()), now = "2026-10-27T15:00:00Z").totalMillis)
    }

    @Test fun `future portions are clipped and zero length live session remains visible`() {
        val row = record("live", "2026-10-27T00:00:00Z", 120_000).copy(status = RecordStatus.RUNNING)
        assertEquals(30_000L, report(listOf(row), now = "2026-10-27T00:00:30Z").totalMillis)
        assertEquals(1, report(listOf(row.copy(elapsedMillis = 0))).entries.size)
        assertEquals(0L, report(listOf(row.copy(elapsedMillis = 0))).totalMillis)
    }

    @Test fun `DST has 23 or 25 real hourly bins and repeated hour is distinct`() {
        val ny = ZoneId.of("America/New_York")
        val spring = HistoryStatistics.period(HistoryRange.TODAY, LocalDate.parse("2026-03-08"), ny)
        val fall = HistoryStatistics.period(HistoryRange.TODAY, LocalDate.parse("2026-11-01"), ny)
        assertEquals(23, spring.buckets.size)
        assertEquals(25, fall.buckets.size)
        val repeated = fall.buckets.filter { it.label == "1" }
        assertEquals(2, repeated.size)
        assertNotEquals(repeated[0].detail, repeated[1].detail)
        val row = record("dst", "2026-11-01T04:00:00Z", 25 * 3_600_000L)
        val summary = HistoryStatistics.summarize(listOf(row), fall, null, instant("2026-11-02T05:00:00Z"))
        assertEquals(row.elapsedMillis, summary.totalMillis)
        assertTrue(summary.totals.all { it == 3_600_000L })
    }

    @Test fun `changing timezone reallocates the same saved timeline without changing records`() {
        val row = record("zone", "2026-10-26T23:50:00Z", 1_200_000)
        val utc = HistoryStatistics.period(HistoryRange.TODAY, LocalDate.parse("2026-10-27"), ZoneId.of("UTC"))
        assertEquals(600_000L, HistoryStatistics.summarize(listOf(row), utc, null, instant("2026-10-28T00:00:00Z")).totalMillis)
        assertEquals(1_200_000L, report(listOf(row)).totalMillis)
        assertEquals(1_200_000L, row.elapsedMillis)
    }

    @Test fun `old long duration cannot overflow into missing negative time`() {
        val huge = record("huge", "2026-10-27T00:00:00Z", Long.MAX_VALUE)
        assertEquals(1000L, report(listOf(huge), now = "2026-10-27T00:00:01Z").totalMillis)
    }

    @Test fun `subsecond fragments sum before formatting and axis starts at zero`() {
        val rows = (0..2).map { record("$it", "2026-10-27T01:00:00Z", 750) }
        assertEquals(2250L, report(rows).totalMillis)
        assertEquals("2秒", historyDurationLabel(report(rows).totalMillis))
        assertEquals("不足1秒", historyDurationLabel(750))
        for (max in listOf(0L, 500L, 35_000L, 90_000L, 4_500_000L, 2_000_000_000L)) {
            val scale = historyChartScale(max)
            assertEquals("0", scale.tick(0.0))
            assertTrue(scale.top * scale.unitMillis >= max)
        }
    }

    @Test fun `historical app choices use package identity and newest known label`() {
        val rows = listOf(record("old", "2026-10-26T00:00:00Z", 1000, "pkg", "旧名"),
            record("new", "2026-10-27T00:00:00Z", 1000, "pkg", "新名"))
        assertEquals(listOf(HistoryApp("pkg", "新名")), HistoryStatistics.apps(rows))
    }
}
