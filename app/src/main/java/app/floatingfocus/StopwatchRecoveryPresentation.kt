package app.floatingfocus

internal enum class RecoveryOrigin(val label: String) {
    MANUAL("手动计时"), BOUND_APP("绑定 App"), PRESET("预设计时")
}

/** Describe only source information actually persisted in the checkpoint; do not infer launcher entry. */
internal object StopwatchRecoveryPresentation {
    fun origin(record: TimerRecord): RecoveryOrigin = when {
        record.stopwatch?.presetId != null -> RecoveryOrigin.PRESET
        record.packageName.isNotBlank() -> RecoveryOrigin.BOUND_APP
        else -> RecoveryOrigin.MANUAL
    }

    fun name(record: TimerRecord): String = record.appName.takeIf { it.isNotBlank() }
        ?: record.packageName.takeIf { it.isNotBlank() } ?: "手动计时"

    fun selected(records: List<TimerRecord>, id: String?, readable: Boolean): TimerRecord? =
        if (readable && id != null) StopwatchRecovery.source(records, id) else null
}
