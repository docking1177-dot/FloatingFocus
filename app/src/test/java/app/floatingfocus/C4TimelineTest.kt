package app.floatingfocus

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class C4TimelineTest {
    private fun timeline() = C4Timeline.parse(listOf(File("src/main/assets/c4_timeline.lrc"),
        File("app/src/main/assets/c4_timeline.lrc")).first { it.isFile }.readText())
    @Test fun `all 136 supplied events and numbers are retained without guessing frequencies`() {
        val events = timeline().events
        assertEquals(136, events.size)
        assertEquals((0..135).toList(), events.map { it.index })
        assertEquals(88, events.count { it.text.contains("滴") })
        assertEquals(11, events.count { it.text.contains("快速连响") })
        assertEquals(4920L, events.single { it.text.startsWith("下包完成") }.atMillis)
        assertEquals(44_440L, events.first { it.text.startsWith("爆炸") }.atMillis)
        assertEquals(50_340L, events.last().atMillis)
    }
    @Test fun `sixty seconds enters at 44439 without adding planting time`() {
        val timer = Countdown(60_000, 1000)
        val t = timeline()
        assertEquals(C4Phase.OFF, t.phase(timer.remainingAt(16_560)))
        assertEquals(C4Phase.PLANTING, t.phase(timer.remainingAt(16_561)))
        assertEquals(60_000L, timer.durationMillis)
    }
    @Test fun `short starts map to their current recording offsets`() {
        val t = timeline()
        assertEquals(C4Phase.PLANTING, t.phase(44_439))
        assertEquals(C4Phase.PLANTING, t.phase(40_000))
        assertEquals(C4Phase.ARMED, t.phase(30_000))
        assertEquals(C4Phase.ARMED, t.phase(10_000))
        assertEquals(C4Phase.PLANTING, t.phase(39_520))
        assertEquals(C4Phase.ARMED, t.phase(39_519))
    }
    @Test fun `planting taps are dropped not buffered for opening`() {
        val input = C4Input()
        C4Timeline.CODE.forEach { assertEquals(C4KeyResult.IGNORED, input.key(it, 39_520)) }
        assertEquals("", input.digits)
        assertEquals(C4KeyResult.CHANGED, input.key('7', 39_519))
        assertEquals("7", input.digits)
    }
    @Test fun `backspace clear and seventh digit validation work without penalty or lockout`() {
        val input = C4Input()
        input.key('7', 30_000); input.key('3', 30_000); input.key('*', 30_000)
        assertEquals("7", input.digits)
        input.key('#', 30_000); assertEquals("", input.digits)
        "1111111".forEach { input.key(it, 30_000) }
        assertTrue(input.error(30_000)); assertEquals("", input.digits)
        assertFalse(input.error(29_580))
        C4Timeline.CODE.dropLast(1).forEach { assertEquals(C4KeyResult.CHANGED, input.key(it, 29_000)) }
        assertEquals(C4KeyResult.DEFUSED, input.key('8', 29_000))
        assertTrue(input.defused)
        assertEquals(C4KeyResult.IGNORED, input.key('8', 28_999))
    }
    @Test fun `deadline wins at zero while a correct final digit one millisecond before wins once`() {
        for (remaining in listOf(0L, -1L)) {
            val input = C4Input()
            "735560".forEach { input.key(it, 1000) }
            assertEquals(C4KeyResult.EXPIRED, input.key('8', remaining)); assertFalse(input.defused)
        }
        val early = C4Input()
        "735560".forEach { early.key(it, 1000) }
        assertEquals(C4KeyResult.DEFUSED, early.key('8', 1))
        assertEquals(C4KeyResult.IGNORED, early.key('8', 0))
    }
    @Test fun `tail is one continuous phase and restores ordinary overtime at the actual tail boundary`() {
        val t = timeline()
        assertEquals(C4Phase.EXPLODING, t.phase(0))
        assertEquals(C4Phase.EXPLODING, t.phase(-5901))
        assertEquals(C4Phase.OFF, t.phase(-5902))
        assertNull(t.nextDelay(-5902))
    }
    @Test fun `LED uses supplied cue positions and never catches up a missed flash`() {
        val t = timeline()
        assertFalse(t.led(44_439 - 5400))
        assertTrue(t.led(44_439 - 5440))
        assertFalse(t.led(44_439 - 5520))
        assertTrue(t.led(44_439 - 43_470))
        assertFalse(t.led(44_439 - 43_515))
        assertFalse(t.led(0))
        assertTrue(t.nextDelay(44_439 - 40_000)!! > 0)
    }
    @Test fun `pre stage pause continues using the existing monotonic countdown`() {
        val clock = SessionClock()
        val timer = Countdown(60_000, 1000)
        clock.pause(1100)
        assertEquals(59_900, timer.remainingAt(clock.at(20_000)))
        clock.resume(21_000)
        assertEquals(59_900, timer.remainingAt(clock.at(21_000)))
        assertEquals(1000L, timer.startedAt)
    }
    @Test fun `device keys keep distinct 44dp targets within normal compact and landscape frames`() {
        for ((width, height) in listOf(260f to 320.55f, 292f to 300f, 292f to 360f, 356f to 438f, 180f to 280f)) {
            val g = C4Geometry(width, height)
            val keys = (0..11).map(g::key)
            assertTrue(keys.all { it.width >= 44 && it.height >= 44 && it.x >= 0 && it.y >= 0 &&
                it.x + it.width <= width && it.y + it.height <= height })
            assertTrue(keys.zipWithNext().all { (a, b) -> a.y != b.y || a.x + a.width < b.x })
        }
    }
    @Test fun `C4 theme is independent of ordinary appearance even at deadline`() {
        val settings = ReminderSettings(baseSize = 1.2f, opacity = .3f, color = OverlayColor.BLUE,
            colorEnabled = true, sizeEnabled = true)
        for (remaining in listOf(44_439L, 20_000L, 1000L, 0L)) {
            val p = ReminderPresenter.forC4(C4Frame(C4Phase.ARMED, remainingMillis = remaining))
            assertEquals(1f, p.size, .001f)
            assertEquals(0xFF555B31.toInt(), p.colorArgb)
            assertEquals(.7f, p.opacity, .001f)
            assertEquals(OverlayShape.ROUNDED_RECTANGLE, p.shape)
            assertEquals(0f, p.pulseDepth, .001f)
            assertFalse(p.finished)
        }
        val ordinary = ReminderPresenter.present(ReminderSnapshot(0, 1f, 1000), settings)
        assertTrue(ordinary.size > 1.2f)
        assertTrue(ordinary.finished)
    }
    @Test fun `keyboard leaves visible gaps without shrinking independent touch targets`() {
        val g = C4Geometry(292f, 360f)
        assertTrue(g.gap >= 4f)
        assertTrue(g.key(0).width >= 44f)
        assertTrue(g.key(0).x + g.key(0).width < g.key(1).x)
    }
    @Test fun `presets snapshot C4 enable without adding sound to appearance`() {
        val p = TimerPreset("short", "short", 1, ReminderSettings(), durationSeconds = 10)
        val encoded = TimerPresetCodec.encode(p)
        assertEquals(p, TimerPresetCodec.decode(encoded))
        assertEquals(false, encoded["c4Enabled"])
    }
}
