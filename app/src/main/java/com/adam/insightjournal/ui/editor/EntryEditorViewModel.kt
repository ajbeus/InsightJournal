package com.adam.insightjournal.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adam.insightjournal.ai.PendingEntryDraft
import com.adam.insightjournal.data.JournalRepository
import com.adam.insightjournal.data.QuickNoteRepository
import com.adam.insightjournal.data.model.JournalEntry
import com.adam.insightjournal.data.model.monthKeyOf
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class EditorUiState(
    val isLoading: Boolean = false,
    val isNew: Boolean = true,
    val title: String = "",
    val body: String = "",
    val date: LocalDate = LocalDate.now(),
    /** 1..5 (see Mood), or null when no mood is picked. */
    val mood: Int? = null,
    val loadError: String? = null,
    /** How many quick notes the AI draft was written from; 0 for a normal entry. */
    val draftedFromNoteCount: Int = 0,
) {
    val canSave: Boolean get() = !isLoading && loadError == null && (title.isNotBlank() || body.isNotBlank())
}

/**
 * [entryId] is null when writing a new entry. [draft] is an AI draft made from
 * quick notes: it pre-fills a new entry, and its notes are linked on save.
 */
class EntryEditorViewModel(
    private val repository: JournalRepository,
    private val quickNotes: QuickNoteRepository,
    private val entryId: String?,
    private val draft: PendingEntryDraft? = null,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    /** The entry as last loaded; null for a new entry. Keeps fields this screen doesn't edit. */
    private var original: JournalEntry? = null

    var uiState by mutableStateOf(EditorUiState(isLoading = entryId != null, isNew = entryId == null))
        private set

    /** What the form looked like when it was opened, for the "discard changes?" check. */
    private var pristine = uiState

    val hasUnsavedChanges: Boolean
        get() = uiState.title != pristine.title || uiState.body != pristine.body || uiState.date != pristine.date ||
            uiState.mood != pristine.mood

    init {
        if (entryId != null) {
            load(entryId)
        } else if (draft != null) {
            // pristine stays empty, so leaving without saving asks before discarding the draft.
            uiState = uiState.copy(
                title = draft.title,
                body = draft.body,
                date = draft.date,
                draftedFromNoteCount = draft.noteIds.size,
            )
        }
    }

    private fun load(id: String) {
        viewModelScope.launch {
            uiState = try {
                val entry = repository.getEntry(id)
                if (entry == null) {
                    uiState.copy(isLoading = false, loadError = "This entry no longer exists.")
                } else if (entry.unreadable) {
                    // Saving would overwrite the stored copy, so it can't even be opened for editing.
                    uiState.copy(isLoading = false, loadError = "This entry couldn't be decrypted, so it can't be opened.")
                } else {
                    original = entry
                    uiState.copy(
                        isLoading = false,
                        title = entry.title,
                        body = entry.body,
                        date = entry.entryDate.atZone(zone).toLocalDate(),
                        mood = entry.mood,
                    )
                }
            } catch (e: Exception) {
                uiState.copy(isLoading = false, loadError = e.localizedMessage ?: "Couldn't load this entry.")
            }
            pristine = uiState
        }
    }

    fun onTitleChange(value: String) { uiState = uiState.copy(title = value) }
    fun onBodyChange(value: String) { uiState = uiState.copy(body = value) }
    fun onDateChange(value: LocalDate) { uiState = uiState.copy(date = value) }

    /** Tapping the mood that is already picked clears it. */
    fun onMoodTap(value: Int) { uiState = uiState.copy(mood = if (uiState.mood == value) null else value) }

    fun save() {
        if (!uiState.canSave) return
        val now = Instant.now()
        val base = original ?: JournalEntry(
            createdAt = now,
            source = if (draft != null) JournalEntry.SOURCE_QUICK_NOTES else JournalEntry.SOURCE_TYPED,
            sourceNoteIds = draft?.noteIds.orEmpty(),
        )
        // Keep the original time of day; only the calendar date is editable.
        val timeOfDay = base.entryDate.atZone(zone).toLocalTime()
        val entryDate = uiState.date.atTime(timeOfDay).atZone(zone).toInstant()
        val savedId = repository.saveEntry(
            base.copy(
                title = uiState.title.trim(),
                body = uiState.body.trim(),
                entryDate = entryDate,
                mood = uiState.mood,
                monthKey = monthKeyOf(entryDate, zone),
                updatedAt = now,
            )
        )
        // Only now, once the entry exists, are the notes marked as used.
        if (original == null && draft != null) quickNotes.setEntryLink(draft.noteIds, savedId)
    }

    fun delete() {
        val id = entryId ?: return
        repository.deleteEntry(id)
        // Free the notes this entry came from so they can be turned into an entry again.
        original?.sourceNoteIds?.takeIf { it.isNotEmpty() }?.let { quickNotes.setEntryLink(it, null) }
    }
}
