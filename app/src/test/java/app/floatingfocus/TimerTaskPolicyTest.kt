package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class TimerTaskPolicyTest {
    @Test fun `finishing a controlled entry task does not stop its newly started session`() {
        assertFalse(TimerTaskPolicy.endsSession("app.floatingfocus.WidgetStartActivity"))
        assertFalse(TimerTaskPolicy.endsSession("app.floatingfocus.PresetShortcutActivity"))
    }
    @Test fun `removing the real app task or an unknown task keeps the existing stop rule`() {
        assertTrue(TimerTaskPolicy.endsSession("app.floatingfocus.MainActivity"))
        assertTrue(TimerTaskPolicy.endsSession("app.floatingfocus.SettingsActivity"))
        assertTrue(TimerTaskPolicy.endsSession(null))
        assertTrue(TimerTaskPolicy.endsSession("unknown.Activity"))
    }
}
