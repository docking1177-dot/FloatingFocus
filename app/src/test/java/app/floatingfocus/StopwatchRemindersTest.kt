package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class StopwatchRemindersTest {
    private fun rules() = StopwatchReminderRules(
        StopwatchRule(true,300,setOf(OvertimeEffect.BRIGHTEN)),
        StopwatchRule(true,900,setOf(OvertimeEffect.OUTLINE)),
        StopwatchRule(true,1800,setOf(OvertimeEffect.SOUND,OvertimeEffect.VIBRATION)))

    @Test fun independentCyclesAndSimultaneousHighestDoNotStack() {
        val engine=StopwatchReminderEngine(rules(),0)
        assertNull(engine.update(299999,false))
        assertEquals(StopwatchLevel.PRIMARY,engine.update(300000,false)!!.level)
        assertNull(engine.update(300000,false))
        assertEquals(StopwatchLevel.PRIMARY,engine.update(600000,false)!!.level)
        val middle=engine.update(900000,false)!!
        assertEquals(StopwatchLevel.MIDDLE,middle.level)
        assertEquals(setOf(OvertimeEffect.OUTLINE),middle.effects)
        assertEquals(1200000L,engine.nextElapsed)
        assertEquals(StopwatchLevel.PRIMARY,engine.update(1200000,false)!!.level)
        assertEquals(StopwatchLevel.PRIMARY,engine.update(1500000,false)!!.level)
        val high=engine.update(1800000,false)!!
        assertEquals(StopwatchLevel.HIGH,high.level)
        assertEquals(setOf(OvertimeEffect.SOUND,OvertimeEffect.VIBRATION),high.effects)
        assertEquals(2100000L,engine.nextElapsed)
    }
    @Test fun rulesMayHaveAnyIntervalOrderAndEachCanBeOff() {
        val s=StopwatchReminderRules(StopwatchRule(false,60),StopwatchRule(true,180),StopwatchRule(true,60))
        val e=StopwatchReminderEngine(s,0)
        assertEquals(StopwatchLevel.HIGH,e.update(60000,false)!!.level)
        assertEquals(StopwatchLevel.HIGH,e.update(180000,false)!!.level)
        assertNull(StopwatchReminderEngine(StopwatchReminderRules(),0).nextElapsed)
    }
    @Test fun pausedSamplingDoesNotConsumeTheNextRunningThreshold() {
        val e=StopwatchReminderEngine(rules(),0)
        assertNull(e.update(299999,true))
        assertNull(e.update(299999,false))
        assertEquals(300000L,e.nextElapsed)
        assertEquals(StopwatchLevel.PRIMARY,e.update(300000,false)!!.level)
        assertNull(e.update(300000,true))
    }
    @Test fun continuationSkipsExactAndEarlierThresholdsButKeepsCyclePhase() {
        val e=StopwatchReminderEngine(rules(),900000)
        assertNull(e.update(900000,false))
        assertEquals(1200000L,e.nextElapsed)
        assertEquals(StopwatchLevel.PRIMARY,e.update(1200000,false)!!.level)
        val near=StopwatchReminderEngine(rules(),299999)
        assertEquals(StopwatchLevel.PRIMARY,near.update(300000,false)!!.level)
    }
    @Test fun pauseJustAfterAQueuedDeadlineDiscardsItWithoutMovingFutureCycles() {
        val e=StopwatchReminderEngine(rules(),0)
        assertNull(e.update(300001,true))
        assertNull(e.update(300001,false))
        assertEquals(600000L,e.nextElapsed)
        assertEquals(StopwatchLevel.PRIMARY,e.update(600000,false)!!.level)
        assertNull(e.update(900000,true))
        assertNull(e.update(900000,false))
        assertEquals(1200000L,e.nextElapsed)
    }
    @Test fun countdownPresetsAndStartsDoNotReadBrokenStopwatchConfiguration() {
        var reads=0
        val broken={ reads++;null }
        assertEquals(StopwatchReminderRules(),resolveStopwatchRules(TimerMode.COUNTDOWN,broken))
        assertEquals(0,reads)
        assertNull(resolveStopwatchRules(TimerMode.STOPWATCH,broken))
        assertEquals(1,reads)
    }
    @Test fun delayedCallbacksDeliverAtMostOneAndAdvanceAllPastRounds() {
        val e=StopwatchReminderEngine(rules(),0)
        assertEquals(StopwatchLevel.HIGH,e.update(7200000,false)!!.level)
        assertNull(e.update(7200000,false))
        assertEquals(7500000L,e.nextElapsed)
        assertNull(e.update(1000,false))
    }
    @Test fun largestTimeNeverOverflowsIntoImmediateLoop() {
        val e=StopwatchReminderEngine(rules(),Long.MAX_VALUE)
        assertNull(e.nextElapsed)
        assertNull(e.update(Long.MAX_VALUE,false))
        val late=StopwatchReminderEngine(rules(),0)
        assertNotNull(late.update(Long.MAX_VALUE,false))
        assertNull(late.nextElapsed)
        assertNull(late.update(Long.MAX_VALUE,false))
    }
    @Test fun permissionsAreIndependentAndCannotRecreateFilteredCueEffects() {
        val cue=StopwatchCue(1,StopwatchLevel.HIGH,OvertimeEffect.entries.toSet(),1800000)
        val none=cue.allowed(StopwatchReminderPermissions())
        assertEquals(OvertimeEffect.entries.filter { it.visual }.toSet(),none.effects)
        assertFalse(OvertimeEffect.SOUND in none.allowed(StopwatchReminderPermissions(true,true)).effects)
        val sound=cue.allowed(StopwatchReminderPermissions(sound=true))
        assertTrue(OvertimeEffect.SOUND in sound.effects)
        assertFalse(OvertimeEffect.VIBRATION in sound.effects)
    }
    @Test fun strictRuleCodecKeepsAllLevelsAndRejectsMalformedNewValues() {
        val value=rules();assertEquals(value,StopwatchReminderCodec.decode(StopwatchReminderCodec.encode(value)))
        assertNull(StopwatchReminderCodec.decode(null))
        val raw=StopwatchReminderCodec.encode(value)
        assertNull(StopwatchReminderCodec.decode(raw-"high"))
        assertNull(StopwatchReminderCodec.decode(raw+("middle" to mapOf("enabled" to true,"intervalSeconds" to 0,"effects" to "SOUND"))))
        assertNull(StopwatchReminderCodec.decode(raw+("high" to mapOf("enabled" to true,"intervalSeconds" to 60.5,"effects" to "SOUND"))))
        assertNull(StopwatchReminderCodec.decode(raw+("high" to mapOf("enabled" to true,"intervalSeconds" to 60,"effects" to "UNKNOWN"))))
    }
}
