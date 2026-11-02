package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class HomeNavigationTest {
    @Test fun `first use has timer and presets only`() {
        assertEquals(listOf(HomeEntry.TIMER, HomeEntry.PRESETS), HomeEntries.decode(null))
        assertEquals(2, HomeEntry.entries.size)
    }

    @Test fun `both new orders round trip and retain their first entrance`() {
        for (order in listOf(listOf(HomeEntry.TIMER, HomeEntry.PRESETS), listOf(HomeEntry.PRESETS, HomeEntry.TIMER))) {
            assertEquals(order, HomeEntries.decode(HomeEntries.encode(order)))
            assertEquals(order.first(), HomeEntries.selected(order, null))
        }
    }

    @Test fun `six legacy orders map apps to timer and keep first occurrence`() {
        val timerFirst = listOf(HomeEntry.TIMER, HomeEntry.PRESETS)
        val presetsFirst = timerFirst.reversed()
        val cases = mapOf(
            "timer,presets,apps" to timerFirst, "timer,apps,presets" to timerFirst,
            "presets,timer,apps" to presetsFirst, "presets,apps,timer" to presetsFirst,
            "apps,timer,presets" to timerFirst, "apps,presets,timer" to timerFirst)
        cases.forEach { (raw, expected) -> assertEquals(raw, expected, HomeEntries.decode(raw)) }
    }

    @Test fun `legacy restored apps opens merged timer even when presets is first`() {
        assertEquals(HomeEntry.TIMER, HomeEntries.selected(listOf(HomeEntry.PRESETS, HomeEntry.TIMER), "apps"))
    }

    @Test fun `malformed stored order cannot remove or duplicate an entrance`() {
        for (raw in listOf("", "timer", "apps", "timer,apps", "timer,timer", "timer,presets,other", "timer,presets,apps,extra", "timer,timer,apps")) {
            assertEquals(raw, HomeEntries.defaults, HomeEntries.decode(raw))
        }
    }

    @Test fun `reordering two entries preserves current identity`() {
        val moved = HomeEntries.move(HomeEntries.defaults, HomeEntry.PRESETS, 0)
        assertEquals(listOf(HomeEntry.PRESETS, HomeEntry.TIMER), moved)
        assertEquals(HomeEntry.TIMER, HomeEntries.selected(moved, HomeEntry.TIMER.id))
        assertEquals(HomeEntries.defaults, HomeEntries.move(moved, HomeEntry.PRESETS, 1))
    }

    @Test fun `invalid restored selection falls back to saved first entry`() {
        val order = listOf(HomeEntry.PRESETS, HomeEntry.TIMER)
        assertEquals(HomeEntry.PRESETS, HomeEntries.selected(order, "obsolete"))
        assertEquals(order, HomeEntries.move(order, HomeEntry.TIMER, -1))
        assertEquals(order, HomeEntries.move(order, HomeEntry.TIMER, 2))
    }
}
