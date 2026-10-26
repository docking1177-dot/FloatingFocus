package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class QuickDurationsTest {
    @Test fun `four independent ordered shortcuts retain custom values`() {
        val values = listOf(2, 45, 7, 180)
        assertTrue(QuickDurations.valid(values))
        assertEquals(values, QuickDurations.decode(values.joinToString(",")))
    }
    @Test fun `corrupt or ambiguous shortcuts restore original defaults`() {
        for (raw in listOf(null, "", "5,10,15", "5,10,15,30,wrong", "0,10,15,30", "5,5,15,30", "181,10,15,30", "5,10,word,30"))
            assertEquals(QuickDurations.defaults, QuickDurations.decode(raw))
    }
}
