package app.floatingfocus

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeNavigationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("home_navigation", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(HomeEntries.decode(prefs.all["order"] as? String))
    val order = current.asStateFlow()

    fun save(order: List<HomeEntry>) {
        val encoded = HomeEntries.encode(order)
        prefs.edit { putString("order", encoded) }
        current.value = order.toList()
    }
}
