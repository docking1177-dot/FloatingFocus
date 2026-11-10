package app.floatingfocus

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeSlotTest {
    @Test fun reserveOnlyTheCurrentClockFormat() {
        assertEquals("00:00", timeSlotSample("00:00"))
        assertEquals("00:00", timeSlotSample("59:59"))
        assertEquals("00:00:00", timeSlotSample("01:00:00"))
        assertEquals("00:00:00", timeSlotSample("99:59:59"))
        assertEquals("000:00:00", timeSlotSample("100:00:00"))
        assertEquals("0000:00:00", timeSlotSample("1000:36:20"))
    }
    @Test fun prefixesAndExistingLegacyHoursArePreservedWithoutClipping() {
        assertEquals("累计 00:00", timeSlotSample("累计 08:12"))
        assertEquals("超时 +00:00", timeSlotSample("超时 +00:01"))
        assertEquals("+0:00:00", timeSlotSample("+1:00:00"))
        assertEquals("累计 000:00:00", timeSlotSample("累计 128:09:44"))
    }
}
