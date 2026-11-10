package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class StopwatchColorTimingTest {
    @Test fun actualTimeHasOneBoundedTimelineInsteadOfScaledTweenLifetime() {
        assertEquals(0f,StopwatchColorTimeline.progress(0),0f)
        assertEquals(.5f,StopwatchColorTimeline.progress(800),0f)
        assertEquals(1f,StopwatchColorTimeline.progress(1600),0f)
        assertEquals(1f,StopwatchColorTimeline.progress(3200),0f)
        assertEquals(0f,StopwatchColorTimeline.progress(-1),0f)
    }
    @Test fun alternatingIsDiscreteAndRandomUsesOnlyItsConsumedPalette() {
        val a=0xff204060.toInt();val b=0xffffcc40.toInt();val base=0xff101010.toInt()
        val frames=StopwatchColorSettings(StopwatchColorMode.ALTERNATING,a,b).sample()
        for(i in 1..99) assertTrue(StopwatchColorTimeline.color(base,frames,i/100f,StopwatchColorMode.ALTERNATING) in setOf(a,b))
        assertEquals(base,StopwatchColorTimeline.color(base,frames,1f,StopwatchColorMode.ALTERNATING))
        val random=listOf(a,b,base,a)
        for(i in 1..99) assertTrue(StopwatchColorTimeline.color(base,random,i/100f,StopwatchColorMode.RANDOM) in random)
    }
}
