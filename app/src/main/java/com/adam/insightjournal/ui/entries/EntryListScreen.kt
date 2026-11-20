package com.adam.insightjournal.ui.entries

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adam.insightjournal.data.model.JournalEntry
import com.adam.insightjournal.data.model.Mood
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy")
private val dayFormat = DateTimeFormatter.ofPattern("d")
private val weekdayFormat = DateTimeFormatter.ofPattern("EEE")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryListScreen(
    viewModel: EntryListViewModel,
    demoMode: Boolean,
    bottomBar: @Composable () -> Unit,
    onNewEntry: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.writeErrors.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Insight Journal") },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    if (!demoMode) {
                        IconButton(onClick = onSignOut) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Sign out")
                        }
                    }
                },
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewEntry,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New entry") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (demoMode) DemoBanner()
            when (val state = uiState) {
                EntryListUiState.Loading -> CenteredBox { CircularProgressIndicator() }
                is EntryListUiState.Error -> CenteredMessage("Couldn't load entries", state.message)
                is EntryListUiState.Loaded ->
                    if (state.months.isEmpty()) {
                        CenteredMessage("No entries yet", "Tap \"New entry\" to write your first one.")
                    } else {
                        EntryList(state.months, onOpenEntry)
                    }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryList(months: List<MonthSection>, onOpenEntry: (String) -> Unit) {
    LazyColumn(
        // Bottom padding keeps the last card clear of the floating button.
        contentPadding = PaddingValues(bottom = 96.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        months.forEach { section ->
            stickyHeader(key = section.month.toString()) {
                MonthHeader(section)
            }
            items(section.entries, key = { it.id }) { entry ->
                EntryCard(entry, onClick = { onOpenEntry(entry.id) })
            }
        }
    }
}

@Composable
private fun MonthHeader(section: MonthSection) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                section.month.format(monthFormat),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            val count = section.entries.size
            Text(
                if (count == 1) "1 entry" else "$count entries",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntryCard(entry: JournalEntry, onClick: () -> Unit) {
    val date = entry.entryDate.atZone(ZoneId.systemDefault())
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(44.dp)) {
                Text(date.format(dayFormat), style = MaterialTheme.typography.headlineSmall)
                Text(
                    date.format(weekdayFormat),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.mood?.takeIf(Mood::isValid)?.let { mood ->
                    Text(
                        Mood.emoji(mood),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .semantics { contentDescription = "Mood: ${Mood.label(mood)}" },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.body.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        entry.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DemoBanner() {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Demo mode: Firebase isn't connected, so entries are not saved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun CenteredMessage(title: String, detail: String) {
    CenteredBox {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
