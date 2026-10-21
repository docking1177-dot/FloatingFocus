package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class AutoTimerEngineTest {
    private fun event(t: Long, pkg: String = "target", kind: AppEventKind = AppEventKind.RESUME, activity: String = "Main") =
        AppUsageEvent(t, pkg, activity, kind)

    @Test fun `leaving pauses and return resumes same budget`() {
        val engine = AutoTimerEngine(setOf("target"), 60_000)
        engine.accept(event(1000), 1000)
        engine.accept(event(4000, kind = AppEventKind.PAUSE), 4000)
        assertEquals(57_000L, engine.timer.snapshot(50_000).remainingMillis)
        assertFalse(engine.timer.running)
        engine.accept(event(51_000), 51_000)
        assertEquals(55_000L, engine.timer.snapshot(53_000).remainingMillis)
    }
    @Test fun `home and non targets do not start a countdown`() {
        val engine = AutoTimerEngine(setOf("target"), 60_000)
        engine.accept(event(1000, "launcher"), 1000)
        assertFalse(engine.timer.started)
        engine.accept(event(2000, "launcher", AppEventKind.PAUSE), 2000)
        engine.accept(event(2000, "other"), 2000)
        assertFalse(engine.timer.running)
        assertEquals(60_000L, engine.timer.snapshot(5000).remainingMillis)
    }
    @Test fun `rapid switches within one poll count only target interval`() {
        val engine = AutoTimerEngine(setOf("target", "second"), 10_000)
        listOf(event(1000), event(1200, kind = AppEventKind.PAUSE), event(1200, "other"),
            event(1400, "other", AppEventKind.PAUSE), event(1400, "second"), event(1500, "second", AppEventKind.PAUSE))
            .forEach { engine.accept(it, it.timestamp) }
        assertEquals(9700L, engine.timer.snapshot(2000).remainingMillis)
    }
    @Test fun `multiple resumed packages pause instead of guessing split screen focus`() {
        val engine = AutoTimerEngine(setOf("target"), 10_000)
        engine.accept(event(1000), 1000)
        engine.accept(event(1500, "other"), 1500)
        assertFalse(engine.timer.running)
        assertNull(engine.foreground)
        assertEquals(9500L, engine.timer.snapshot(4000).remainingMillis)
        engine.accept(event(4000, "other", AppEventKind.PAUSE), 4000)
        assertTrue(engine.timer.running)
        assertEquals(9000L, engine.timer.snapshot(4500).remainingMillis)
    }
    @Test fun `same package activity transition does not lose resumed activity`() {
        val engine = AutoTimerEngine(setOf("target"), 10_000)
        engine.accept(event(1000, activity = "One"), 1000)
        engine.accept(event(1100, activity = "Two"), 1100)
        engine.accept(event(1200, kind = AppEventKind.PAUSE, activity = "One"), 1200)
        assertTrue(engine.timer.running)
        engine.accept(event(1400, kind = AppEventKind.STOP, activity = "Two"), 1400)
        assertFalse(engine.timer.running)
        assertEquals(9600L, engine.timer.snapshot(5000).remainingMillis)
    }
    @Test fun `screen lock clears foreground and never charges locked time`() {
        val engine = AutoTimerEngine(setOf("target"), 10_000)
        engine.accept(event(1000), 1000)
        engine.suspend(2000, "locked")
        assertEquals(9000L, engine.timer.snapshot(100_000).remainingMillis)
        assertFalse(engine.timer.running)
        engine.accept(event(101_000), 101_000)
        assertEquals(8000L, engine.timer.snapshot(102_000).remainingMillis)
    }
    @Test fun `expiry and later resumes do not restart the session`() {
        val clock = PausableCountdown(1000)
        clock.resume(1000)
        clock.pause(3000)
        clock.resume(10_000)
        assertEquals(0L, clock.snapshot(11_000).remainingMillis)
        assertEquals(1f, clock.snapshot(11_000).progress, 0f)
    }
    @Test fun `delayed out of order events cannot resurrect an old target`() {
        val engine = AutoTimerEngine(setOf("target"), 10_000)
        engine.accept(event(1000), 1000)
        engine.accept(event(2000, kind = AppEventKind.PAUSE), 2000)
        engine.accept(event(1500), 1500)
        assertFalse(engine.timer.running)
    }
    @Test fun `process restart starts disabled with no hidden elapsed time`() {
        val old = AutoTimerEngine(setOf("target"), 10_000)
        old.accept(event(1000), 1000)
        val restarted = AutoTimerEngine(setOf("target"), 10_000)
        assertFalse(restarted.timer.started)
        assertFalse(restarted.timer.running)
        assertEquals(10_000L, restarted.timer.snapshot(1_000_000).remainingMillis)
    }
    @Test fun `incremental query overlaps only two seconds and deduplicates`() {
        val cursor = UsageEventCursor(100_000, 1000)
        val first = cursor.query(101_000, 2000)
        assertEquals(100_000L, first.from)
        val e = event(100_500)
        assertEquals(listOf(e), cursor.fresh(listOf(e, e), first))
        val second = cursor.query(102_000, 3000)
        assertTrue(cursor.fresh(listOf(e), second).isEmpty())
        val third = cursor.query(103_000, 4000)
        assertEquals(100_000L, third.from)
        val fourth = cursor.query(104_000, 5000)
        assertEquals(101_000L, fourth.from)
        assertFalse(fourth.discontinuity)
    }
    @Test fun `clock jumps and long scheduling gaps do not trigger historical scans`() {
        val cursor = UsageEventCursor(100_000, 1000)
        val gap = cursor.query(150_000, 51_000)
        assertTrue(gap.discontinuity)
        assertEquals(150_000L, gap.from)
        val backwards = cursor.query(140_000, 52_000)
        assertTrue(backwards.discontinuity)
        assertTrue(cursor.fresh(listOf(event(139_999)), backwards).isEmpty())
        val jump = cursor.query(240_000, 53_000)
        assertTrue(jump.discontinuity)
    }
    @Test fun `reset permits fresh events after backwards wall clock correction`() {
        val engine = AutoTimerEngine(setOf("target"), 10_000)
        engine.accept(event(100_000), 1000)
        engine.suspend(2000, "wall clock changed")
        engine.accept(event(50_000), 3000)
        assertTrue(engine.timer.running)
        assertEquals(8000L, engine.timer.snapshot(4000).remainingMillis)
    }
    @Test fun `exclusive query end is processed exactly once on the next query`() {
        val cursor = UsageEventCursor(1000, 0)
        val e = event(2000)
        assertTrue(cursor.fresh(listOf(e), cursor.query(2000, 1000)).isEmpty())
        assertEquals(listOf(e), cursor.fresh(listOf(e), cursor.query(3000, 2000)))
    }
    @Test fun `quick unlock overlap never replays usage already charged before locking`() {
        assertEquals(10_000L, UsageEventCursor.unlockFloor(10_500, 10_000))
        assertEquals(98_000L, UsageEventCursor.unlockFloor(100_000, 10_000))
        assertEquals(5000L, UsageEventCursor.unlockFloor(5000, 10_000))
    }
}
