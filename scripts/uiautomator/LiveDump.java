import android.os.Environment;
import java.io.File;
import com.android.uiautomator.core.Configurator;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
/** Test-only live snapshot. Does not wait for ticking clocks to become idle. */
public class LiveDump extends UiAutomatorTestCase {
 public void testDump() throws Exception {
  Configurator.getInstance().setWaitForIdleTimeout(0);
  Configurator.getInstance().setWaitForSelectorTimeout(1000);
  File target = new File(new File(Environment.getDataDirectory(), "local/tmp"), "ff-live.xml");
  for (int attempt=0; attempt<20; attempt++) {
   getUiDevice().dumpWindowHierarchy("ff-live.xml");
   if (target.isFile() && target.length()>0) {
    System.out.println("FF_LIVE_PATH="+target.getAbsolutePath());
    return;
   }
   Thread.sleep(100);
  }
  fail("No fresh active-window hierarchy at "+target.getAbsolutePath());
 }
}
