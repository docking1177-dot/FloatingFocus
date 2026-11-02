package app.floatingfocus

enum class HomeEntry(val id: String, val label: String) {
    TIMER("timer", "计时"), PRESETS("presets", "预设")
}

/** Navigation identity is independent of timer selections, saved presets and their order. */
object HomeEntries {
    val defaults = HomeEntry.entries.toList()

    fun decode(raw: String?): List<HomeEntry> {
        val ids = raw?.split(',') ?: return defaults
        val currentIds = defaults.map { it.id }.toSet()
        val validCurrent = ids.size == currentIds.size && ids.toSet() == currentIds
        val validLegacy = ids.size == currentIds.size + 1 && ids.toSet() == currentIds + "apps"
        if (!validCurrent && !validLegacy) return defaults
        // Preserve the first occurrence: an old App-first home becomes Timer-first.
        return ids.map { id -> HomeEntry.entries.first { it.id == if (id == "apps") "timer" else id } }.distinct()
    }

    fun encode(order: List<HomeEntry>): String {
        require(order.size == defaults.size && order.toSet() == defaults.toSet())
        return order.joinToString(",") { it.id }
    }

    fun move(order: List<HomeEntry>, entry: HomeEntry, targetIndex: Int): List<HomeEntry> {
        if (entry !in order || targetIndex !in order.indices) return order
        return order.toMutableList().apply { remove(entry); add(targetIndex, entry) }
    }

    fun selected(order: List<HomeEntry>, restoredId: String?): HomeEntry =
        order.find { it.id == if (restoredId == "apps") "timer" else restoredId } ?: order.first()
}
