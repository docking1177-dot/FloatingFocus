package app.floatingfocus

import android.app.Application
import android.content.res.Configuration

/** Observe widgets only while this normal app process exists; no background worker or restart. */
class FocusApplication : Application() {
    override fun onCreate() { super.onCreate(); TimerWidgets.observe(this) }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        TimerWidgets.refresh(this)
    }
}
