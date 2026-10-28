package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class PresetShortcutPolicyTest {
    private val first = TimerPreset("one", "同名", 5, ReminderSettings(), targetApp = LaunchableApp("pkg.one", "App"))
    private fun check(id: String? = "one", items: List<TimerPreset>? = listOf(first), active: Boolean = false,
                      saved: Boolean = true, available: Boolean = true, permission: Boolean = true) =
        PresetLaunchPolicy.check(id, items, active, { saved }, { available }, permission)

    @Test fun `stable identity follows only id and rejects malformed external ids`() {
        assertEquals(PresetShortcutIdentity.shortcutId(first.id), PresetShortcutIdentity.shortcutId(first.copy(name = "改名").id))
        assertNotEquals(PresetShortcutIdentity.shortcutId("one"), PresetShortcutIdentity.shortcutId("two"))
        for (id in listOf(null, "", " ", " one", "one\n", "x".repeat(129))) assertFalse(PresetShortcutIdentity.valid(id))
        assertEquals("one", PresetShortcutIdentity.presetId(PresetShortcutIdentity.shortcutId("one")))
        assertNull(PresetShortcutIdentity.presetId("unrelated"))
    }

    @Test fun `same name and app never substitute for missing identity`() {
        val two = first.copy(id = "two", durationSeconds = 10)
        assertEquals(two, check("two", listOf(first, two)).preset)
        assertEquals(PresetLaunchIssue.MISSING, check("deleted", listOf(first, two)).issue)
        assertEquals(PresetLaunchIssue.READ_FAILED, check(items = null).issue)
        assertEquals(PresetLaunchIssue.MISSING, check(items = emptyList()).issue)
    }

    @Test fun `latest snapshot supplies mode appearance duration target and position`() {
        val latest = first.copy(mode = TimerMode.STOPWATCH, durationSeconds = 33,
            targetApp = LaunchableApp("pkg.two", "New"), settings = first.settings.copy(opacity = .4f), lastPosition = OverlayAnchor(.9f, .1f))
        assertEquals(latest, check(items = listOf(latest)).preset)
        assertNull(check(items = listOf(latest)).issue)
    }

    @Test fun `any active session wins over binding permission and reminder changes`() {
        assertEquals(PresetLaunchIssue.ACTIVE, check(active = true, saved = false, permission = false).issue)
        assertEquals(PresetLaunchIssue.BINDING, check(saved = false).issue)
        assertEquals(PresetLaunchIssue.UNAVAILABLE, check(available = false).issue)
        assertEquals(PresetLaunchIssue.PERMISSION, check(permission = false).issue)
        assertEquals(PresetLaunchIssue.BINDING, check(items = listOf(first.copy(targetApp = null))).issue)
    }

    @Test fun `service admission gap is reserved and failures can release it`() {
        val gate = AppTimerLaunchGate()
        assertTrue(gate.acquire())
        assertFalse(gate.acquire())
        gate.release()
        assertTrue(gate.acquire())
    }

    @Test fun `pin separates unsupported read error missing and broken bindings`() {
        fun pin(items: List<TimerPreset>? = listOf(first), supported: Boolean = true, saved: Boolean = true, available: Boolean = true) =
            PresetLaunchPolicy.forPin("one", items, supported, { saved }, { available })
        assertEquals(PresetLaunchIssue.UNSUPPORTED, pin(supported = false).issue)
        assertEquals(PresetLaunchIssue.READ_FAILED, pin(items = null).issue)
        assertEquals(PresetLaunchIssue.MISSING, pin(items = emptyList()).issue)
        assertEquals(PresetLaunchIssue.BINDING, pin(saved = false).issue)
        assertEquals(PresetLaunchIssue.UNAVAILABLE, pin(available = false).issue)
        assertNull(pin().issue)
    }

    @Test fun `no callback cannot leave pin retry permanently blocked`() {
        val throttle = PresetPinThrottle()
        assertTrue(throttle.acquire(10))
        assertFalse(throttle.acquire(11))
        assertFalse(throttle.acquire(2009))
        assertTrue(throttle.acquire(2010))
        throttle.reset()
        assertTrue(throttle.acquire(2011))
    }

    @Test fun `late pin confirmation uses edited metadata and disables a deleted id only`() {
        val oneId = PresetShortcutIdentity.shortcutId(first.id)
        val twoId = PresetShortcutIdentity.shortcutId("two")
        val edited = first.copy(name = "新名称", targetApp = LaunchableApp("new.pkg", "New"))
        val plan = PresetShortcutSyncPlan.from(listOf(edited), listOf(oneId, twoId, "another-feature"))!!
        assertEquals(listOf(edited), plan.update)
        assertEquals(listOf(twoId), plan.disable)
        assertNull(PresetShortcutSyncPlan.from(null, listOf(oneId, twoId)))
        assertEquals(listOf(oneId, twoId), PresetShortcutSyncPlan.from(emptyList(), listOf(oneId, twoId))!!.disable)
    }
}
