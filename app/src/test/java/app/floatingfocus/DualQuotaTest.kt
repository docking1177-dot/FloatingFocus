package app.floatingfocus

import org.junit.Assert.assertEquals
import org.junit.Test

class DualQuotaTest {
    private val epoch = 1_800_000_000_000L
    private fun at(ms: Long) = QuotaTime(epoch + ms, ms, 1)
    @Test fun overlappingSameAppChargesUnionAndEndingOneKeepsOther() {
        val q = DailyAppQuota(QuotaLedger.create("UTC", at(0)), "test")
        q.configure("app", 120_000, true, at(0))
        q.begin("a", "app", at(0))
        q.begin("b", "app", at(10_000))
        q.checkpoint("a", false, at(20_000), finish = true)
        q.checkpoint("b", false, at(30_000), finish = true)
        assertEquals(30_000L, q.summary("app")!!.usedMillis)
    }
    @Test fun differentAppsAndPausedIntervalsRemainIndependent() {
        val q = DailyAppQuota(QuotaLedger.create("UTC", at(0)), "test")
        q.configure("x", 120_000, true, at(0)); q.configure("y", 120_000, true, at(0))
        q.begin("a", "x", at(0)); q.begin("b", "y", at(5_000))
        q.checkpoint("a", false, at(10_000))
        q.checkpoint("b", false, at(20_000), finish = true)
        q.checkpoint("a", true, at(25_000)); q.checkpoint("a", false, at(30_000), finish = true)
        assertEquals(15_000L, q.summary("x")!!.usedMillis)
        assertEquals(15_000L, q.summary("y")!!.usedMillis)
    }
    @Test fun persistedTwoCursorsNeverChargeDowntimeOrResumeAbandonedRows() {
        val q=DailyAppQuota(QuotaLedger.create("UTC",at(0)),"old")
        q.configure("app",120_000,true,at(0))
        q.begin("a","app",at(0));q.begin("b","app",at(1_000));q.refresh(at(10_000))
        val saved=QuotaLedgerCodec.decode(QuotaLedgerCodec.encode(q.state))!!
        val next=DailyAppQuota(saved,"new")
        next.refresh(at(50_000))
        assertEquals(10_000L,next.summary("app")!!.usedMillis)
        next.begin("c","app",at(50_000));next.checkpoint("c",false,at(60_000),true)
        assertEquals(20_000L,next.summary("app")!!.usedMillis)
    }
    @Test fun differentAppsAcrossMidnightBothReceiveBothDaysRegardlessOfOrder() {
        val midnight=java.time.Instant.parse("2026-11-11T23:59:50Z").toEpochMilli()
        fun t(ms:Long)=QuotaTime(midnight+ms,ms,1)
        for(order in listOf(listOf("x","y"),listOf("y","x"))) {
            val q=DailyAppQuota(QuotaLedger.create("UTC",t(0)),"test")
            order.forEach { q.configure(it,120_000,true,t(0));q.begin(it,it,t(0)) }
            q.refresh(t(20_000))
            for(pkg in order) {
                assertEquals(10_000L,q.state.history.single().used[pkg])
                assertEquals(10_000L,q.state.day.used[pkg])
            }
        }
    }
}
