package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class OverlayInitialOffsetTest {
    @Test fun savedAnchorTemporarilySeparatesWithoutMutatingIt() {
        val saved=OverlayAnchor(.7f,.3f)
        val shifted=saved.forNewWindow(1000,64)
        assertEquals(.364f,shifted.yFraction,.0001f)
        assertEquals(.3f,saved.yFraction,.0001f)
        assertEquals(saved.xFraction,shifted.xFraction,.0001f)
    }
    @Test fun bottomAnchorOffsetsUpAndZeroOffsetIsIdentity() {
        val bottom=OverlayAnchor(.7f,.99f)
        assertTrue(bottom.forNewWindow(1000,64).yFraction<bottom.yFraction)
        assertEquals(bottom,bottom.forNewWindow(1000,0))
    }
    @Test fun bottomPlacementRemainsDistinctAfterNativeFrameClamping() {
        val saved=OverlayAnchor(.7f,.9f)
        val shifted=saved.forNewWindow(1000,64,200)
        fun top(a:OverlayAnchor)=(a.yFraction*1000-100).toInt().coerceIn(0,800)
        assertNotEquals(top(saved),top(shifted))
    }
}
