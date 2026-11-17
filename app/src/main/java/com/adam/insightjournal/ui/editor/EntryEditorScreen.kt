package com.adam.insightjournal.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adam.insightjournal.data.model.Mood
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditorScreen(viewModel: EntryEditorViewModel, onDone: () -> Unit) {
    val state = viewModel.uiState
    var showDatePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    val onBack = { if (viewModel.hasUnsavedChanges) showDiscardConfirm = true else onDone() }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New entry" else "Edit entry") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!state.isNew && state.loadError == null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete entry")
                        }
                    }
                    IconButton(
                        onClick = { viewModel.save(); onDone() },
                        enabled = state.canSave,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Save entry")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding).imePadding()) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.loadError != null -> Text(
                    state.loadError,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                else -> EditorForm(state, viewModel, onPickDate = { showDatePicker = true })
            }
        }
    }

    if (showDatePicker) {
        EntryDatePicker(
            initial = state.date,
            onPicked = { viewModel.onDateChange(it); showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }
    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete this entry?",
            text = "This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { viewModel.delete(); onDone() },
            onDismiss = { showDeleteConfirm = false },
        )
    }
    if (showDiscardConfirm) {
        ConfirmDialog(
            title = "Discard changes?",
            text = "Your edits to this entry haven't been saved.",
            confirmLabel = "Discard",
            onConfirm = onDone,
            onDismiss = { showDiscardConfirm = false },
        )
    }
}

@Composable
private fun EditorForm(state: EditorUiState, viewModel: EntryEditorViewModel, onPickDate: () -> Unit) {
    val plainFieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
    )
    Column(Modifier.fillMaxSize()) {
        if (state.draftedFromNoteCount > 0) {
            val count = state.draftedFromNoteCount
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(
                    "AI draft from ${if (count == 1) "1 quick note" else "$count quick notes"}. " +
                        "Edit anything, then save to add it to your journal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        AssistChip(
            onClick = onPickDate,
            label = { Text(state.date.format(dateFormat)) },
            leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null, Modifier.size(18.dp)) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        MoodPicker(selected = state.mood, onTap = viewModel::onMoodTap)
        TextField(
            value = state.title,
            onValueChange = viewModel::onTitleChange,
            placeholder = { Text("Title", style = MaterialTheme.typography.headlineSmall) },
            textStyle = MaterialTheme.typography.headlineSmall,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = plainFieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            value = state.body,
            onValueChange = viewModel::onBodyChange,
            placeholder = { Text("What's on your mind?") },
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = plainFieldColors,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/** Five faces; the picked one is highlighted and named. Optional: an entry can have no mood. */
@Composable
private fun MoodPicker(selected: Int?, onTap: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Mood.all.forEach { value ->
            val isSelected = value == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    )
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onTap(value) })
                    .semantics { contentDescription = "Mood: ${Mood.label(value)}" },
            ) {
                Text(
                    Mood.emoji(value),
                    fontSize = 22.sp,
                    // Unpicked faces are dimmed so the picked one stands out.
                    modifier = Modifier.alpha(if (selected == null || isSelected) 1f else 0.45f),
                )
            }
        }
        Text(
            selected?.let(Mood::label) ?: "How did the day feel?",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDatePicker(initial: LocalDate, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    // The Material date picker works in UTC milliseconds at midnight.
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = pickerState.selectedDateMillis
                if (millis == null) onDismiss()
                else onPicked(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState)
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
