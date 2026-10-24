package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class TimerHistoryTest {
    @Test fun `countdown keeps elapsed and overtime after its single completion event`() {
        val timer = Countdown(300_000, 500)
        val latch = CompletionReminder()
        assertEquals(200_000L, timer.elapsedAt(200_500))
        assertFalse(latch.consume(true, false, timer.remainingAt(300_499)))
        assertTrue(latch.consume(true, false, timer.remainingAt(300_500)))
        val later = ReminderSnapshot.at(timer, 320_500)
        assertEquals(0L, later.remainingMillis)
        assertEquals(20_000L, later.overtimeMillis)
        assertEquals(320_000L, later.elapsedMillis)
        assertFalse(latch.consume(true, false, later.remainingMillis))
        assertEquals("◷  超时 +00:20", ReminderPresenter.present(later).title)
    }

    @Test fun `stopwatch starts at zero and has no deadline reminder`() {
        val timer = Countdown(0, 500, TimerMode.STOPWATCH)
        for (elapsed in listOf(0L, 999L, 1000L, 200_000L, 20_000_000L)) {
            val snapshot = ReminderSnapshot.at(timer, 500 + elapsed)
            val presentation = ReminderPresenter.present(snapshot, ReminderSettings(style = ReminderStyle.SIZE))
            assertFalse(presentation.finished)
            assertEquals(ReminderStage.QUIET, snapshot.stage)
            assertEquals(1f, presentation.size, 0f)
            assertEquals(0L, snapshot.overtimeMillis)
            assertEquals(elapsed, snapshot.elapsedMillis)
        }
        assertEquals("00:00", formatElapsed(999))
        assertEquals("00:01", formatElapsed(1000))
        assertEquals("◷  03:20", ReminderPresenter.present(ReminderSnapshot.at(timer, 200_500)).title)
    }

    @Test fun `abrupt recovery ends at checkpoint and never adds time until next launch`() {
        val started = TimerRecord("id", "App", "pkg", TimerMode.COUNTDOWN, 300_000, 1_000_000)
        val checkpoint = started.checkpoint(1000, 201_000)
        val recovered = checkpoint.recover()
        assertEquals(200_000L, recovered.elapsedMillis)
        assertEquals(1_200_000L, recovered.endedWallMillis)
        assertEquals(RecordStatus.INTERRUPTED, recovered.status)
        assertEquals(recovered, recovered.recover())
        val finished = checkpoint.copy(status = RecordStatus.MANUAL)
        assertEquals(finished, finished.recover())
        assertEquals(0L, recovered.overtimeMillis)
    }

    @Test fun `record duration uses monotonic time and overtime excludes planned duration`() {
        val record = TimerRecord("id", "App", "pkg", TimerMode.COUNTDOWN, 300_000, 1_000_000)
            .checkpoint(500, 330_500)
        assertEquals(30_000L, record.overtimeMillis)
        assertEquals(record, record.checkpoint(500, 200_500))
        assertEquals(0L, record.copy(mode = TimerMode.STOPWATCH).overtimeMillis)
    }

    @Test fun `CSV handles punctuation unicode newlines and spreadsheet formulas`() {
        val record = TimerRecord("id", "=SUM(1,2)\n\"视频\"", "pkg", TimerMode.COUNTDOWN,
            300_000, 1_000_000, 200_000, RecordStatus.INTERRUPTED)
        val csv = TimerRecordCsv.encode(listOf(record))
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("\"'=SUM(1,2)\n\"\"视频\"\"\""))
        assertTrue(csv.contains("\"300\",\"200\",\"0\""))
        assertTrue(csv.contains("异常中断，约计"))
    }

    @Test fun `preset round trips stopwatch while missing mode keeps old countdown default`() {
        val preset = TimerPreset("id", "正计时", 15, ReminderSettings(), TimerMode.STOPWATCH)
        assertEquals(preset, TimerPresetCodec.decode(TimerPresetCodec.encode(preset)))
        assertEquals(TimerMode.COUNTDOWN, TimerPresetCodec.decode(TimerPresetCodec.encode(preset) - "mode")!!.mode)
    }
}
