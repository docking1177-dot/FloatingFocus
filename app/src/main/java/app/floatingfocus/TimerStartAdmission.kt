package app.floatingfocus

/** Main-thread reservation shared by manual and App timer starts, before the service is active. */
class TimerStartAdmission {
    var pendingId: String? = null
        private set
    fun reserve(id: String, active: Boolean): Boolean {
        if (active || pendingId != null) return false
        pendingId = id
        return true
    }
    fun complete(id: String?) { if (owns(id)) pendingId = null }
    fun owns(id: String?): Boolean = id != null && pendingId == id
}
