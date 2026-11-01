package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class HomeNavigationTest {
    @Test fun `first use keeps all three entrances in original order`() {
        assertEquals(listOf(HomeEntry.TIMER, HomeEntry.PRESETS, HomeEntry.APPS), HomeEntries.decode(null))
    }

    @Test fun `every user order round trips without changing entry identities`() {
        HomeEntry.entries.forEach { first ->
            HomeEntry.entries.filter { it != first }.forEach { second ->
                val order = listOf(first, second, HomeEntry.entries.single { it != first && it != second })
                assertEquals(order, HomeEntries.decode(HomeEntries.encode(order)))
                assertEquals(first, HomeEntries.selected(order, null))
            }
        }
    }

    @Test fun `incomplete duplicate or unknown stored entries cannot hide an entrance`() {
        for (raw in listOf("", "timer", "timer,presets", "timer,timer,apps", "timer,presets,other", "timer,presets,apps,extra")) {
            assertEquals(HomeEntries.defaults, HomeEntries.decode(raw))
        }
    }

    @Test fun `drag moves one identity and preserves the order of the other entrances`() {
        val moved = HomeEntries.move(HomeEntries.defaults, HomeEntry.APPS, 0)
        assertEquals(listOf(HomeEntry.APPS, HomeEntry.TIMER, HomeEntry.PRESETS), moved)
        assertEquals(HomeEntries.defaults, HomeEntries.move(moved, HomeEntry.APPS, 2))
        assertEquals(3, moved.distinct().size)
    }

    @Test fun `sorting never changes the currently selected identity`() {
        val moved = HomeEntries.move(HomeEntries.defaults, HomeEntry.APPS, 0)
        assertEquals(HomeEntry.PRESETS, HomeEntries.selected(moved, HomeEntry.PRESETS.id))
        assertEquals(HomeEntry.APPS, HomeEntries.selected(moved, null))
    }

    @Test fun `invalid restored selection falls back to the saved first entrance`() {
        val order = listOf(HomeEntry.PRESETS, HomeEntry.APPS, HomeEntry.TIMER)
        assertEquals(HomeEntry.PRESETS, HomeEntries.selected(order, "obsolete"))
        assertEquals(order, HomeEntries.move(order, HomeEntry.TIMER, -1))
        assertEquals(order, HomeEntries.move(order, HomeEntry.TIMER, 3))
    }
}
