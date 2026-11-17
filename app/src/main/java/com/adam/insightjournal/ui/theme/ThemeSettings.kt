package com.adam.insightjournal.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DarkMode(val label: String) {
    SYSTEM("Match phone"),
    LIGHT("Light"),
    DARK("Dark"),
}

enum class JournalFont(val label: String) {
    SANS("Modern"),
    SERIF("Classic"),
    MONO("Typewriter"),
}

/** Everything the Appearance screen lets the user choose. */
data class ThemeSettings(
    val palette: ThemePalette = ThemePalette.SUNSET,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val font: JournalFont = JournalFont.SANS,
)

/**
 * Remembers the user's appearance choices on this device (SharedPreferences).
 * They are per device on purpose: a theme is about the screen in your hand, and
 * reading it must not wait for sign-in or the network.
 */
class ThemeSettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ThemeSettings> = _settings.asStateFlow()

    fun update(transform: (ThemeSettings) -> ThemeSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        prefs.edit()
            .putString(KEY_PALETTE, updated.palette.name)
            .putString(KEY_DARK_MODE, updated.darkMode.name)
            .putString(KEY_FONT, updated.font.name)
            .apply()
    }

    private fun load(): ThemeSettings {
        val defaults = ThemeSettings()
        return ThemeSettings(
            palette = enumOr(prefs.getString(KEY_PALETTE, null), defaults.palette),
            darkMode = enumOr(prefs.getString(KEY_DARK_MODE, null), defaults.darkMode),
            font = enumOr(prefs.getString(KEY_FONT, null), defaults.font),
        )
    }

    private companion object {
        const val KEY_PALETTE = "palette"
        const val KEY_DARK_MODE = "darkMode"
        const val KEY_FONT = "font"
    }
}

/** A stored name that no longer matches a constant falls back to the default. */
private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
