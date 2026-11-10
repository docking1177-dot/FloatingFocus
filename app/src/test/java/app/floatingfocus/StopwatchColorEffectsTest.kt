package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class StopwatchColorEffectsTest {
    private val a = 0xff204060.toInt()
    private val b = 0xffffcc40.toInt()
    private val base = 0xff102030.toInt()

    @Test fun colorTimelineRestoresBaseAndDoesNotChangeOpaqueColorContract() {
        val frames = StopwatchColorSettings(StopwatchColorMode.TRANSITION, a, b).sample()
        assertEquals(listOf(a, b), frames)
        assertEquals(base, StopwatchColorTimeline.color(base, frames, 0f))
        assertEquals(a, StopwatchColorTimeline.color(base, frames, 1f / 3f))
        assertEquals(b, StopwatchColorTimeline.color(base, frames, 2f / 3f))
        assertEquals(base, StopwatchColorTimeline.color(base, frames, 1f))
        (0..100).forEach { assertEquals(255, StopwatchColorTimeline.color(base, frames, it / 100f).ushr(24)) }
        assertEquals(base, StopwatchColorTimeline.color(base, emptyList(), .5f))
    }

    @Test fun alternatingIncludesTwoCompleteOutAndBackCycles() {
        assertEquals(listOf(a, b, a, b, a), StopwatchColorSettings(StopwatchColorMode.ALTERNATING, a, b).sample())
    }

    @Test fun randomPaletteIsSampledOnlyWhenCueIsCreatedAndSurvivesPermissionCopies() {
        var draws = 0
        val color = StopwatchColorSettings(StopwatchColorMode.RANDOM)
        val rule = StopwatchRule(true, 60, setOf(OvertimeEffect.SOUND), color)
        val engine = StopwatchReminderEngine(StopwatchReminderRules(primary = rule), 0) { size -> draws++; (draws - 1) % size }
        assertEquals(0, draws)
        val cue = engine.update(60000, false)!!
        assertEquals(4, draws)
        assertEquals(4, cue.colors.size)
        assertTrue(cue.colors.zipWithNext().all { (a, b) -> a != b })
        repeat(100) { StopwatchColorTimeline.color(base, cue.colors, it / 100f) }
        assertNull(engine.update(60000, false))
        assertEquals(cue.colors, cue.allowed(StopwatchReminderPermissions()).colors)
        assertEquals(4, draws)
        engine.update(120000, false)
        assertEquals(8, draws)
    }

    @Test fun missingColorIsLegacyNoneButExplicitBadFieldsRejectWholeSnapshot() {
        val rules = StopwatchReminderRules(primary = StopwatchRule(color = StopwatchColorSettings(StopwatchColorMode.ALTERNATING, a, b)))
        val raw = StopwatchReminderCodec.encode(rules)
        assertEquals(rules, StopwatchReminderCodec.decode(raw))
        val legacy = raw.mapValues { (_, value) -> (value as Map<*, *>) - "color" }
        assertEquals(StopwatchColorSettings(), StopwatchReminderCodec.decode(legacy)!!.primary.color)
        val row = raw["primary"] as Map<*, *>
        listOf(null, "NONE", mapOf("mode" to "UNKNOWN", "a" to a, "b" to b),
            mapOf("mode" to "TRANSITION", "a" to 1.5, "b" to b),
            mapOf("mode" to "TRANSITION", "a" to 0x00204060, "b" to b)).forEach { invalid ->
            assertNull(StopwatchReminderCodec.decode(raw + ("primary" to (row + ("color" to invalid)))))
        }
    }

    @Test fun editingVisualOnlyNeverRequestsSavedAudioEffects() {
        assertEquals(emptySet<OvertimeEffect>(), stopwatchPreviewAudio(null))
        assertEquals(emptySet<OvertimeEffect>(), stopwatchPreviewAudio(OvertimeEffect.BRIGHTEN))
        assertEquals(setOf(OvertimeEffect.SOUND), stopwatchPreviewAudio(OvertimeEffect.SOUND))
        assertEquals(setOf(OvertimeEffect.VIBRATION), stopwatchPreviewAudio(OvertimeEffect.VIBRATION))
    }

    @Test fun pausedOrDisabledRulesDoNotConsumeRandomSequence() {
        var draws = 0
        val rule = StopwatchRule(true, 60, emptySet(), StopwatchColorSettings(StopwatchColorMode.RANDOM))
        val engine = StopwatchReminderEngine(StopwatchReminderRules(primary = rule), 0) { draws++; 0 }
        assertNull(engine.update(60000, true))
        assertNull(engine.update(60000, false))
        assertEquals(0, draws)
        assertEquals(4, engine.update(120000, false)!!.colors.size)
        assertEquals(4, draws)
        assertTrue(StopwatchColorSettings().sample { error("NONE cannot sample randomness") }.isEmpty())
    }
}
