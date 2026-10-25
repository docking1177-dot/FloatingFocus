package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class ReminderPresenterTest {
    private fun frame(progress: Float) = ReminderSnapshot(((1 - progress) * 900_000).toLong().coerceAtLeast(0), progress)

    @Test fun `all strategies consume the same immutable clock snapshot at boundaries`() {
        val timer = Countdown(900_000, 1000)
        listOf(0L to ReminderStage.QUIET, 449_999L to ReminderStage.QUIET,
            450_000L to ReminderStage.WARM, 719_999L to ReminderStage.WARM,
            720_000L to ReminderStage.URGENT, 899_999L to ReminderStage.URGENT,
            900_000L to ReminderStage.FINISHED, 960_000L to ReminderStage.FINISHED).forEach { (elapsed, stage) ->
            val snapshot = ReminderSnapshot.at(timer, 1000 + elapsed)
            assertEquals(stage, snapshot.stage)
            val presentations = ReminderStyle.entries.map { ReminderPresenter.present(snapshot, ReminderSettings(style = it)) }
            assertEquals(1, presentations.map { it.title }.distinct().size)
            assertTrue(presentations.all { it.finished == (stage == ReminderStage.FINISHED) })
            assertEquals((elapsed - 900_000).coerceAtLeast(0), snapshot.overtimeMillis)
        }
    }

    @Test fun `default color endpoints preserve real reminder policy`() {
        listOf(0f to ReminderPresenter.GREY, 0.5f to ReminderPresenter.GREY,
            0.8f to ReminderPresenter.ORANGE, 1f to ReminderPresenter.RED).forEach { (p, color) ->
            assertEquals(color, ReminderPresenter.present(frame(p)).colorArgb)
        }
        assertEquals(0f, ReminderPresenter.present(frame(0.799f)).pulseDepth, 0f)
        assertEquals(ReminderPresenter.HIGHLIGHT_DEPTH, ReminderPresenter.present(frame(0.8f)).pulseDepth, 0.0001f)
        assertEquals(0f, ReminderPresenter.present(frame(1f)).pulseDepth, 0f)
    }

    @Test fun `continuous colors interpolate while steps jump exactly at nodes`() {
        val steps = ReminderSettings(colorMode = ChangeMode.STEPS)
        assertEquals(ReminderPresenter.GREY, ReminderPresenter.present(frame(0.499f), steps).colorArgb)
        assertEquals(ReminderPresenter.ORANGE, ReminderPresenter.present(frame(0.5f), steps).colorArgb)
        assertEquals(ReminderPresenter.ORANGE, ReminderPresenter.present(frame(0.799f), steps).colorArgb)
        assertEquals(ReminderPresenter.RED, ReminderPresenter.present(frame(0.8f), steps).colorArgb)
        val middle = ReminderPresenter.present(frame(0.65f)).colorArgb
        assertNotEquals(ReminderPresenter.GREY, middle)
        assertNotEquals(ReminderPresenter.ORANGE, middle)
    }

    @Test fun `size grows monotonically without changing strategy color`() {
        val settings = ReminderSettings(style = ReminderStyle.SIZE)
        val sizes = (0..100).map { ReminderPresenter.present(frame(it / 100f), settings).size }
        assertTrue(sizes.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(1f, sizes.first(), 0f)
        assertEquals(1.5f, sizes.last(), 0f)
        assertEquals(ReminderPresenter.GREY, ReminderPresenter.present(frame(0.9f), settings).colorArgb)
    }

    @Test fun `step size changes only at configured policy nodes`() {
        val settings = ReminderSettings(style = ReminderStyle.SIZE, sizeMode = ChangeMode.STEPS)
        listOf(0.499f to 1f, 0.5f to 1.2f, 0.799f to 1.2f, 0.8f to 1.4f, 0.999f to 1.4f, 1f to 1.5f).forEach { (p, size) ->
            assertEquals(size, ReminderPresenter.present(frame(p), settings).size, 0.0001f)
        }
    }

    @Test fun `confirmation is requested once per stage without stopping timer`() {
        val settings = ReminderSettings(style = ReminderStyle.CONFIRM)
        assertFalse(ReminderPresenter.present(frame(0.49f), settings).confirmationRequired)
        assertTrue(ReminderPresenter.present(frame(0.5f), settings).confirmationRequired)
        assertFalse(ReminderPresenter.present(frame(0.7f), settings, ReminderStage.WARM).confirmationRequired)
        assertTrue(ReminderPresenter.present(frame(0.8f), settings, ReminderStage.WARM).confirmationRequired)
        assertTrue(ReminderPresenter.present(frame(1f), settings, ReminderStage.URGENT).confirmationRequired)
        assertFalse(ReminderPresenter.present(frame(1f), settings, ReminderStage.FINISHED).confirmationRequired)
        assertFalse(ReminderPresenter.present(frame(0.8f)).confirmationRequired)
    }

    @Test fun `zero intensity suppresses emphasis and parameters have bounded output`() {
        ReminderStyle.entries.forEach { style ->
            val output = ReminderPresenter.present(frame(0.9f), ReminderSettings(style = style, intensity = 0f, baseSize = 0.75f, opacity = 0.3f))
            assertEquals(ReminderPresenter.GREY, output.colorArgb)
            assertEquals(0.75f, output.size, 0f)
            assertEquals(0.3f, output.opacity, 0f)
            assertEquals(0f, output.pulseDepth, 0f)
        }
        assertEquals(2.25f, ReminderPresenter.present(frame(1f), ReminderSettings(style = ReminderStyle.SIZE, baseSize = 1.5f)).size, 0f)
        assertThrows(IllegalArgumentException::class.java) { ReminderSettings(opacity = 0f) }
        assertThrows(IllegalArgumentException::class.java) { ReminderSettings(intensity = Float.NaN) }
    }

    @Test fun `overtime is explicit and never wraps countdown display`() {
        assertEquals("◷  时间到了", ReminderPresenter.present(ReminderSnapshot.at(Countdown(1000, 0), 1000)).title)
        val output = ReminderPresenter.present(ReminderSnapshot.at(Countdown(1000, 0), 61_000))
        assertEquals("◷  超时 +01:00", output.title)
        assertTrue(output.finished)
    }
}
