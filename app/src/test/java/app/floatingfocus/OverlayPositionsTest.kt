package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class OverlayPositionsTest {
    private val left = OverlayAnchor(.1f,.1f)
    private val right = OverlayAnchor(.9f,.1f)
    @Test fun directionsAreIndependentAndSurviveRoundTrip() {
        val positions = OverlayPositions().remember(OverlayOrientation.PORTRAIT,left)
            .remember(OverlayOrientation.LANDSCAPE,right)
        assertEquals(left,positions.anchor(OverlayOrientation.PORTRAIT))
        assertEquals(right,positions.anchor(OverlayOrientation.LANDSCAPE))
        assertEquals(positions,OverlayPositionsCodec.decode(OverlayPositionsCodec.encode(positions)))
        val moved=positions.remember(OverlayOrientation.LANDSCAPE,OverlayAnchor(.7f,.8f))
        assertEquals(left,moved.anchor(OverlayOrientation.PORTRAIT))
    }
    @Test fun legacyBelongsOnlyToFirstDirectionAndCannotOverwriteSlots() {
        val migrated=OverlayPositions(legacy=left).migrate(OverlayOrientation.LANDSCAPE)
        assertEquals(left,migrated.anchor(OverlayOrientation.LANDSCAPE))
        assertNull(migrated.migrate(OverlayOrientation.PORTRAIT).anchor(OverlayOrientation.PORTRAIT))
        val existing=OverlayPositions(portrait=right,legacy=left)
        assertEquals(existing,existing.migrate(OverlayOrientation.LANDSCAPE))
        assertEquals(right,existing.anchor(OverlayOrientation.PORTRAIT))
        assertNull(existing.anchor(OverlayOrientation.LANDSCAPE))
    }
    @Test fun invalidNewCoordinatesAreNotZeroAnchors() {
        assertNull(OverlayPositionsCodec.decode(mapOf("portrait" to mapOf("x" to 2f,"y" to .1f))))
        assertEquals(OverlayPositions(),OverlayPositionsCodec.decode(null))
        assertNull(OverlayPositionsCodec.decode(mapOf("legacyOrientation" to null)))
    }
    @Test fun liveDragSurvivesMeasureButRotationUpAndCloseNeverCommitIt() {
        val v=OverlayViewport(1080,2200)
        val drag=OverlayDragCheckpoint()
        drag.begin(v);drag.move(v,right)
        assertEquals(right,drag.pending(v))
        assertNull(drag.finish(OverlayViewport(2200,1080)))
        drag.begin(v);drag.move(v,left);drag.cancel()
        assertNull(drag.finish(v))
        drag.begin(v);drag.move(v,right)
        assertEquals(right,drag.finish(v))
        assertNull(drag.finish(v))
    }
    @Test fun compactTimeIsCenteredAfterClampingAndRotation() {
        val p=OverlayPosition().apply { restore(right,2204,1000) }
        val compact=OverlayContentGeometry(410,166,244f,80f,83f,83f,6f)
        val first=p.placeContent(compact,2204,1000)
        assertEquals(205f,first.headerX,0f)
        p.restore(right,1080,2200)
        val after=p.placeContent(compact,1080,2200)
        assertEquals(205f,after.headerX,0f)
        assertEquals(83f,after.headerY,0f)
        assertEquals(right,p.snapshot(1080,2200))
    }
}
