package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class OvertimeReminderRequestTest {
    private val key = OvertimeKey("live", 42)
    private val current = OvertimeContext(key, true)
    private val settings = OvertimeReminderSettings(enabled = true, effects = OvertimeEffect.entries.toSet(),
        limit = OvertimeLimit.NOTICEABLE)
    private val request = OvertimeSnoozeRequest(key, 180, "operation-1")
    private val cue = OvertimeCue(1, OvertimeEffect.entries.toSet(), OvertimeLimit.NOTICEABLE,
        4, 3, 480_000, 1_380_000, 900_000)

    @Test fun oldSessionOrOldLegCannotRescheduleCurrentSession() {
        assertTrue(request.accepts(current, settings))
        assertFalse(request.accepts(current.copy(key = OvertimeKey("new", 42)), settings))
        assertFalse(request.accepts(current.copy(key = OvertimeKey("live", 43)), settings))
        assertFalse(request.accepts(current.copy(key = null), settings))
    }

    @Test fun nonOvertimePausedDisabledAndInvalidRequestsAreRejected() {
        assertFalse(request.accepts(current.copy(inOvertime = false), settings))
        assertFalse(request.accepts(current.copy(paused = true), settings))
        assertFalse(request.accepts(current, settings.copy(enabled = false)))
        assertFalse(request.copy(seconds = 59).accepts(current, settings))
        assertFalse(request.copy(seconds = 10801).accepts(current, settings))
        assertFalse(request.copy(operationId = "").accepts(current, settings))
    }

    @Test fun liveMuteRemovesOnlySelectedFeedbackAndNeverAddsEffects() {
        val soundOff = settings.copy(effects = settings.effects - OvertimeEffect.SOUND)
        val filtered = OvertimeFeedbackPolicy.filter(cue, current, soundOff)
        assertFalse(OvertimeEffect.SOUND in filtered.effects)
        assertTrue(OvertimeEffect.VIBRATION in filtered.effects)
        val vibrationOff = settings.copy(effects = settings.effects - OvertimeEffect.VIBRATION)
        assertTrue(OvertimeEffect.SOUND in OvertimeFeedbackPolicy.filter(cue, current, vibrationOff).effects)
        assertFalse(OvertimeEffect.VIBRATION in OvertimeFeedbackPolicy.filter(cue, current, vibrationOff).effects)
        val visualCue = cue.copy(effects = setOf(OvertimeEffect.OUTLINE))
        assertEquals(setOf(OvertimeEffect.OUTLINE), OvertimeFeedbackPolicy.filter(visualCue, current, settings).effects)
    }

    @Test fun loweringCapImmediatelyRemovesAudioAndBoundsVisualStrength() {
        val low = OvertimeFeedbackPolicy.filter(cue, current, settings.copy(limit = OvertimeLimit.LIGHT))
        assertEquals(OvertimeLimit.LIGHT, low.level)
        assertTrue(low.effects.all { it.visual })
        assertTrue(OvertimeFeedbackPolicy.filter(cue, current, settings.copy(enabled = false)).effects.isEmpty())
    }

    @Test fun fullC4AndItsTailCannotReceiveIncompatibleCurrentCue() {
        val full = current.copy(c4 = true, c4Expanded = true)
        assertEquals(setOf(OvertimeEffect.SOUND, OvertimeEffect.VIBRATION),
            OvertimeFeedbackPolicy.filter(cue, full, settings).effects)
        assertEquals(setOf(OvertimeEffect.VIBRATION),
            OvertimeFeedbackPolicy.filter(cue, full.copy(c4AudioBusy = true), settings).effects)
        assertTrue(OvertimeFeedbackPolicy.filter(cue, current.copy(inOvertime = false), settings).effects.isEmpty())
    }
}
