package app.floatingfocus

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class StartAdmissionObservableTest {
    @Test fun `start ownership is observable even when an empty session did not change`() = runBlocking {
        val admission = TimerStartAdmission()
        val seen = mutableListOf<String?>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { admission.pending.take(3).toList(seen) }
        assertTrue(admission.reserve("one", false)); yield()
        admission.complete("other"); yield()
        assertEquals(listOf(null, "one"), seen)
        admission.complete("one"); observer.join()
        assertEquals(listOf(null, "one", null), seen)
    }
}
