package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class TimerTaskPolicyTest {
    @Test fun `finishing a controlled entry task does not stop its newly started session`() {
        assertFalse(TimerTaskPolicy.endsSession("app.floatingfocus.WidgetStartActivity"))
        assertFalse(TimerTaskPolicy.endsSession("app.floatingfocus.PresetShortcutActivity"))
    }
    @Test fun `removing the real app task or an unknown task keeps the existing stop rule`() {
        assertFalse(TimerTaskPolicy.endsSession("app.floatingfocus.MainActivity"))
        assertFalse(TimerTaskPolicy.endsSession("app.floatingfocus.SettingsActivity"))
        assertFalse(TimerTaskPolicy.endsSession(null))
        assertFalse(TimerTaskPolicy.endsSession("unknown.Activity"))
    }
}
