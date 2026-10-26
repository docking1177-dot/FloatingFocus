package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class ReminderStrategyTest {
    private fun at(elapsed: Long, duration: Long = 100_000) = ReminderSnapshot.at(Countdown(duration, 0), elapsed)

    @Test fun `default strategy preserves existing stages and continuous effect progress`() {
        val strategy = ReminderStrategy()
        for (elapsed in 0L..100_000L step 100L) {
            val frame = at(elapsed)
            assertEquals(ReminderPolicy.stage(frame.progress), strategy.stage(frame))
            assertEquals(frame.progress, strategy.effectProgress(frame), .000001f)
        }
    }

    @Test fun `progress thresholds are independent with exact inclusive boundaries`() {
        val strategy = ReminderStrategy(warm = ReminderNode(40, 60), urgent = ReminderNode(90, 20))
        assertEquals(ReminderStage.QUIET, strategy.stage(at(39_999)))
        assertEquals(ReminderStage.WARM, strategy.stage(at(40_000)))
        assertEquals(ReminderStage.WARM, strategy.stage(at(89_999)))
        assertEquals(ReminderStage.URGENT, strategy.stage(at(90_000)))
        assertEquals(ReminderStage.FINISHED, strategy.stage(at(100_000)))
    }

    @Test fun `remaining thresholds use current leg and handle thresholds longer than timer`() {
        val strategy = ReminderStrategy(TriggerMode.REMAINING)
        assertEquals(ReminderStage.QUIET, strategy.stage(at(0)))
        assertEquals(ReminderStage.WARM, strategy.stage(at(40_000)))
        assertEquals(ReminderStage.WARM, strategy.stage(at(79_999)))
        assertEquals(ReminderStage.URGENT, strategy.stage(at(80_000)))
        assertEquals(ReminderStage.URGENT, strategy.stage(at(0, 10_000)))
        val first = Countdown(60_000, 0)
        val extended = first.extendAt(80_000, 120_000)!!
        assertEquals(ReminderStage.QUIET, strategy.stage(ReminderSnapshot.at(extended, 80_000)))
        assertEquals(ReminderStage.URGENT, strategy.stage(ReminderSnapshot.at(extended, 180_000)))
    }

    @Test fun `mixed conditions use OR and higher level wins even if nodes are crossed`() {
        val strategy = ReminderStrategy(TriggerMode.MIXED,
            ReminderNode(50, 60, useProgress = true, useRemaining = false),
            ReminderNode(90, 20, useProgress = false, useRemaining = true))
        assertEquals(ReminderStage.QUIET, strategy.stage(at(49_999)))
        assertEquals(ReminderStage.WARM, strategy.stage(at(50_000)))
        assertEquals(ReminderStage.URGENT, strategy.stage(at(80_000)))
        val crossed = strategy.copy(warm = strategy.warm.copy(percent = 95))
        assertEquals(ReminderStage.URGENT, crossed.stage(at(80_000)))
        assertEquals(ReminderStage.URGENT, crossed.stage(at(95_000)))
        val either = strategy.copy(urgent = strategy.urgent.copy(useProgress = true, percent = 70))
        assertEquals(ReminderStage.URGENT, either.stage(at(70_000)))
    }

    @Test fun `fixed strategies never decrease during a countdown and both effects follow same nodes`() {
        for (mode in TriggerMode.entries) {
            val strategy = ReminderStrategy(mode, ReminderNode(70, 15, true, true), ReminderNode(40, 20, true, true))
            val frames = (0L..100_000 step 100L).map { at(it) }
            assertTrue(frames.map { strategy.stage(it).ordinal }.zipWithNext().all { (a, b) -> b >= a })
            assertTrue(frames.map { strategy.effectProgress(it) }.zipWithNext().all { (a, b) -> b >= a })
            val settings = ReminderSettings(colorEnabled = true, sizeEnabled = true, colorMode = ChangeMode.STEPS,
                sizeMode = ChangeMode.STEPS, strategy = strategy)
            val urgent = ReminderPresenter.present(at(80_000), settings)
            assertEquals(ReminderPresenter.RED, urgent.colorArgb)
            assertEquals(1.4f, urgent.size, .00001f)
            assertTrue(urgent.pulseDepth > 0)
        }
    }

    @Test fun `disabled mixed nodes never trigger but completion and stopwatch keep their rules`() {
        val off = ReminderNode(0, 10_800, false, false)
        val strategy = ReminderStrategy(TriggerMode.MIXED, off, off)
        assertEquals(ReminderStage.QUIET, strategy.stage(at(99_999)))
        assertEquals(ReminderStage.FINISHED, strategy.stage(at(110_000)))
        val stopwatch = ReminderSnapshot.at(Countdown(0, 0, TimerMode.STOPWATCH), 500_000)
        assertEquals(ReminderStage.QUIET, strategy.stage(stopwatch))
        assertEquals(0f, strategy.effectProgress(stopwatch), 0f)
    }

    @Test fun `strategies round trip with presets and invalid fields retain other saved values`() {
        val strategy = ReminderStrategy(TriggerMode.MIXED, ReminderNode(40, 120, false, true), ReminderNode(90, 20, true, true))
        val settings = ReminderSettings(strategy = strategy)
        assertEquals(settings, ReminderSettingsCodec.decode(ReminderSettingsCodec.encode(settings)))
        val preset = TimerPreset("strategy", "测试", 5, settings)
        assertEquals(preset, TimerPresetCodec.decode(TimerPresetCodec.encode(preset)))
        val broken = ReminderSettingsCodec.decode(ReminderSettingsCodec.encode(settings) +
            mapOf("warmPercent" to 100, "urgentSeconds" to Double.NaN))
        assertEquals(50, broken.strategy.warm.percent)
        assertEquals(20, broken.strategy.urgent.seconds)
        assertEquals(120, broken.strategy.warm.seconds)
        assertEquals(strategy.mode, broken.strategy.mode)
        assertEquals(ReminderStrategy(), ReminderSettingsCodec.decode(emptyMap<String, Any>()).strategy)
    }
}
