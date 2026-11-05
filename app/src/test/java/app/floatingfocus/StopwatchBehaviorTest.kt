package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class StopwatchBehaviorTest {
    @Test fun legacyAndInvalidValuesStayContinuous() {
        for (value in listOf(null, "unknown", true, 1))
            assertEquals(StopwatchBehavior.CONTINUOUS, StopwatchBehavior.decode(value))
        assertEquals(StopwatchBehavior.SCREEN_ON, StopwatchBehavior.decode("SCREEN_ON"))
    }

    @Test fun countdownNeverUsesScreenDrivenBehaviorAndPresetOverridesCurrent() {
        assertEquals(StopwatchBehavior.CONTINUOUS, StopwatchBehavior.resolve(TimerMode.COUNTDOWN,
            StopwatchBehavior.SCREEN_ON, StopwatchBehavior.SCREEN_ON))
        assertEquals(StopwatchBehavior.CONTINUOUS, StopwatchBehavior.resolve(TimerMode.STOPWATCH,
            StopwatchBehavior.SCREEN_ON, StopwatchBehavior.CONTINUOUS))
        assertEquals(StopwatchBehavior.SCREEN_ON, StopwatchBehavior.resolve(TimerMode.STOPWATCH,
            StopwatchBehavior.CONTINUOUS, StopwatchBehavior.SCREEN_ON))
        assertEquals(StopwatchBehavior.SCREEN_ON, StopwatchBehavior.resolve(TimerMode.STOPWATCH,
            StopwatchBehavior.SCREEN_ON))
    }

    @Test fun presetRoundTripAndMissingFieldDoNotFollowCurrentPreference() {
        val saved = TimerPreset("preset", "亮屏", 15, ReminderSettings(), mode = TimerMode.STOPWATCH,
            stopwatchBehavior = StopwatchBehavior.SCREEN_ON)
        val row = TimerPresetCodec.encode(saved)
        assertEquals(saved, TimerPresetCodec.decode(row))
        assertEquals(StopwatchBehavior.CONTINUOUS,
            TimerPresetCodec.decode(row - "stopwatchBehavior")!!.stopwatchBehavior)
        assertEquals(StopwatchBehavior.CONTINUOUS,
            TimerPresetCodec.decode(row + ("stopwatchBehavior" to "invalid"))!!.stopwatchBehavior)
    }
}
