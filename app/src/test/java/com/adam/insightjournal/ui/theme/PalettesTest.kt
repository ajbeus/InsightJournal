package com.adam.insightjournal.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/** Every built-in palette must keep text readable (WCAG AA: contrast of at least 4.5 to 1). */
class PalettesTest {

    @Test
    fun `text colors contrast with the color they sit on`() {
        val failures = mutableListOf<String>()
        for (palette in ThemePalette.entries) {
            val spec = palette.spec ?: continue
            for (dark in listOf(false, true)) {
                val scheme = paletteScheme(spec, dark)
                val pairs = mapOf(
                    "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
                    "onPrimaryContainer/primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                    "onSecondaryContainer/secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                    "onTertiaryContainer/tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                    "onSurface/surface" to (scheme.onSurface to scheme.surface),
                    "onSurface/card" to (scheme.onSurface to scheme.surfaceContainerHighest),
                    "onSurfaceVariant/surface" to (scheme.onSurfaceVariant to scheme.surface),
                    "onSurfaceVariant/card" to (scheme.onSurfaceVariant to scheme.surfaceContainerHighest),
                    "primary/surface" to (scheme.primary to scheme.surface),
                    "primary/card" to (scheme.primary to scheme.surfaceContainerHighest),
                )
                for ((name, colors) in pairs) {
                    val ratio = contrastRatio(colors.first, colors.second)
                    if (ratio < 4.5f) failures += "$palette ${if (dark) "dark" else "light"} $name is $ratio"
                }
            }
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }
}
