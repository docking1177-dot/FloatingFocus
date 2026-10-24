package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class PresetExtensionTest {
    @Test fun `preset saves bound shortcut and all existing appearance parameters`() {
        val preset = TimerPreset("id", "视频", 30, ReminderSettings(baseSize = 1.3f, opacity = .4f,
            intensity = .6f, shape = OverlayShape.VERTICAL_CAPSULE), TimerMode.COUNTDOWN,
            LaunchableApp("example.video", "视频"))
        assertEquals(preset, TimerPresetCodec.decode(TimerPresetCodec.encode(preset)))
        assertNull(TimerPresetCodec.decode(TimerPresetCodec.encode(preset) - "targetPackage" - "targetLabel")!!.targetApp)
        assertEquals("example.video", TimerPresetCodec.decode(TimerPresetCodec.encode(preset) - "targetLabel")!!.targetApp!!.label)
    }

    @Test fun `extension counts from confirmation and retains elapsed planned and overtime totals`() {
        val first = Countdown(300_000, 1000)
        val next = first.extendAt(331_000, 120_000)!!
        assertEquals(120_000L, next.remainingAt(331_000))
        assertEquals(330_000L, next.elapsedAt(331_000))
        assertEquals(420_000L, next.totalPlannedMillis)
        assertEquals(30_000L, next.totalOvertimeAt(331_000))
        assertEquals(0L, next.overtimeAt(331_000))
        assertEquals(0f, next.progressAt(331_000), 0f)
        assertFalse(ReminderPresenter.present(ReminderSnapshot.at(next, 331_000)).finished)
        val third = next.extendAt(461_000, 60_000)!!
        assertEquals(480_000L, third.totalPlannedMillis)
        assertEquals(460_000L, third.elapsedAt(461_000))
        assertEquals(40_000L, third.totalOvertimeAt(461_000))
    }

    @Test fun `extension rejects active countdown stopwatch and invalid duration`() {
        val timer = Countdown(1000, 0)
        assertNull(timer.extendAt(999, 1000))
        assertNull(timer.extendAt(1000, 0))
        assertNull(timer.extendAt(1000, Countdown.MAX_DURATION_MILLIS + 1))
        assertNull(Countdown(0, 0, TimerMode.STOPWATCH).extendAt(5000, 1000))
        assertNotNull(timer.extendAt(1000, Countdown.MAX_DURATION_MILLIS))
    }

    @Test fun `each added countdown leg gets exactly one completion reminder`() {
        val latch = CompletionReminder()
        val first = Countdown(1000, 0)
        assertTrue(latch.consume(true, false, first.remainingAt(1000)))
        val next = first.extendAt(2000, 1000)!!
        latch.reset()
        assertFalse(latch.consume(true, false, next.remainingAt(2000)))
        assertTrue(latch.consume(true, false, next.remainingAt(3000)))
        assertFalse(latch.consume(true, false, next.remainingAt(5000)))
    }

    @Test fun `history keeps earlier overtime even when the new leg ends early`() {
        val record = TimerRecord("id", "App", "pkg", TimerMode.COUNTDOWN, 420_000, 1_000_000,
            340_000, savedOvertimeMillis = 30_000)
        assertEquals(30_000L, record.recover().overtimeMillis)
        assertTrue(TimerRecordCsv.encode(listOf(record)).contains("\"420\",\"340\",\"30\""))
        assertEquals(20_000L, record.copy(plannedMillis = 320_000, savedOvertimeMillis = null).overtimeMillis)
    }
}
