package com.adam.insightjournal.ui.entries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adam.insightjournal.data.JournalRepository
import com.adam.insightjournal.data.model.JournalEntry
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import java.time.ZoneId

/** One month's worth of entries, newest first. */
data class MonthSection(val month: YearMonth, val entries: List<JournalEntry>)

sealed interface EntryListUiState {
    data object Loading : EntryListUiState
    data class Loaded(val months: List<MonthSection>) : EntryListUiState
    data class Error(val message: String) : EntryListUiState
}

class EntryListViewModel(repository: JournalRepository) : ViewModel() {

    val uiState: StateFlow<EntryListUiState> = repository.observeEntries()
        .map<List<JournalEntry>, EntryListUiState> { EntryListUiState.Loaded(groupByMonth(it)) }
        .catch { emit(EntryListUiState.Error(it.localizedMessage ?: "Couldn't load your entries.")) }
        // Stop listening to Firestore 5s after the screen goes away.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntryListUiState.Loading)

    val writeErrors: SharedFlow<String> = repository.writeErrors
}

/** Entries arrive newest first, so the groups come out newest month first too. */
fun groupByMonth(
    entries: List<JournalEntry>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<MonthSection> =
    entries
        .groupBy { YearMonth.from(it.entryDate.atZone(zone)) }
        .map { (month, monthEntries) -> MonthSection(month, monthEntries) }
        .sortedByDescending { it.month }
