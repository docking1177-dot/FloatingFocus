package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class StopwatchReminderSnapshotTest {
    private val rules=StopwatchReminderRules(primary=StopwatchRule(true,65,setOf(OvertimeEffect.OUTLINE)))
    @Test fun presetRoundTripAndOldDefaultAreIndependentOfCurrentRules() {
        val p=TimerPreset("p","Preset",15,ReminderSettings(),mode=TimerMode.STOPWATCH,stopwatchReminders=rules)
        val row=TimerPresetCodec.encode(p)
        assertEquals(rules,TimerPresetCodec.decode(row)!!.stopwatchReminders)
        val legacy=TimerPresetCodec.decode(row-"stopwatchReminders")!!
        assertEquals(StopwatchReminderRules(),legacy.stopwatchReminders)
        assertEquals(p.id,legacy.id);assertEquals(p.positions,legacy.positions)
        assertNull(TimerPresetCodec.decode(row+("stopwatchReminders" to null)))
        assertNull(TimerPresetCodec.decode(row+("stopwatchReminders" to "bad")))
    }
    @Test fun checkpointCarriesOriginalRulesAndMissingOldFieldMeansOff() {
        val p=StopwatchCheckpoint(StopwatchBehavior.CONTINUOUS,ScreenTimeSnapshot(3000,listOf(ActiveInterval(0,3000))),
            60000,"parent","parent",stopwatchReminders=rules)
        val row=StopwatchCheckpointCodec.encode(p)
        val restored=StopwatchCheckpointCodec.decode(row)!!
        assertEquals(rules,restored.stopwatchReminders)
        assertEquals(63000L,restored.carriedMillis+restored.activity.activeMillis)
        assertEquals(StopwatchReminderRules(),StopwatchCheckpointCodec.decode(row-"stopwatchReminders")!!.stopwatchReminders)
        assertNull(StopwatchCheckpointCodec.decode(row+("stopwatchReminders" to null)))
        assertNull(StopwatchCheckpointCodec.decode(row+("stopwatchReminders" to mapOf("primary" to "bad"))))
    }
}
