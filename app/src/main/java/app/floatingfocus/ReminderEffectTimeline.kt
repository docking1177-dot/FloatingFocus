package app.floatingfocus

/** Bounded reminder effects use actual elapsed time, independent of UI transition scale. */
internal object ReminderEffectTimeline {
    const val DURATION_MILLIS=1600
    fun progress(elapsedMillis: Long): Float = (elapsedMillis.coerceAtLeast(0).toDouble()/DURATION_MILLIS).coerceIn(0.0,1.0).toFloat()
}
