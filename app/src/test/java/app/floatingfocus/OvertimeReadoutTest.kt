package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class OvertimeReadoutTest {
    @Test fun `fifteen planned plus five overtime shows twenty total without summing overtime twice`() {
        val p = ReminderPresenter.present(ReminderSnapshot.at(Countdown(900_000, 0), 1_200_000))
        assertEquals(OvertimeReadout(1_200_000, 300_000), p.overtime)
        assertEquals("20:00", p.overtime!!.totalText)
        assertEquals("+05:00", p.overtime!!.overtimeText)
    }

    @Test fun `multiple added legs retain all used time while overtime still describes the current leg`() {
        val one = Countdown(900_000, 0).extendAt(1_200_000, 120_000)!!
        val two = one.extendAt(1_380_000, 60_000)!!
        val p = ReminderPresenter.present(ReminderSnapshot.at(two, 1_470_000))
        assertEquals(1_470_000L, p.overtime!!.totalMillis)
        assertEquals(30_000L, p.overtime!!.overtimeMillis)
        assertEquals("24:30", p.overtime!!.totalText)
    }

    @Test fun `paused session keeps the same used time and resumes without counting the pause`() {
        val clock = SessionClock()
        val timer = Countdown(10_000, 0)
        clock.pause(15_000)
        val paused = ReminderPresenter.present(ReminderSnapshot.at(timer, clock.at(200_000)))
        assertEquals("00:15", paused.overtime!!.totalText)
        clock.resume(215_000)
        assertEquals("00:20", ReminderPresenter.present(ReminderSnapshot.at(timer, clock.at(220_000))).overtime!!.totalText)
    }

    @Test fun `normal countdown stopwatch and C4 do not consume normal overtime layout data`() {
        assertNull(ReminderPresenter.present(ReminderSnapshot.at(Countdown(60_000, 0), 5000)).overtime)
        assertNull(ReminderPresenter.present(ReminderSnapshot.at(Countdown(0, 0, TimerMode.STOPWATCH), 80_000)).overtime)
        assertNull(ReminderPresenter.forC4(C4Frame(C4Phase.OVERTIME, overtimeMillis = 90_000)).overtime)
        assertEquals(OvertimeReadout(60_000, 0), ReminderPresenter.present(ReminderSnapshot.at(Countdown(60_000, 0), 60_000)).overtime)
    }

    @Test fun `long sessions include hours without changing original minute format elsewhere`() {
        assertEquals("59:59", formatUsageTime(3_599_999))
        assertEquals("1:00:00", formatUsageTime(3_600_000))
        assertEquals("8:00:00", formatUsageTime(28_800_000))
        assertEquals("60:00", formatElapsed(3_600_000))
    }
}
