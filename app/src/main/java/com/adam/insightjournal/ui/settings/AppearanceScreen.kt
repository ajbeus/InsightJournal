package com.adam.insightjournal.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adam.insightjournal.ui.theme.DarkMode
import com.adam.insightjournal.ui.theme.JournalFont
import com.adam.insightjournal.ui.theme.ThemePalette
import com.adam.insightjournal.ui.theme.ThemeSettingsRepository
import com.adam.insightjournal.ui.theme.colorSchemeFor
import com.adam.insightjournal.ui.theme.isDark
import com.adam.insightjournal.ui.theme.wallpaperColorsAvailable

/**
 * Color theme, light/dark and font. Every choice applies at once to the whole
 * app (this screen included), so the screen is its own preview.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(repository: ThemeSettingsRepository, onBack: () -> Unit) {
    val settings by repository.settings.collectAsStateWithLifecycle()
    val dark = settings.isDark()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionTitle("Color")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ThemePalette.entries
                    .filter { it != ThemePalette.WALLPAPER || wallpaperColorsAvailable }
                    .forEach { palette ->
                        PaletteSwatch(
                            palette = palette,
                            dark = dark,
                            selected = palette == settings.palette,
                            onClick = { repository.update { it.copy(palette = palette) } },
                        )
                    }
            }

            SectionTitle("Light or dark")
            ChipRow {
                DarkMode.entries.forEach { mode ->
                    FilterChip(
                        selected = mode == settings.darkMode,
                        onClick = { repository.update { it.copy(darkMode = mode) } },
                        label = { Text(mode.label) },
                    )
                }
            }

            SectionTitle("Font")
            ChipRow {
                JournalFont.entries.forEach { font ->
                    FilterChip(
                        selected = font == settings.font,
                        onClick = { repository.update { it.copy(font = font) } },
                        // Each chip is set in its own font, so the name shows what you get.
                        label = { Text(font.label, fontFamily = font.previewFamily()) },
                    )
                }
            }

            SectionTitle("Preview")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Morning run", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Cold and clear. Ran the river loop and felt better than I expected.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Saved on this phone only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A small sample of the palette (its paper tone and three accent colors) with its name underneath. */
@Composable
private fun PaletteSwatch(palette: ThemePalette, dark: Boolean, selected: Boolean, onClick: () -> Unit) {
    val scheme = colorSchemeFor(palette, dark)
    val shape = RoundedCornerShape(16.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(96.dp)
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(shape)
                .background(scheme.surfaceContainerHighest)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = shape,
                ),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                listOf(scheme.primary, scheme.secondary, scheme.tertiary).forEach { color ->
                    Box(Modifier.size(24.dp).clip(CircleShape).background(color))
                }
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 22.dp).size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(palette.label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 10.dp),
    )
}

private fun JournalFont.previewFamily(): FontFamily = when (this) {
    JournalFont.SANS -> FontFamily.Default
    JournalFont.SERIF -> FontFamily.Serif
    JournalFont.MONO -> FontFamily.Monospace
}
