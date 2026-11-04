package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class OvertimeReminderTest {
    private val enabled = OvertimeReminderSettings(enabled = true)
    private val key = OvertimeKey("session-a", 1000)
    private fun context(overtime: Long = 0, key: OvertimeKey = this.key) = OvertimeContext(
        key, inOvertime = true, overtimeMillis = overtime, elapsedMillis = 900_000 + overtime,
        plannedMillis = 900_000)
    private fun earliest() = OvertimeReminderEngine { 0 }

    @Test fun missingAndMalformedSettingsNeverEnableNewFeedback() {
        val empty = OvertimeReminderCodec.decode(emptyMap<String, Any>())
        assertFalse(empty.enabled)
        assertEquals(300, empty.intervalSeconds)
        assertEquals(setOf(OvertimeEffect.BRIGHTEN, OvertimeEffect.OUTLINE, OvertimeEffect.STATUS), empty.effects)
        val malformed = OvertimeReminderCodec.decode(mapOf("enabled" to "true", "interval_seconds" to -1,
            "effects" to "SOUND_FAKE,UNKNOWN", "limit" to "UNKNOWN"))
        assertFalse(malformed.enabled)
        assertEquals(300, malformed.intervalSeconds)
        assertTrue(malformed.effects.isEmpty())
    }

    @Test fun independentSoundAndVibrationChoicesSurviveRoundTrip() {
        val soundOnly = enabled.copy(effects = setOf(OvertimeEffect.SOUND), interaction = OvertimeInteraction.CHOICE)
        val restored = OvertimeReminderCodec.decode(OvertimeReminderCodec.encode(soundOnly))
        assertEquals(soundOnly, restored)
        assertFalse(OvertimeEffect.VIBRATION in restored.effects)
        assertEquals(emptySet<OvertimeEffect>(), OvertimeReminderCodec.decode(
            OvertimeReminderCodec.encode(enabled.copy(effects = emptySet()))).effects)
    }

    @Test fun eachRoundDrawsDeadlineOnlyOnceWithinTwentyPercent() {
        var draws = 0
        val engine = OvertimeReminderEngine { draws++; 0 }
        assertNull(engine.update(0, context(), enabled))
        assertEquals(240_000L, engine.state.nextAt)
        repeat(30) { engine.update(it * 1000L, context(it * 1000L), enabled) }
        assertEquals(1, draws)
        assertEquals(240_000L, engine.state.nextAt)
        val latest = OvertimeReminderEngine { bound -> bound - 1 }
        latest.update(0, context(), enabled)
        assertEquals(360_000L, latest.state.nextAt)
    }

    @Test fun lateWakeConsumesOneRoundAndDoesNotCatchUp() {
        val engine = earliest()
        engine.update(0, context(), enabled)
        val cue = engine.update(3_600_000, context(3_600_000), enabled)!!
        assertEquals(1, cue.round)
        assertEquals(OvertimeLimit.LIGHT, cue.level)
        assertEquals(3_840_000L, engine.state.nextAt)
        assertNull(engine.update(3_600_000, context(3_600_000), enabled))
    }

    @Test fun firstRoundNeverUsesSoundOrVibrationEvenWhenEverythingAllowed() {
        val engine = earliest()
        val settings = enabled.copy(effects = OvertimeEffect.entries.toSet(), limit = OvertimeLimit.NOTICEABLE)
        engine.update(0, context(), settings)
        val first = engine.update(240_000, context(240_000), settings)!!
        assertEquals(1, first.effects.size)
        assertTrue(first.effects.all { it.visual })
    }

    @Test fun lightOnlySoundConfigurationSkipsRatherThanEnablingOtherEffects() {
        val engine = earliest()
        val settings = enabled.copy(effects = setOf(OvertimeEffect.SOUND), limit = OvertimeLimit.LIGHT)
        engine.update(0, context(), settings)
        val cue = engine.update(240_000, context(240_000), settings)!!
        assertTrue(cue.effects.isEmpty())
        assertEquals(480_000L, engine.state.nextAt)
    }

    @Test fun sustainedOvertimeAllowsPermittedAudioButCapStillApplies() {
        val engine = earliest()
        val settings = enabled.copy(effects = setOf(OvertimeEffect.SOUND, OvertimeEffect.VIBRATION))
        engine.update(0, context(), settings)
        engine.update(240_000, context(240_000), settings)
        val second = engine.update(900_000, context(900_000), settings)!!
        assertEquals(OvertimeLimit.STANDARD, second.level)
        assertTrue(second.effects.isNotEmpty())
        assertTrue(second.effects.size <= 2)
        val capped = earliest()
        val low = settings.copy(limit = OvertimeLimit.LIGHT)
        capped.update(0, context(), low)
        capped.update(240_000, context(240_000), low)
        assertTrue(capped.update(900_000, context(900_000), low)!!.effects.isEmpty())
    }

    @Test fun recentEffectsHaveLowerPriorityButRemainPossible() {
        val random = Random(71)
        var bright = 0
        var outline = 0
        repeat(3000) {
            val effect = OvertimeRotation.select(setOf(OvertimeEffect.BRIGHTEN, OvertimeEffect.OUTLINE),
                listOf(setOf(OvertimeEffect.BRIGHTEN)), 1, random::nextInt).single()
            if (effect == OvertimeEffect.BRIGHTEN) bright++ else outline++
        }
        assertTrue("Recent effect should remain eligible", bright > 100)
        assertTrue("Unused effect should be preferred", outline > bright * 2)
    }

    @Test fun oneEffectMayRepeatAndTwoEffectCombinationHasNoDuplicates() {
        assertEquals(setOf(OvertimeEffect.STATUS), OvertimeRotation.select(setOf(OvertimeEffect.STATUS),
            listOf(setOf(OvertimeEffect.STATUS)), 2) { 0 })
        assertEquals(setOf(OvertimeEffect.BRIGHTEN, OvertimeEffect.OUTLINE), OvertimeRotation.select(
            setOf(OvertimeEffect.BRIGHTEN, OvertimeEffect.OUTLINE), emptyList(), 2) { 0 })
    }

    @Test fun userChoiceIsExactAndDoesNotChangeClockValues() {
        val engine = earliest()
        val before = context(100_000)
        engine.update(0, context(), enabled)
        assertTrue(engine.snooze(100_000, before, enabled, 180, "choice-1"))
        assertEquals(280_000L, engine.state.nextAt)
        assertTrue(engine.state.exact)
        assertEquals(1, engine.state.snoozeCount)
        assertNull(engine.update(279_999, context(279_999), enabled))
        val event = engine.update(280_000, context(280_000), enabled)!!
        assertEquals(1_180_000L, event.elapsedMillis)
        assertEquals(900_000L, event.plannedMillis)
        assertEquals(520_000L, engine.state.nextAt)
        assertFalse(engine.state.exact)
    }

    @Test fun duplicateAndStaleSnoozeRequestsDoNotChangeDeadlineOrCount() {
        val engine = earliest()
        engine.update(0, context(), enabled)
        assertTrue(engine.snooze(0, context(), enabled, 60, "same"))
        assertFalse(engine.snooze(5000, context(5000), enabled, 180, "same"))
        assertFalse(engine.snooze(5000, context(key = OvertimeKey("old", 2)), enabled, 180, "old"))
        assertFalse(engine.snooze(5000, context(), enabled, 59, "bad-duration"))
        assertEquals(60_000L, engine.state.nextAt)
        assertEquals(1, engine.state.snoozeCount)
    }

    @Test fun threeExplicitChoicesCanRaiseLevelButNotAboveCap() {
        val engine = earliest()
        val settings = enabled.copy(limit = OvertimeLimit.NOTICEABLE)
        engine.update(0, context(), settings)
        engine.update(240_000, context(240_000), settings)
        repeat(3) { assertTrue(engine.snooze(250_000, context(250_000), settings, 60, "choice-$it")) }
        val cue = engine.update(310_000, context(310_000), settings)!!
        assertEquals(OvertimeLimit.NOTICEABLE, cue.level)
        assertEquals(3, cue.snoozeCount)
        assertEquals(900_000L, cue.plannedMillis)
        assertEquals(1_210_000L, cue.elapsedMillis)
    }

    @Test fun changingBaseIntervalDoesNotAdvanceExplicitChoice() {
        val engine = earliest()
        engine.update(0, context(), enabled)
        engine.snooze(1000, context(1000), enabled, 180, "pick")
        engine.update(2000, context(2000), enabled.copy(intervalSeconds = 60))
        assertEquals(181_000L, engine.state.nextAt)
        engine.update(181_000, context(181_000), enabled.copy(intervalSeconds = 60))
        assertEquals(229_000L, engine.state.nextAt)
    }

    @Test fun automaticDeadlineChangesOnlyWhenIntervalChanges() {
        val engine = earliest()
        engine.update(0, context(), enabled)
        engine.update(1000, context(1000), enabled.copy(limit = OvertimeLimit.LIGHT, effects = emptySet()))
        assertEquals(240_000L, engine.state.nextAt)
        engine.update(2000, context(2000), enabled.copy(intervalSeconds = 60))
        assertEquals(50_000L, engine.state.nextAt)
    }

    @Test fun masterOffCancelsAndReenableStartsFromNow() {
        val engine = earliest()
        engine.update(0, context(), enabled)
        engine.update(50_000, context(50_000), enabled.copy(enabled = false))
        assertNull(engine.state.nextAt)
        assertNull(engine.update(900_000, context(900_000), enabled))
        assertEquals(1_140_000L, engine.state.nextAt)
    }

    @Test fun endingOrLeavingOvertimeCancelsAndNewLegStartsFresh() {
        val engine = earliest()
        engine.update(0, context(), enabled)
        engine.snooze(0, context(), enabled, 60, "old-choice")
        engine.update(10_000, context().copy(inOvertime = false), enabled)
        assertNull(engine.state.nextAt)
        val next = context(key = OvertimeKey("session-a", 20_000))
        assertNull(engine.update(20_000, next, enabled))
        assertEquals(0, engine.state.snoozeCount)
        assertEquals(260_000L, engine.state.nextAt)
        assertFalse(engine.snooze(20_000, context(), enabled, 60, "stale-leg"))
    }

    @Test fun pausedClockDoesNotConsumeOrRearmDueReminder() {
        val engine = earliest()
        engine.update(0, context(), enabled)
        assertNull(engine.update(240_000, context(240_000).copy(paused = true), enabled))
        assertEquals(240_000L, engine.state.nextAt)
        assertNotNull(engine.update(240_000, context(240_000), enabled))
        assertNull(engine.update(240_000, context(240_000), enabled))
    }

    @Test fun c4FullDeviceSkipsVisualsAndBusyAudioSkipsTone() {
        val engine = earliest()
        val settings = enabled.copy(effects = OvertimeEffect.entries.toSet())
        engine.update(0, context().copy(c4 = true), settings)
        assertTrue(engine.update(240_000, context(240_000).copy(c4 = true, c4Expanded = true), settings)!!.effects.isEmpty())
        val cue = engine.update(900_000, context(900_000).copy(c4 = true, c4Expanded = true, c4AudioBusy = true), settings)!!
        assertEquals(setOf(OvertimeEffect.VIBRATION), cue.effects)
        assertNull(engine.update(900_000, context(900_000).copy(c4 = true), settings))
    }

    @Test fun preZeroAndStopwatchCannotCreateSchedule() {
        val engine = earliest()
        assertNull(engine.update(0, context().copy(inOvertime = false), enabled))
        assertNull(engine.state.nextAt)
        assertFalse(engine.snooze(0, context(), enabled, 60, "no-active-overtime"))
    }
}
