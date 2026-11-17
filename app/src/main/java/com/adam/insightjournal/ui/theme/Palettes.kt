package com.adam.insightjournal.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The color themes the user can pick from. Each one is described by a few hues
 * and a saturation; [paletteScheme] turns that into a full Material 3 color scheme.
 *
 * The names are stored in preferences, so don't rename a constant without a migration.
 */
enum class ThemePalette(val label: String, internal val spec: PaletteSpec?) {
    /** The default: the browns and golds of the logo. */
    SUNSET("Sunset", PaletteSpec(primaryHue = 30f, secondaryHue = 18f, tertiaryHue = 350f, saturation = 0.62f)),
    FOREST("Forest", PaletteSpec(primaryHue = 145f, secondaryHue = 95f, tertiaryHue = 185f, saturation = 0.42f)),
    OCEAN("Ocean", PaletteSpec(primaryHue = 208f, secondaryHue = 188f, tertiaryHue = 255f, saturation = 0.62f)),
    LAVENDER("Lavender", PaletteSpec(primaryHue = 265f, secondaryHue = 285f, tertiaryHue = 330f, saturation = 0.45f)),
    ROSE("Rose", PaletteSpec(primaryHue = 345f, secondaryHue = 10f, tertiaryHue = 35f, saturation = 0.55f)),
    GRAPHITE("Graphite", PaletteSpec(primaryHue = 215f, secondaryHue = 215f, tertiaryHue = 215f, saturation = 0.12f)),

    /** Colors taken from the phone's wallpaper (Material You). Android 12+ only. */
    WALLPAPER("Wallpaper", null),
}

/** Hues are in degrees on the color wheel (0 = red, 120 = green, 240 = blue). */
internal data class PaletteSpec(
    val primaryHue: Float,
    val secondaryHue: Float,
    val tertiaryHue: Float,
    /** 0..1: how colorful the theme is. */
    val saturation: Float,
)

/**
 * Builds a Material 3 scheme from a [PaletteSpec]. Every role is the palette's hue
 * at a fixed lightness, the same idea as Material's tonal palettes: light themes use
 * dark accents on pale surfaces, dark themes use pale accents on dark surfaces.
 * Backgrounds carry a little of the primary hue so each theme has its own paper tone.
 */
internal fun paletteScheme(spec: PaletteSpec, dark: Boolean): ColorScheme {
    val s = spec.saturation
    fun primary(lightness: Float) = Color.hsl(spec.primaryHue, s, lightness)
    fun secondary(lightness: Float) = Color.hsl(spec.secondaryHue, s * 0.55f, lightness)
    fun tertiary(lightness: Float) = Color.hsl(spec.tertiaryHue, s * 0.7f, lightness)
    fun neutral(lightness: Float, strength: Float = 0.45f) =
        Color.hsl(spec.primaryHue, (s * strength).coerceAtMost(0.4f), lightness)

    return if (dark) {
        darkColorScheme(
            primary = primary(0.74f), onPrimary = primary(0.14f),
            primaryContainer = primary(0.27f), onPrimaryContainer = primary(0.90f),
            inversePrimary = primary(0.36f),
            secondary = secondary(0.76f), onSecondary = secondary(0.15f),
            secondaryContainer = secondary(0.26f), onSecondaryContainer = secondary(0.90f),
            tertiary = tertiary(0.76f), onTertiary = tertiary(0.15f),
            tertiaryContainer = tertiary(0.27f), onTertiaryContainer = tertiary(0.90f),
            background = neutral(0.075f), onBackground = neutral(0.90f),
            surface = neutral(0.075f), onSurface = neutral(0.90f),
            surfaceVariant = neutral(0.24f), onSurfaceVariant = neutral(0.78f),
            surfaceTint = primary(0.74f),
            inverseSurface = neutral(0.90f), inverseOnSurface = neutral(0.18f),
            outline = neutral(0.56f, 0.25f), outlineVariant = neutral(0.28f),
            surfaceBright = neutral(0.22f), surfaceDim = neutral(0.075f),
            surfaceContainerLowest = neutral(0.05f), surfaceContainerLow = neutral(0.10f),
            surfaceContainer = neutral(0.12f), surfaceContainerHigh = neutral(0.155f),
            surfaceContainerHighest = neutral(0.19f),
        )
    } else {
        // Green and yellow look brighter than blue or purple at the same lightness, so
        // a fixed lightness isn't enough here: accents that are used as text colors
        // are darkened until they are readable on the darkest light surface (a card).
        val card = neutral(0.90f, 0.6f)
        val primaryTone = readableLightness(card, ::primary)
        lightColorScheme(
            primary = primary(primaryTone), onPrimary = Color.White,
            primaryContainer = primary(0.87f), onPrimaryContainer = primary(0.14f),
            inversePrimary = primary(0.74f),
            secondary = secondary(readableLightness(card, ::secondary)), onSecondary = Color.White,
            secondaryContainer = secondary(0.88f), onSecondaryContainer = secondary(0.15f),
            tertiary = tertiary(readableLightness(card, ::tertiary)), onTertiary = Color.White,
            tertiaryContainer = tertiary(0.89f), onTertiaryContainer = tertiary(0.15f),
            background = neutral(0.975f, 0.8f), onBackground = neutral(0.11f),
            surface = neutral(0.975f, 0.8f), onSurface = neutral(0.11f),
            surfaceVariant = neutral(0.90f, 0.6f), onSurfaceVariant = neutral(0.30f, 0.3f),
            surfaceTint = primary(primaryTone),
            inverseSurface = neutral(0.20f), inverseOnSurface = neutral(0.95f),
            outline = neutral(0.48f, 0.2f), outlineVariant = neutral(0.80f, 0.4f),
            surfaceBright = neutral(0.975f, 0.8f), surfaceDim = neutral(0.86f, 0.5f),
            surfaceContainerLowest = Color.White, surfaceContainerLow = neutral(0.955f, 0.7f),
            surfaceContainer = neutral(0.94f, 0.7f), surfaceContainerHigh = neutral(0.92f, 0.65f),
            surfaceContainerHighest = card,
        )
    }
}

/** WCAG contrast ratio between two colors, from 1 (identical) to 21 (black on white). */
internal fun contrastRatio(a: Color, b: Color): Float {
    val lighter = maxOf(a.luminance(), b.luminance())
    val darker = minOf(a.luminance(), b.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * The lightest lightness, starting from [start] and going darker, at which
 * [tone] reaches [MIN_TEXT_CONTRAST] against [background].
 */
private fun readableLightness(background: Color, tone: (Float) -> Color, start: Float = 0.38f): Float {
    var lightness = start
    while (lightness > 0.1f && contrastRatio(tone(lightness), background) < MIN_TEXT_CONTRAST) lightness -= 0.01f
    return lightness
}

/** A little above the WCAG AA minimum of 4.5 for body text. */
private const val MIN_TEXT_CONTRAST = 4.6f
