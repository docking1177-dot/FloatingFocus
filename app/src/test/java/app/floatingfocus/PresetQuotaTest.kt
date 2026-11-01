package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class PresetQuotaTest {
    private val preset = TimerPreset("stable", "video", 15, ReminderSettings(), targetApp = LaunchableApp("video", "Video"))

    @Test fun oldPresetRemainsFixedAndQuotaSourceRoundTripsWithoutRemainingSnapshot() {
        val old = TimerPresetCodec.encode(preset).toMutableMap().apply { remove("timeSource") }
        assertEquals(PresetTimeSource.FIXED, TimerPresetCodec.decode(old)!!.timeSource)
        val quota = preset.copy(timeSource = PresetTimeSource.APP_DAILY_QUOTA)
        assertEquals(quota, TimerPresetCodec.decode(TimerPresetCodec.encode(quota)))
        assertFalse(TimerPresetCodec.encode(quota).containsKey("remainingQuota"))
    }

    @Test fun quotaCannotTurnAStopwatchIntoCountdownImplicitly() {
        val map = TimerPresetCodec.encode(preset).toMutableMap().apply {
            put("timeSource", "APP_DAILY_QUOTA"); put("mode", "STOPWATCH")
        }
        assertNull(TimerPresetCodec.decode(map))
    }
}
