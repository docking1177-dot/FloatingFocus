package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class StopwatchRecoveryPresentationTest {
    private fun record(id: String = "parent", appName: String = "App", packageName: String = "pkg",
                       checkpoint: StopwatchCheckpoint = StopwatchCheckpoint(StopwatchBehavior.CONTINUOUS,
                           ScreenTimeSnapshot(10_000, listOf(ActiveInterval(0, 10_000))))) =
        TimerRecord(id, appName, packageName, TimerMode.STOPWATCH, 0, 1_000, 10_000,
            RecordStatus.INTERRUPTED, stopwatch = checkpoint)

    @Test fun `preset origin takes precedence over a bound app`() {
        val row = record().let { it.copy(stopwatch = it.stopwatch!!.copy(presetId = "preset")) }
        assertEquals(RecoveryOrigin.PRESET, StopwatchRecoveryPresentation.origin(row))
        assertEquals(RecoveryOrigin.BOUND_APP, StopwatchRecoveryPresentation.origin(record()))
        assertEquals(RecoveryOrigin.MANUAL, StopwatchRecoveryPresentation.origin(record(packageName = "")))
    }

    @Test fun `blank record name has a usable fallback without claiming a removed app name`() {
        assertEquals("手动计时", StopwatchRecoveryPresentation.name(record(appName = "", packageName = "")))
        assertEquals("pkg", StopwatchRecoveryPresentation.name(record(appName = " ")))
        assertEquals("App", StopwatchRecoveryPresentation.name(record()))
    }

    @Test fun `selected recovery uses all records and rejects consumed or unreadable sources`() {
        val parent = record()
        val child = record("child").let { it.copy(stopwatch = it.stopwatch!!.copy(
            continuationOf = "parent", rootId = "parent", carriedMillis = 10_000)) }
        assertNull(StopwatchRecoveryPresentation.selected(listOf(parent, child), "parent", readable = true))
        assertEquals(child, StopwatchRecoveryPresentation.selected(listOf(parent, child), "child", readable = true))
        assertNull(StopwatchRecoveryPresentation.selected(listOf(parent, child), "child", readable = false))
        assertNull(StopwatchRecoveryPresentation.selected(listOf(parent, child), "removed", readable = true))
    }
}
