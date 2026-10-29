package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class PresetPinFeedbackTest {
    @Test fun `no callback becomes unconfirmed never cancelled or successful`() {
        val waiting = PresetPinFeedback("stable-id", PresetPinPhase.WAITING, "waiting")
        val result = waiting.afterWaiting(true)
        assertEquals(PresetPinPhase.UNCONFIRMED, result.phase)
        assertEquals("stable-id", result.presetId)
        assertTrue(result.canOpenSettings)
        assertTrue(result.message.contains("桌面快捷方式"))
    }
    @Test fun `timeout cannot overwrite confirmation or rejected request`() {
        for (phase in listOf(PresetPinPhase.CONFIRMED, PresetPinPhase.NOT_SENT, PresetPinPhase.UNSUPPORTED)) {
            val result = PresetPinFeedback("id", phase, "actual result")
            assertSame(result, result.afterWaiting(true))
        }
    }
    @Test fun `device guidance is explicit only for the identified vendor`() {
        assertTrue(PresetPinHelp.isXiaomi("Xiaomi", "Redmi"))
        assertTrue(PresetPinHelp.isXiaomi("xiaomi", "other"))
        assertFalse(PresetPinHelp.isXiaomi("Google", "Pixel"))
        assertTrue(PresetPinHelp.instructions(true).contains("权限管理"))
        assertFalse(PresetPinHelp.instructions(false).contains("小米"))
    }
}
