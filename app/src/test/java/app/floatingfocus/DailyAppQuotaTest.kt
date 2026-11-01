package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DailyAppQuotaTest {
    private val start = Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()
    private fun sample(seconds: Long, boot: Int = 1, wall: Long = start + seconds * 1000) = QuotaTime(wall, 100_000 + seconds * 1000, boot)
    private fun engine() = DailyAppQuota(QuotaLedger.create("UTC", sample(0)))
    private fun DailyAppQuota.enable(app: String = "video") { configure(app, 7_200_000, true, sample(0)) }

    @Test fun repeatedSessionsSharePackageWithoutDoubleSettlement() {
        val e = engine(); e.enable(); e.begin("one", "video", sample(0))
        e.checkpoint("one", true, sample(1500), finish = true)
        e.checkpoint("one", true, sample(1500), finish = true)
        assertEquals(5_700_000L, e.summary("video")!!.remainingMillis)
        e.begin("two", "video", sample(1600)); e.checkpoint("two", true, sample(2500), finish = true)
        assertEquals(4_800_000L, e.summary("video")!!.remainingMillis)
    }

    @Test fun pausesDoNotDeductAndStaleRequestsCannotChargeAnotherSession() {
        val e = engine(); e.enable(); e.enable("other"); e.begin("one", "video", sample(0))
        e.checkpoint("one", false, sample(20)); e.checkpoint("one", true, sample(80))
        e.checkpoint("one", true, sample(100), finish = true)
        e.begin("two", "other", sample(110)); e.checkpoint("one", true, sample(120))
        e.checkpoint("two", true, sample(130), finish = true)
        assertEquals(40_000L, e.summary("video")!!.usedMillis)
        assertEquals(20_000L, e.summary("other")!!.usedMillis)
    }

    @Test fun enableMidSessionAndTogglePreserveAlreadyUsedTime() {
        val e = engine(); e.begin("one", "video", sample(0))
        e.configure("video", 120_000, true, sample(10)); e.checkpoint("one", true, sample(30))
        e.configure("video", 120_000, false, sample(40)); e.checkpoint("one", true, sample(50))
        e.configure("video", 120_000, true, sample(60)); e.checkpoint("one", true, sample(80))
        assertEquals(50_000L, e.summary("video")!!.usedMillis)
    }

    @Test fun editingLimitClampsRemainingAndTracksExcess() {
        val e = engine(); e.enable(); e.begin("one", "video", sample(0)); e.checkpoint("one", true, sample(80))
        e.configure("video", 60_000, true, sample(80))
        assertEquals(0L, e.summary("video")!!.remainingMillis)
        assertEquals(20_000L, e.summary("video")!!.extraMillis)
        e.configure("video", 120_000, true, sample(80))
        assertEquals(40_000L, e.summary("video")!!.remainingMillis)
    }

    @Test fun crossBoundarySplitsRuntimeWithoutRestoringOldDates() {
        val wall = Instant.parse("2026-11-01T03:59:50Z").toEpochMilli()
        fun t(s: Long) = QuotaTime(wall + s * 1000, s * 1000, 1)
        val e = DailyAppQuota(QuotaLedger.create("UTC", t(0), resetMinute = 240))
        e.configure("video", 120_000, true, t(0)); e.begin("one", "video", t(0))
        e.checkpoint("one", true, t(30), finish = true)
        assertEquals(20_000L, e.summary("video")!!.usedMillis)
        assertEquals(10_000L, e.state.history.single().used["video"])
        e.refresh(QuotaTime(wall, 240_000, 1))
        assertEquals(20_000L, e.summary("video")!!.usedMillis)
        assertNotNull(e.state.clockWarning)
    }

    @Test fun dayBoundaryEditNeverGrantsQuotaImmediatelyOrCreatesShortTransitionDay() {
        val e = engine(); e.enable(); e.begin("one", "video", sample(0)); e.checkpoint("one", true, sample(60), finish = true)
        e.changeReset(240, sample(60))
        e.refresh(sample(12 * 3600 + 1)) // old midnight must not refill
        assertEquals(60_000L, e.summary("video")!!.usedMillis)
        e.refresh(sample(16 * 3600)) // next 04:00
        assertEquals(0L, e.summary("video")!!.usedMillis)
        assertEquals(240, e.state.resetMinute)
    }

    @Test fun restartDoesNotChargeStoppedProcessAndKeepsFixedTimezone() {
        val e = engine(); e.enable(); e.begin("old", "video", sample(0)); e.checkpoint("old", true, sample(30))
        val reopened = DailyAppQuota(e.state)
        reopened.begin("new", "video", sample(1000, boot = 2)); reopened.checkpoint("new", true, sample(1020, boot = 2), finish = true)
        assertEquals(50_000L, reopened.summary("video")!!.usedMillis)
        assertEquals("UTC", reopened.state.zoneId)
    }

    @Test fun forwardClockJumpWithinBootCannotRefillAndBackwardClockCannotReopenPastDay() {
        val e = engine(); e.enable(); e.begin("one", "video", sample(0)); e.checkpoint("one", true, sample(10), finish = true)
        e.refresh(sample(20, wall = start + 5 * 86_400_000))
        assertEquals(10_000L, e.summary("video")!!.usedMillis)
        e.refresh(sample(30, boot = 2, wall = start - 86_400_000))
        assertEquals(10_000L, e.summary("video")!!.usedMillis)
    }

    @Test fun dstCalendarDaysUseFixedZoneRatherThanTwentyFourHourArithmetic() {
        val wall = Instant.parse("2026-11-01T04:00:00Z").toEpochMilli()
        val e = DailyAppQuota(QuotaLedger.create("America/New_York", QuotaTime(wall, 0, 1)))
        assertEquals(25 * 3_600_000L, e.state.day.endMillis - e.state.day.startMillis)
    }

    @Test fun delayedAppOpenedEventKeepsTheActualMonotonicStart() {
        val e = engine(); e.enable(); e.refresh(sample(5))
        e.begin("opened", "video", sample(3)); e.checkpoint("opened", true, sample(10), finish = true)
        assertEquals(7_000L, e.summary("video")!!.usedMillis)
    }

    @Test fun repeatedBoundaryEditsAfterOldMidnightCannotRefillEarly() {
        for (newReset in listOf(60, 0)) {
            val e = engine(); e.enable(); e.begin("one", "video", sample(0))
            e.checkpoint("one", true, sample(60), finish = true)
            e.changeReset(240, sample(60))
            val scheduled = e.state.nextResetMillis
            e.refresh(sample(14 * 3600)) // 02:00, after the original midnight but before 04:00
            e.changeReset(newReset, sample(14 * 3600))
            e.refresh(sample(14 * 3600 + 1))
            assertEquals(60_000L, e.summary("video")!!.usedMillis)
            assertTrue(e.state.nextResetMillis >= scheduled)
        }
    }
}
