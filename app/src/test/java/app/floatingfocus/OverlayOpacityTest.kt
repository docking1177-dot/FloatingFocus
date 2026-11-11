package app.floatingfocus

import org.junit.Assert.*
import org.junit.Test

class OverlayOpacityTest {
    @Test fun oldSingleOpacityMigratesIntoBothChannels() {
        val settings=ReminderSettingsCodec.decode(mapOf("opacity" to .42f))
        assertEquals(.42f,ReminderSettingsCodec.encode(settings)["foregroundOpacity"])
    }
    @Test fun nativeAndRenderLayersApplyEachChannelExactlyOnce() {
        val p=OverlayOpacity(.4f,.9f)
        assertEquals(.4f,p.window*p.background,0.0001f)
        assertEquals(.9f,p.window*p.foreground,0.0001f)
        val capped=OverlayLockPolicy.displayOpacity(p.window,true,.6f)
        assertEquals(.6f,capped,0.0001f)
        assertEquals(.9f,p.window,0.0001f)
    }
    @Test fun twoLockedOverlappingWindowsStayWithinCombinedSystemThreshold() {
        val a=OverlayLockPolicy.displayOpacity(.9f,true,.8f,2)
        assertTrue(1f-(1f-a)*(1f-a) <= .80001f)
        assertEquals(.9f,OverlayLockPolicy.displayOpacity(.9f,false,.8f,2),.0001f)
    }
    @Test fun unequalOpacityRoundTripsAndC4DoesNotReadIt() {
        val settings=ReminderSettingsCodec.decode(mapOf("opacity" to .3f,"foregroundOpacity" to 1f))
        assertEquals(settings,ReminderSettingsCodec.decode(ReminderSettingsCodec.encode(settings)))
        val normal=ReminderPresenter.present(ReminderSnapshot(10000,0f),settings)
        assertEquals(.3f,normal.opacity,.0001f);assertEquals(1f,normal.foregroundOpacity,.0001f)
        val c4=ReminderPresenter.forC4(C4Frame())
        assertEquals(C4Theme.OPACITY,c4.opacity,.0001f)
        assertEquals(C4Theme.OPACITY,c4.foregroundOpacity,.0001f)
    }
}
