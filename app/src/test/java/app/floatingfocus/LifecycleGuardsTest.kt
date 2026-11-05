package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class LifecycleGuardsTest {
    @Test fun observerRegistrationFailureDoesNotCommitChildOrPublishReady() {
        var committed=false
        assertThrows(IllegalStateException::class.java) {
            TimerStartupGate.initialize({ error("register failed") }, { true }, { committed=true })
        }
        assertFalse(committed)
        var ready=true
        assertFalse(TimerStartupGate.initialize({ ready=false }, { ready }, { committed=true }))
        assertFalse(committed)
    }
    @Test fun initializationCommitsOnlyAfterAllFalliblePreparationAndValidityCheck() {
        val events=mutableListOf<String>()
        assertTrue(TimerStartupGate.initialize({ events+="observer" },{ events+="check";true },{ events+="record" }))
        assertEquals(listOf("observer","check","record"),events)
    }
    @Test fun taggedCheckpointCannotBecomeLegacyWhenWholeMemberIsMissing() {
        val r=TimerRecord("child","App","pkg",TimerMode.STOPWATCH,0,100000,1000,RecordStatus.INTERRUPTED,
            stopwatch=StopwatchCheckpoint(StopwatchBehavior.CONTINUOUS,ScreenTimeSnapshot(1000,listOf(ActiveInterval(0,1000))),
                3000,"parent","parent"))
        val row=TimerRecordCodec.encode(r)
        assertEquals(r,TimerRecordCodec.decode(row))
        assertEquals("STOPWATCH",row["source"])
        assertNull(TimerRecordCodec.decode(row-"stopwatch"))
        assertNull(TimerRecordCodec.decode(row+ ("stopwatch" to null)))
        assertNull(TimerRecordCodec.decode(row-"source"))
        assertNull(TimerRecordCodec.decode((row-"stopwatch") + ("source" to null)))
        val legacy=TimerRecord("old","App","pkg",TimerMode.STOPWATCH,0,100000,3000,RecordStatus.INTERRUPTED)
        assertEquals(legacy,TimerRecordCodec.decode(TimerRecordCodec.encode(legacy)))
    }
    @Test fun retryRecoversOrphanButNeverOwnLiveSessionAfterFirstWriteFailed() {
        val ownership=RecordRecoveryOwnership()
        val orphan=TimerRecord("orphan","Manual","",TimerMode.STOPWATCH,0,100000)
        val live=orphan.copy(id="live")
        ownership.submitted(live)
        val recovered=ownership.recover(orphan)
        assertEquals(RecordStatus.INTERRUPTED,recovered.status)
        // A failed persistence does not mutate ownership; a retry must recover it again.
        assertEquals(RecordStatus.INTERRUPTED,ownership.recover(orphan).status)
        assertEquals(RecordStatus.RUNNING,ownership.recover(live).status)
    }
}
