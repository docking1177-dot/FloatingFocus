package app.floatingfocus

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ScreenOnHistoryTest {
    private val zone = ZoneId.of("UTC")
    private val start = Instant.parse("2026-11-03T23:50:00Z").toEpochMilli()
    private fun screenRecord() = TimerRecord("screen-1", "亮屏计时", "", TimerMode.STOPWATCH, 0, start,
        elapsedMillis = 15 * 60000L, screenTime = ScreenTimeSnapshot(30 * 60000L,
            listOf(ActiveInterval(0, 5 * 60000L), ActiveInterval(20 * 60000L, 30 * 60000L))))

    @Test fun activeTimeAndEndSpanAreDifferent() {
        val r = screenRecord()
        assertEquals(15 * 60000L, r.elapsedMillis)
        assertEquals(Instant.parse("2026-11-04T00:20:00Z").toEpochMilli(), r.endedWallMillis)
        assertEquals(0L, r.overtimeMillis)
    }

    @Test fun midnightChartAllocatesActualIntervalsInsteadOfCompressingTheGap() {
        val r = screenRecord()
        val yesterday = HistoryStatistics.period(HistoryRange.TODAY, LocalDate.of(2026, 11, 3), zone)
        val today = HistoryStatistics.period(HistoryRange.TODAY, LocalDate.of(2026, 11, 4), zone)
        val now = Instant.parse("2026-11-04T01:00:00Z")
        val first = HistoryStatistics.summarize(listOf(r), yesterday, null, now)
        val second = HistoryStatistics.summarize(listOf(r), today, null, now)
        assertEquals(5 * 60000L, first.totalMillis)
        assertEquals(10 * 60000L, second.totalMillis)
        assertEquals(10 * 60000L, second.totals[0])
        assertEquals(1, second.entries.size)
        assertEquals(0L, HistoryStatistics.summarize(listOf(r), today, "com.target.app", now).totalMillis)
    }

    @Test fun cutoffNeverExtrapolatesAnActiveCheckpoint() {
        val period = HistoryStatistics.period(HistoryRange.TODAY, LocalDate.of(2026, 11, 4), zone)
        val report = HistoryStatistics.summarize(listOf(screenRecord()), period, null,
            Instant.parse("2026-11-04T00:15:00Z"))
        assertEquals(5 * 60000L, report.totalMillis)
    }

    @Test fun oldContinuousRecordsKeepTheirOriginalMeaning() {
        val old = TimerRecord("old-1", "手动计时", "", TimerMode.STOPWATCH, 0, start, 30 * 60000L)
        val period = HistoryStatistics.period(HistoryRange.TODAY, LocalDate.of(2026, 11, 4), zone)
        assertEquals(20 * 60000L, HistoryStatistics.summarize(listOf(old), period, null,
            Instant.parse("2026-11-04T01:00:00Z")).totalMillis)
        assertEquals(start + 30 * 60000L, old.endedWallMillis)
    }

    @Test fun inconsistentScreenRecordsCannotSilentlyBecomeContinuousTime() {
        assertThrows(IllegalArgumentException::class.java) { screenRecord().copy(elapsedMillis = 1) }
        assertThrows(IllegalArgumentException::class.java) { screenRecord().copy(packageName = "com.target.app") }
        assertThrows(IllegalArgumentException::class.java) { screenRecord().copy(mode = TimerMode.COUNTDOWN, plannedMillis = 1) }
    }

    @Test fun recoveryRetainsOnlySavedIntervalsAndNeverAddsDowntime() {
        val r = screenRecord().recover()
        assertEquals(RecordStatus.INTERRUPTED, r.status)
        assertEquals(900000L, r.elapsedMillis)
        assertEquals(screenRecord().screenTime, r.screenTime)
    }

    @Test fun csvPreservesOriginalFieldsAndExportsEffectiveTotalAndRealSpan() {
        val csv = TimerRecordCsv.encode(listOf(screenRecord()))
        assertTrue(csv.contains("\"2026-11-04T00:20:00Z\""))
        assertTrue(csv.contains("\"0\",\"900\",\"0\",\"计时中\",\"亮屏有效时长\",\"1800\""))
        assertTrue(csv.contains("[[0,300000],[1200000,1800000]]"))
        val old = TimerRecordCsv.encode(listOf(TimerRecord("old", "手动计时", "", TimerMode.STOPWATCH, 0, start, 60000)))
        assertTrue(old.contains("\"会话经过时长\",\"60\",\"\""))
    }
}
