package com.adam.insightjournal.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adam.insightjournal.data.JournalRepository
import com.adam.insightjournal.data.QuickNoteRepository
import com.adam.insightjournal.search.SearchHit
import com.adam.insightjournal.search.SearchOrder
import com.adam.insightjournal.search.searchJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

sealed interface SearchResults {
    /** Nothing typed yet. */
    data object Idle : SearchResults
    data object Loading : SearchResults
    data class Found(val hits: List<SearchHit>) : SearchResults
    data class Error(val message: String) : SearchResults
}

/**
 * Searches the entries and quick notes the repositories already keep loaded.
 * Because the result is derived from the live flows, it updates by itself if an
 * entry changes while the screen is open.
 */
class SearchViewModel(
    journal: JournalRepository,
    quickNotes: QuickNoteRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _order = MutableStateFlow(SearchOrder.BEST_MATCH)
    val order: StateFlow<SearchOrder> = _order.asStateFlow()

    val results: StateFlow<SearchResults> =
        combine(journal.observeEntries(), quickNotes.observeNotes(), _query, _order) { entries, notes, query, order ->
            if (query.isBlank()) SearchResults.Idle
            else SearchResults.Found(searchJournal(query, entries, notes, order))
        }
            // The scan is cheap, but there is no reason to do it on the UI thread.
            .flowOn(Dispatchers.Default)
            .catch { emit(SearchResults.Error(it.localizedMessage ?: "Couldn't search your journal.")) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults.Loading)

    fun onQueryChange(value: String) { _query.value = value }
    fun onOrderChange(value: SearchOrder) { _order.value = value }
}
