package app.floatingfocus

enum class HomeEntry(val id: String, val label: String) {
    TIMER("timer", "计时"), PRESETS("presets", "预设"), APPS("apps", "App")
}

/** Navigation identity is independent of timer selections, saved presets and their order. */
object HomeEntries {
    val defaults = HomeEntry.entries.toList()

    fun decode(raw: String?): List<HomeEntry> {
        val ids = raw?.split(',') ?: return defaults
        val entries = ids.mapNotNull { id -> HomeEntry.entries.find { it.id == id } }
        return entries.takeIf { ids.size == defaults.size && it.size == defaults.size && it.distinct().size == defaults.size }
            ?: defaults
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
        order.find { it.id == restoredId } ?: order.first()
}
