package app.floatingfocus

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StopwatchRecoveryTest {
    private val wall = Instant.parse("2026-11-05T01:00:00Z").toEpochMilli()
    private fun record(id: String = "parent", elapsed: Long = 300000, checkpoint: StopwatchCheckpoint? =
        StopwatchCheckpoint(StopwatchBehavior.CONTINUOUS, ScreenTimeSnapshot(elapsed, listOf(ActiveInterval(0, elapsed))))) =
        TimerRecord(id, "App", "com.example.app", TimerMode.STOPWATCH, 0, wall, elapsed,
            RecordStatus.INTERRUPTED, stopwatch = checkpoint)

    @Test fun onlyReliableInterruptedStopwatchesAreCandidates() {
        val r = record()
        assertEquals(r, StopwatchRecovery.source(listOf(r), r.id))
        for (status in listOf(RecordStatus.MANUAL, RecordStatus.TASK_REMOVED, RecordStatus.RUNNING))
            assertNull(StopwatchRecovery.source(listOf(r.copy(status = status)), r.id))
        assertNull(StopwatchRecovery.source(listOf(record(checkpoint = null)), r.id))
        val countdown = TimerRecord("countdown", "App", "pkg", TimerMode.COUNTDOWN, 1000, wall, 5000, RecordStatus.INTERRUPTED)
        assertTrue(StopwatchRecovery.candidates(listOf(countdown)).isEmpty())
        val oldScreen = TimerRecord("screen", "亮屏计时", "", TimerMode.STOPWATCH, 0, wall, 1000,
            RecordStatus.INTERRUPTED, screenTime = ScreenTimeSnapshot(4000, listOf(ActiveInterval(0,1000))))
        assertEquals(oldScreen, StopwatchRecovery.source(listOf(oldScreen), "screen"))
    }

    @Test fun childConsumesParentAndOnlyNewestInterruptedSegmentCanContinue() {
        val parent = record()
        val child = record("child", 60000).let { it.copy(stopwatch = it.stopwatch!!.copy(
            carriedMillis = parent.displayElapsedMillis, continuationOf = parent.id, rootId = parent.id)) }
        assertNull(StopwatchRecovery.source(listOf(parent, child), parent.id))
        assertEquals(child, StopwatchRecovery.source(listOf(parent, child), child.id))
        assertEquals(360000L, child.displayElapsedMillis)
        assertEquals(60000L, child.elapsedMillis)
        assertTrue(StopwatchRecovery.candidates(listOf(parent, child.copy(status = RecordStatus.MANUAL))).isEmpty())
    }

    @Test fun chartAndCsvNeverAddCarriedBaselineAgain() {
        val parent = record()
        val child = record("child", 60000).let { it.copy(startedWallMillis = wall + 3600000,
            stopwatch = it.stopwatch!!.copy(carriedMillis = 300000, continuationOf = "parent", rootId = "parent")) }
        val period = HistoryStatistics.period(HistoryRange.TODAY, LocalDate.of(2026,11,5), ZoneId.of("UTC"))
        val report = HistoryStatistics.summarize(listOf(parent,child), period, null, Instant.ofEpochMilli(wall+7200000))
        assertEquals(360000L, report.totalMillis)
        assertEquals(report.totalMillis, report.totals.sum())
        val csv = TimerRecordCsv.encode(listOf(child))
        assertTrue(csv.contains("\"0\",\"60\",\"0\""))
        assertTrue(csv.contains("\"parent\",\"parent\",\"300\""))
    }

    @Test fun newContinuousStopwatchExcludesManualPauseAndKeepsRealSpan() {
        val active = ScreenTimeSnapshot(10000, listOf(ActiveInterval(0,2000),ActiveInterval(8000,10000)))
        val r = record(elapsed = 4000, checkpoint = StopwatchCheckpoint(StopwatchBehavior.CONTINUOUS, active))
        assertEquals(wall+10000, r.endedWallMillis)
        assertEquals(4000L, r.elapsedMillis)
        assertThrows(IllegalArgumentException::class.java) { r.copy(elapsedMillis = 10000) }
    }

    @Test fun checkpointCodecRejectsMalformedNewDataInsteadOfLegacyFallback() {
        val original = record().stopwatch!!
        val row = StopwatchCheckpointCodec.encode(original)
        assertEquals(original, StopwatchCheckpointCodec.decode(row))
        for (bad in listOf(row - "activity", row + ("behavior" to "unknown"),
            row + ("positions" to null),
            row + ("carriedMillis" to -1), row + ("continuationOf" to "parent"),
            row + ("activity" to mapOf("spanMillis" to 50, "intervals" to listOf(listOf(0,100))))))
            assertNull(StopwatchCheckpointCodec.decode(bad))
        assertThrows(IllegalArgumentException::class.java) { record().copy(stopwatch = original.copy(
            continuationOf = "parent", rootId = "parent")) }
    }

    @Test fun resumedQuotaCursorOnlyChargesNewRunningInterval() {
        fun time(ms: Long) = QuotaTime(wall+ms,ms,1)
        val quota = DailyAppQuota(QuotaLedger.create("UTC", time(0)), "process")
        quota.configure("pkg", 3600000, true, time(0))
        quota.begin("parent", "pkg", time(0))
        quota.checkpoint("parent", false, time(10000), finish=true)
        quota.begin("child", "pkg", time(100000))
        quota.checkpoint("child", false, time(105000))
        assertEquals(15000L, quota.summary("pkg")!!.usedMillis)
        quota.checkpoint("child", false, time(200000), finish=true)
        assertEquals(15000L, quota.summary("pkg")!!.usedMillis)
    }
}
