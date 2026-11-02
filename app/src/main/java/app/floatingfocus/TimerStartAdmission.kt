package app.floatingfocus

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread reservation shared by manual and App timer starts, before the service is active. */
class TimerStartAdmission {
    private val mutablePending = MutableStateFlow<String?>(null)
    val pending = mutablePending.asStateFlow()
    val pendingId: String? get() = mutablePending.value
    fun reserve(id: String, active: Boolean): Boolean {
        if (active || pendingId != null) return false
        mutablePending.value = id
        return true
    }
    fun complete(id: String?) { if (owns(id)) mutablePending.value = null }
    fun owns(id: String?): Boolean = id != null && pendingId == id
}
