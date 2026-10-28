package app.floatingfocus

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Debug-only UI preference; never part of a preset or reminder appearance. */
internal enum class PresetStyle(val label: String) {
    ICON_CARD("图标方案卡"), ICON_TILE("图标启动块"), COMPACT_ROW("紧凑快捷行");
    companion object {
        fun decode(value: String?) = entries.firstOrNull { it.name == value } ?: ICON_CARD
    }
}

internal class PresetStyleStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("debug_preset_style", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(PresetStyle.decode(prefs.getString("style", null)))
    val style = current.asStateFlow()
    fun select(style: PresetStyle) {
        prefs.edit { putString("style", style.name) }
        current.value = style
    }
    companion object {
        @Volatile private var instance: PresetStyleStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: PresetStyleStore(context.applicationContext).also { instance = it }
        }
    }
}
