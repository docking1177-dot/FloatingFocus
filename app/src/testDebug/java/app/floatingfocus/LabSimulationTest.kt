package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class LabSimulationTest {
    @Test fun `lab strategy overrides are isolated from both production switches`() {
        val shared = ReminderSettings(colorEnabled = true, sizeEnabled = true)
        val confirm = shared.forLabStyle(ReminderStyle.CONFIRM)
        assertFalse(confirm.colorEnabled)
        assertFalse(confirm.sizeEnabled)
        assertTrue(shared.colorEnabled && shared.sizeEnabled)
        assertTrue(shared.forLabStyle(ReminderStyle.SIZE).sizeEnabled)
        assertFalse(shared.forLabStyle(ReminderStyle.SIZE).colorEnabled)
    }
    @Test fun `seeking across zero preserves the independent production clock`() {
        val real = Countdown(900_000, 500)
        val lab = LabSimulation().seek(-60_000)
        assertEquals(60_000L, lab.snapshot.overtimeMillis)
        assertEquals(ReminderStage.FINISHED, lab.snapshot.stage)
        assertEquals(899_000L, real.remainingAt(1500))
        assertEquals(900_000L, LabSimulation().signedRemainingMillis)
    }
    @Test fun `playback reuses elapsed clock and repeated samples never advance twice`() {
        val lab = LabSimulation(60_000, 1000).play(50_000)
        val first = lab.tick(52_000)
        assertEquals(-1000L, first.signedRemainingMillis)
        assertEquals(first, first.tick(52_000))
        assertEquals(-101_000L, first.tick(152_000).signedRemainingMillis)
    }
    @Test fun `seeking stops playback and resets acknowledgement`() {
        val lab = LabSimulation(settings = ReminderSettings(style = ReminderStyle.CONFIRM)).seek(180_000).confirm().play(1_000)
        assertFalse(lab.appearance.confirmationRequired)
        val changed = lab.seek(0)
        assertNull(changed.playback)
        assertNull(changed.acknowledgedStage)
        assertTrue(changed.appearance.confirmationRequired)
    }
    @Test fun `confirmation survives playback within a stage and reappears next stage`() {
        val lab = LabSimulation(10_000, 5000, ReminderSettings(style = ReminderStyle.CONFIRM)).confirm().play(10_000)
        assertFalse(lab.tick(11_000).appearance.confirmationRequired)
        assertTrue(lab.tick(13_000).appearance.confirmationRequired)
    }
    @Test fun `reset value restores every simulation parameter`() {
        val changed = LabSimulation().seek(-1).copy(settings = ReminderSettings(style = ReminderStyle.SIZE, opacity = 0.4f)).confirm()
        assertNotEquals(LabSimulation(), changed)
        val reset = LabSimulation()
        assertEquals(ReminderSettings(), reset.settings)
        assertNull(reset.playback)
        assertNull(reset.acknowledgedStage)
        assertEquals(900_000L, reset.snapshot.remainingMillis)
    }
    @Test fun `invalid times are rejected and seek bounds are safe`() {
        assertThrows(IllegalArgumentException::class.java) { LabSimulation(durationMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) { LabSimulation(signedRemainingMillis = 900_001) }
        assertEquals(900_000L, LabSimulation().seek(Long.MAX_VALUE).signedRemainingMillis)
        assertEquals(-Countdown.MAX_DURATION_MILLIS, LabSimulation().seek(Long.MIN_VALUE).signedRemainingMillis)
    }
}
