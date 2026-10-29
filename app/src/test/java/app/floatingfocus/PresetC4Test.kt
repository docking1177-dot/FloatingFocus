package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class PresetC4Test {
    private fun legacy() = TimerPresetCodec.encode(TimerPreset("stable", "保存方案", 5,
        ReminderSettings(color = OverlayColor.BLUE), targetApp = LaunchableApp("example.app", "App"),
        lastPosition = OverlayAnchor(.8f, .2f), durationSeconds = 125)) - "c4Enabled"

    @Test fun `legacy presets migrate explicitly to C4 off without changing other fields`() {
        val old = legacy()
        val migrated = TimerPresetCodec.encode(TimerPresetCodec.decode(old)!!)
        assertEquals(false, migrated["c4Enabled"])
        assertEquals(old, migrated - "c4Enabled")
    }

    @Test fun `both explicit C4 states survive persistence without a sound snapshot`() {
        for (enabled in listOf(false, true)) {
            val row = legacy() + ("c4Enabled" to enabled)
            val decoded = TimerPresetCodec.decode(row)!!
            assertEquals(row, TimerPresetCodec.encode(decoded))
            assertFalse(TimerPresetCodec.encode(decoded).containsKey("sound"))
            assertFalse((TimerPresetCodec.encode(decoded)["settings"] as Map<*, *>).keys.any { it.toString().contains("c4", true) })
        }
    }

    @Test fun `malformed C4 values are never interpreted as enabled or follow global`() {
        for (value in listOf("true", 1, null)) {
            val decoded = TimerPresetCodec.decode(legacy() + ("c4Enabled" to value))!!
            assertEquals(false, TimerPresetCodec.encode(decoded)["c4Enabled"])
        }
    }

    @Test fun `preset enable overrides current enable while current launches follow it`() {
        for (global in listOf(false, true)) {
            for (sound in listOf(false, true)) {
                val current = C4Settings(enabled = global, sound = sound)
                assertEquals(global, current.appliesTo(TimerMode.COUNTDOWN))
                for (saved in listOf(false, true)) {
                    assertEquals(saved, current.appliesTo(TimerMode.COUNTDOWN, saved))
                    assertFalse(current.appliesTo(TimerMode.STOPWATCH, saved))
                    assertEquals(sound, current.sound)
                }
            }
        }
    }

    @Test fun `editing a C4 draft cannot change another preset or previous value`() {
        val one = TimerPresetCodec.decode(legacy())!!
        val two = one.copy(id = "other", c4Enabled = true)
        val changed = one.copy(c4Enabled = true)
        assertFalse(one.c4Enabled)
        assertEquals(listOf(changed, two), TimerPresetCodec.upsert(listOf(one, two), changed))
        assertEquals(one.lastPosition, changed.lastPosition)
    }
}
