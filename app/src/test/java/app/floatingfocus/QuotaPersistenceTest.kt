package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class QuotaPersistenceTest {
    private fun time(s: Long) = QuotaTime(1_793_534_400_000 + s * 1000, s * 1000, 1)

    @Test fun roundTripIncludesUsageCursorAndPendingBoundary() {
        val e = DailyAppQuota(QuotaLedger.create("Asia/Shanghai", time(0)))
        e.configure("video", 120_000, true, time(0)); e.begin("one", "video", time(0))
        e.checkpoint("one", false, time(20)); e.changeReset(240, time(20))
        assertEquals(e.state, QuotaLedgerCodec.decode(QuotaLedgerCodec.encode(e.state)))
    }

    @Test fun malformedLedgerIsReadFailureRatherThanFreshAllowance() {
        val row = QuotaLedgerCodec.encode(QuotaLedger.create("UTC", time(0))).toMutableMap()
        row["resetMinute"] = 1440
        assertNull(QuotaLedgerCodec.decode(row))
        row["resetMinute"] = 0; row["zoneId"] = "missing/timezone"
        assertNull(QuotaLedgerCodec.decode(row))
        row["zoneId"] = "UTC"; row["apps"] = mapOf("video" to mapOf("limitMillis" to -1))
        assertNull(QuotaLedgerCodec.decode(row))
    }

    @Test fun failedUsageWriteRetriesWithoutDoubleChargingAndFailedConfigDoesNotCommitDraft() {
        var fail = false
        var disk = QuotaLedger.create("UTC", time(0))
        val journal = QuotaJournal(disk) { if (fail) throw java.io.IOException("disk full") else disk = it }
        assertTrue(journal.change { configure("video", 120_000, true, time(0)) })
        assertTrue(journal.change { begin("one", "video", time(0)) })
        fail = true
        assertFalse(journal.change { checkpoint("one", true, time(20)) })
        assertFalse(journal.change(configuration = true) { configure("video", 60_000, true, time(20)) })
        assertEquals(120_000L, journal.ledger.apps["video"]!!.limitMillis)
        fail = false
        assertTrue(journal.change { checkpoint("one", true, time(30), finish = true) })
        assertEquals(30_000L, disk.day.used["video"])
        assertEquals(30_000L, journal.ledger.day.used["video"])
    }
}
