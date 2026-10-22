package app.floatingfocus

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickLaunchTargetsTest {
    private val first = LaunchableApp("one", "第一个")
    private val second = LaunchableApp("two", "第二个")

    @Test fun `legacy target survives upgrade but clearing selection is respected`() {
        assertEquals(listOf(first), QuickLaunchTargets.restore(null, first))
        assertEquals(emptyList<LaunchableApp>(), QuickLaunchTargets.restore(emptyList(), first))
    }
    @Test fun `invalid duplicates are cleaned and blank label has a fallback`() {
        assertEquals(listOf(first, LaunchableApp("two", "two")), QuickLaunchTargets.restore(
            listOf(first, first.copy(label = "duplicate"), LaunchableApp("", "invalid"), LaunchableApp("two", "")), null))
    }
    @Test fun `adding a target retains prior order and refreshes installed labels`() {
        val renamed = first.copy(label = "新名称")
        assertEquals(listOf(renamed, second), QuickLaunchTargets.applySelection(listOf(first), listOf(second, renamed), setOf("one", "two")))
    }
    @Test fun `unavailable target stays removable without losing the other shortcuts`() {
        assertEquals(listOf(first, second), QuickLaunchTargets.applySelection(listOf(first), listOf(second), setOf("one", "two")))
        assertEquals(listOf(second), QuickLaunchTargets.applySelection(listOf(first, second), listOf(second), setOf("two")))
    }
}
