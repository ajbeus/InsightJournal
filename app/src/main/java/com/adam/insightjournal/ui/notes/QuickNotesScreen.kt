package com.adam.insightjournal.ui.notes

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adam.insightjournal.ai.SummaryStatus
import com.adam.insightjournal.data.model.QuickNote
import com.adam.insightjournal.ai.AiSettings
import com.adam.insightjournal.ui.ai.rememberAiGate
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")
private val dayFormat = DateTimeFormatter.ofPattern("EEE, MMM d")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickNotesScreen(
    viewModel: QuickNotesViewModel,
    aiSettings: AiSettings,
    onOpenDraft: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    // Every AI action asks for consent first while AI features are off.
    val aiGate = rememberAiGate(aiSettings)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var noteToDelete by remember { mutableStateOf<QuickNote?>(null) }
    // Days whose open/closed state the user flipped from the default.
    var toggledDays by rememberSaveable { mutableStateOf(setOf<String>()) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(viewModel) {
        viewModel.draftReady.collect { onOpenDraft() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Quick notes") }) },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            NoteInput(onAdd = viewModel::addNote)
            if (!viewModel.aiEnabled) {
                Text(
                    "AI features are off: no ANTHROPIC_API_KEY in local.properties.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when (val state = uiState) {
                QuickNotesUiState.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                is QuickNotesUiState.Error -> CenteredMessage("Couldn't load notes", state.message)
                is QuickNotesUiState.Loaded ->
                    if (state.days.isEmpty()) {
                        CenteredMessage(
                            "No quick notes yet",
                            "Jot things down through the day, then turn them into a journal entry.",
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 16.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            state.days.forEach { day ->
                                val key = day.date.toString()
                                // Days already in the journal start folded away to keep the list short.
                                val openByDefault = day.pendingCount > 0
                                val expanded = openByDefault != (key in toggledDays)
                                item(key = "day-$key") {
                                    DayHeader(
                                        day = day,
                                        expanded = expanded,
                                        aiEnabled = viewModel.aiEnabled,
                                        onToggle = {
                                            toggledDays = if (key in toggledDays) toggledDays - key else toggledDays + key
                                        },
                                        onCreateEntry = { aiGate.run { viewModel.createEntryForDay(day.date) } },
                                    )
                                }
                                if (expanded) {
                                    items(day.notes, key = { it.note.id }) { item ->
                                        NoteCard(
                                            item = item,
                                            onSummarize = { aiGate.run { viewModel.requestSummary(item.note) } },
                                            onDelete = { noteToDelete = item.note },
                                        )
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }

    noteToDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text("Delete this note?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteNote(note.id); noteToDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { noteToDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NoteInput(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Write a quick note") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            maxLines = 6,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = { onAdd(text); text = "" },
            enabled = text.isNotBlank(),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Save note")
        }
    }
}

@Composable
private fun DayHeader(
    day: DaySection,
    expanded: Boolean,
    aiEnabled: Boolean,
    onToggle: () -> Unit,
    onCreateEntry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    dayLabel(day.date),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    daySubtitle(day),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide notes" else "Show notes",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (aiEnabled && day.pendingCount > 0) {
            if (day.isDrafting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Writing a draft from your notes…", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                FilledTonalButton(
                    onClick = onCreateEntry,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text("Create journal entry")
                }
            }
        }
    }
}

private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(dayFormat)
    }
}

private fun daySubtitle(day: DaySection): String {
    val total = day.notes.size
    val notes = if (total == 1) "1 note" else "$total notes"
    return when (day.pendingCount) {
        0 -> "$notes · Added to journal"
        total -> notes
        else -> "$notes · ${day.pendingCount} not in journal yet"
    }
}

@Composable
private fun NoteCard(item: QuickNoteItem, onSummarize: () -> Unit, onDelete: () -> Unit) {
    val note = item.note
    var expanded by rememberSaveable(note.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { expanded = !expanded }
            .animateContentSize(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    note.createdAt.atZone(ZoneId.systemDefault()).format(timeFormat) +
                        if (note.entryId != null) " · In journal" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete note",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Column(Modifier.padding(end = 12.dp)) {
                if (note.summary != null) {
                    Text(
                        "AI summary",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(note.summary, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    note.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (note.summary != null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else if (note.summary != null) 2 else 6,
                    overflow = TextOverflow.Ellipsis,
                )
                SummaryStatusRow(item, onSummarize)
            }
        }
    }
}

@Composable
private fun SummaryStatusRow(item: QuickNoteItem, onSummarize: () -> Unit) {
    when (val status = item.summaryStatus) {
        SummaryStatus.InProgress -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Summarizing…", style = MaterialTheme.typography.labelMedium)
        }
        is SummaryStatus.Failed -> Column(Modifier.padding(top = 8.dp)) {
            Text(
                status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onSummarize, contentPadding = PaddingValues(0.dp)) { Text("Retry summary") }
        }
        null -> if (item.canRequestSummary) {
            TextButton(onClick = onSummarize, contentPadding = PaddingValues(0.dp)) { Text("Summarize") }
        }
    }
}

@Composable
private fun CenteredMessage(title: String, detail: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
