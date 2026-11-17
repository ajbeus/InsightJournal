package com.adam.insightjournal.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adam.insightjournal.ai.AiException
import com.adam.insightjournal.ai.EntryDraftHolder
import com.adam.insightjournal.ai.JournalAi
import com.adam.insightjournal.ai.PendingEntryDraft
import com.adam.insightjournal.ai.QuickNoteSummaries
import com.adam.insightjournal.ai.SummaryStatus
import com.adam.insightjournal.data.QuickNoteRepository
import com.adam.insightjournal.data.model.QuickNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** A note plus what the summary row should show for it. */
data class QuickNoteItem(
    val note: QuickNote,
    val summaryStatus: SummaryStatus?,
    /** True when the note is long enough to summarize but has no summary and no request running. */
    val canRequestSummary: Boolean,
)

/** One day's notes, newest first. */
data class DaySection(
    val date: LocalDate,
    val notes: List<QuickNoteItem>,
    /** Notes not yet turned into a journal entry. */
    val pendingCount: Int,
    val isDrafting: Boolean,
)

sealed interface QuickNotesUiState {
    data object Loading : QuickNotesUiState
    data class Loaded(val days: List<DaySection>) : QuickNotesUiState
    data class Error(val message: String) : QuickNotesUiState
}

class QuickNotesViewModel(
    private val repository: QuickNoteRepository,
    private val summaries: QuickNoteSummaries,
    private val ai: JournalAi?,
    private val draftHolder: EntryDraftHolder,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    val aiEnabled: Boolean get() = ai != null

    /** The day a draft is being written for, if any. Only one at a time. */
    private val draftingDay = MutableStateFlow<LocalDate?>(null)

    val uiState: StateFlow<QuickNotesUiState> =
        combine(repository.observeNotes(), summaries.status, draftingDay) { notes, status, drafting ->
            val items = notes.map { note ->
                QuickNoteItem(
                    note = note,
                    summaryStatus = status[note.id],
                    canRequestSummary = summaries.isEnabled &&
                        summaries.needsSummary(note) &&
                        status[note.id] !is SummaryStatus.InProgress,
                )
            }
            QuickNotesUiState.Loaded(groupByDay(items, drafting)) as QuickNotesUiState
        }
            .catch { emit(QuickNotesUiState.Error(it.localizedMessage ?: "Couldn't load your notes.")) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuickNotesUiState.Loading)

    private val draftErrors = Channel<String>(Channel.BUFFERED)
    /** Messages to show in a snackbar. */
    val messages: Flow<String> = merge(repository.writeErrors, draftErrors.receiveAsFlow())

    private val _draftReady = Channel<Unit>(Channel.BUFFERED)
    /** Fires when a draft is waiting in the [EntryDraftHolder]; the screen then opens the editor. */
    val draftReady: Flow<Unit> = _draftReady.receiveAsFlow()

    private fun groupByDay(items: List<QuickNoteItem>, drafting: LocalDate?): List<DaySection> =
        items
            .groupBy { it.note.createdAt.atZone(zone).toLocalDate() }
            .map { (date, dayItems) ->
                DaySection(
                    date = date,
                    notes = dayItems,
                    pendingCount = dayItems.count { it.note.entryId == null },
                    isDrafting = date == drafting,
                )
            }
            .sortedByDescending { it.date }

    /** Saves first, then summarizes. The note is safe even if the summary fails. */
    fun addNote(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val note = repository.addNote(trimmed)
        summaries.summarize(note)
    }

    fun requestSummary(note: QuickNote) = summaries.summarize(note)

    fun deleteNote(id: String) = repository.deleteNote(id)

    /**
     * Asks the AI to draft a journal entry from the day's notes that haven't
     * been used yet, then hands the draft to the editor. Nothing is written
     * to the journal here; that happens only when the user saves in the editor.
     */
    fun createEntryForDay(date: LocalDate) {
        val ai = ai ?: return
        if (draftingDay.value != null) return
        val day = (uiState.value as? QuickNotesUiState.Loaded)?.days?.find { it.date == date } ?: return
        val notes = day.notes.map { it.note }
            .filter { it.entryId == null }
            .sortedBy { it.createdAt }
        if (notes.isEmpty()) return

        draftingDay.value = date
        viewModelScope.launch {
            try {
                val draft = ai.draftEntry(date, notes)
                draftHolder.put(PendingEntryDraft(date, draft.title, draft.body, notes.map { it.id }))
                _draftReady.send(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                draftErrors.send((e as? AiException)?.message ?: "Couldn't write the draft. Try again.")
            } finally {
                draftingDay.value = null
            }
        }
    }
}
