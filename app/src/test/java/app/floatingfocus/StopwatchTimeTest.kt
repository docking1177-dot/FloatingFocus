package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class StopwatchTimeTest {
    @Test fun elapsedUsesHoursWithoutDayRolloverOrFloatingPointRounding() {
        for ((seconds,text) in listOf(0L to "00:00",2180L to "36:20",3599L to "59:59",3600L to "01:00:00",
                16580L to "04:36:20",86400L to "24:00:00",359999L to "99:59:59",360000L to "100:00:00",3600000L to "1000:00:00")) {
            assertEquals(text,formatElapsed(seconds*1000))
        }
        assertEquals("59:59",formatElapsed(3599999))
        assertEquals("00:00",formatElapsed(-1))
        assertEquals("2562047788015:12:55",formatElapsed(Long.MAX_VALUE))
    }
    @Test fun nativeWidgetFormattingChangesOnlyAtHourPaddingBoundaries() {
        fun p(ms:Long)=TimerWidgetModel.present(SessionState(active=true,mode=TimerMode.STOPWATCH,elapsedMillis=ms),TimerStartupDefaults(),0)
        assertNull(p(3599999).clockFormat)
        assertEquals("0%s",p(3600000).clockFormat)
        assertEquals("01:00:00",p(3600000).time)
        assertEquals("0%s",p(35999999).clockFormat)
        assertNull(p(36000000).clockFormat)
        assertEquals("100:00:00",p(360000000).time)
        val paused=p(3600000).copy(running=false)
        assertEquals("01:00:00",paused.time)
    }
}
