package app.floatingfocus

data class LaunchableApp(val packageName: String, val label: String)

enum class QuickLaunchLayout { LIST, GRID, STRIP }

object QuickLaunchTargets {
    /** An explicitly saved empty list must not resurrect the legacy single selection. */
    fun restore(saved: List<LaunchableApp>?, legacy: LaunchableApp?): List<LaunchableApp> =
        (saved ?: listOfNotNull(legacy)).filter { it.packageName.isNotBlank() }
            .distinctBy { it.packageName }.map { if (it.label.isBlank()) it.copy(label = it.packageName) else it }

    /** Retain shortcut order and unavailable selections; append newly chosen apps in catalog order. */
    fun applySelection(current: List<LaunchableApp>, catalog: List<LaunchableApp>, selected: Set<String>): List<LaunchableApp> {
        val available = catalog.associateBy { it.packageName }
        return restore(current.map { available[it.packageName] ?: it } + catalog, null)
            .filter { it.packageName in selected }
    }
}
