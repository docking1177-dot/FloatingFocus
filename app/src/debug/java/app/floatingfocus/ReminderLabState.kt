package app.floatingfocus

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Process-local, memory-only sandbox. No references to FloatingService or persistent storage. */
internal object ReminderLabState {
    var simulation by mutableStateOf(LabSimulation())
    var animationRequest by mutableIntStateOf(0)
    var overlayVisible by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    fun reset(settings: ReminderSettings = ReminderSettings()) {
        simulation = LabSimulation(settings = settings)
        animationRequest = 0
        message = null
    }
}
