package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class TimerPresetTest {
    @Test fun `full appearance and duration round trip without sharing mutable state`() {
        val settings = ReminderSettings(ReminderStyle.SIZE, ChangeMode.STEPS, ChangeMode.CONTINUOUS,
            .65f, 1.4f, .45f, OverlayColor.BLUE, OverlayShape.VERTICAL_CAPSULE)
        val original = TimerPreset("id-1", "短视频 30 分钟", 30, settings)
        assertEquals(original, TimerPresetCodec.decode(TimerPresetCodec.encode(original)))
        val changed = original.copy(minutes = 5, settings = settings.copy(opacity = 1f))
        assertEquals(30, original.minutes)
        assertEquals(.45f, original.settings.opacity, 0f)
        assertNotEquals(original, changed)
    }

    @Test fun `edit and rename replace only the matching identity`() {
        val one = TimerPreset("one", "方案", 15, ReminderSettings())
        val two = one.copy(id = "two", minutes = 30)
        val renamed = one.copy(name = "新名称", minutes = 45)
        val result = TimerPresetCodec.upsert(listOf(one, two), renamed)
        assertEquals(listOf(renamed, two), result)
        assertEquals(listOf(one, two), TimerPresetCodec.upsert(listOf(one), two))
    }

    @Test fun `invalid records are rejected while valid legacy appearance survives`() {
        val row = TimerPresetCodec.encode(TimerPreset("id", "方案", 30, ReminderSettings()))
        for (value in listOf(0, -1, 181, 30.5, Double.NaN, "30"))
            assertNull(TimerPresetCodec.decode(row + ("minutes" to value)))
        assertNull(TimerPresetCodec.decode(row + ("id" to "")))
        assertNull(TimerPresetCodec.decode(row + ("name" to "   ")))
        assertNull(TimerPresetCodec.decode(row - "settings"))
        assertEquals(OverlayShape.ROUNDED_RECTANGLE,
            TimerPresetCodec.decode(row + ("settings" to mapOf("shape" to "ROUNDED_RECTANGLE")))!!.settings.shape)
        assertEquals(ReminderSettings(), TimerPresetCodec.decode(row + ("settings" to emptyMap<String, Any>()))!!.settings)
    }
}
