package com.adam.insightjournal.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Wallpaper colors need Android 12; older phones don't get that option. */
val wallpaperColorsAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun ThemeSettings.isDark(): Boolean = when (darkMode) {
    DarkMode.SYSTEM -> isSystemInDarkTheme()
    DarkMode.LIGHT -> false
    DarkMode.DARK -> true
}

/** The colors for one palette. Also used by the Appearance screen to draw its swatches. */
@Composable
fun colorSchemeFor(palette: ThemePalette, dark: Boolean): ColorScheme {
    val spec = palette.spec
    if (spec == null && wallpaperColorsAvailable) {
        val context = LocalContext.current
        return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    // "Wallpaper" saved on a phone that can't do it falls back to the default palette.
    val resolved = spec ?: ThemePalette.SUNSET.spec!!
    return remember(resolved, dark) { paletteScheme(resolved, dark) }
}

@Composable
fun InsightJournalTheme(
    settings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = colorSchemeFor(settings.palette, settings.isDark()),
        typography = remember(settings.font) { typographyFor(settings.font) },
        content = content,
    )
}
