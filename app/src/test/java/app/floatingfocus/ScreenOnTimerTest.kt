package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class ScreenOnTimerTest {
    @Test fun onlyNormalAwakeDisplayCounts() {
        assertTrue(ScreenOnPolicy.counts(true, true))
        assertFalse(ScreenOnPolicy.counts(false, true)) // ambient display is not an active screen
        assertFalse(ScreenOnPolicy.counts(true, false)) // proximity-off is not visible
        assertFalse(ScreenOnPolicy.counts(false, false))
    }

    @Test fun startsOffWithoutInventingTime() {
        val tracker = ScreenOnTracker(1000)
        assertEquals(ScreenTimeSnapshot(7000, emptyList()), tracker.snapshot(8000))
        assertTrue(tracker.update(9000, true))
        assertEquals(2000L, tracker.snapshot(11000).activeMillis)
    }

    @Test fun multipleWakePeriodsShareOneActiveTotalAndExcludeOffGap() {
        val tracker = ScreenOnTracker(1000)
        tracker.update(1000, true)
        tracker.update(4000, false)
        tracker.update(12000, true)
        val saved = tracker.finish(17000)
        assertEquals(8000L, saved.activeMillis)
        assertEquals(16000L, saved.spanMillis)
        assertEquals(listOf(ActiveInterval(0, 3000), ActiveInterval(11000, 16000)), saved.intervals)
    }

    @Test fun repeatedEventsCannotSplitOrDoubleCount() {
        val tracker = ScreenOnTracker(100)
        assertTrue(tracker.update(100, true))
        assertFalse(tracker.update(200, true))
        assertTrue(tracker.update(1100, false))
        assertFalse(tracker.update(5100, false))
        assertEquals(listOf(ActiveInterval(0, 1000)), tracker.snapshot(8100).intervals)
        assertEquals(1000L, tracker.snapshot(9100).activeMillis)
    }

    @Test fun snapshotsDoNotCreateOneIntervalPerTick() {
        val tracker = ScreenOnTracker(0)
        tracker.update(0, true)
        tracker.snapshot(1000); tracker.snapshot(2000)
        assertEquals(listOf(ActiveInterval(0, 3000)), tracker.snapshot(3000).intervals)
    }

    @Test fun finishedTrackerCannotBeRestartedByLateScreenEvents() {
        val tracker = ScreenOnTracker(1000)
        tracker.update(1000, true)
        val saved = tracker.finish(5000)
        assertFalse(tracker.update(12000, true))
        assertFalse(tracker.update(13000, false))
        assertEquals(saved, tracker.finish(20000))
        assertEquals(saved, tracker.snapshot(21000))
    }

    @Test fun invalidOrOverlappingIntervalsAreRejected() {
        val invalid = listOf(
            { ScreenTimeSnapshot(-1, emptyList()) },
            { ScreenTimeSnapshot(10, listOf(ActiveInterval(-1, 5))) },
            { ScreenTimeSnapshot(10, listOf(ActiveInterval(5, 4))) },
            { ScreenTimeSnapshot(10, listOf(ActiveInterval(0, 11))) },
            { ScreenTimeSnapshot(10, listOf(ActiveInterval(0, 7), ActiveInterval(6, 10))) })
        invalid.forEach { block -> assertThrows(IllegalArgumentException::class.java) { block() } }
    }
}
