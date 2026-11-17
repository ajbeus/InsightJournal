package com.adam.insightjournal.ui.insights

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adam.insightjournal.ai.AiException
import com.adam.insightjournal.ai.JournalAi
import com.adam.insightjournal.data.JournalRepository
import com.adam.insightjournal.data.QuickNoteRepository
import com.adam.insightjournal.data.WeeklySummaryRepository
import com.adam.insightjournal.insights.MoodStats
import com.adam.insightjournal.insights.Streaks
import com.adam.insightjournal.insights.WeekDigest
import com.adam.insightjournal.insights.WeeklySummary
import com.adam.insightjournal.insights.journalingStreaks
import com.adam.insightjournal.insights.moodStats
import com.adam.insightjournal.insights.weekDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/** How far back the mood charts look. */
enum class MoodRange(val label: String, val days: Long) {
    WEEK("7 days", 7),
    MONTH("30 days", 30),
    QUARTER("90 days", 90),
}

sealed interface InsightsUiState {
    data object Loading : InsightsUiState
    data class Error(val message: String) : InsightsUiState
    data class Loaded(
        val week: WeekDigest,
        /** The last AI summary that was generated, which may be about older text; see [summaryIsCurrent]. */
        val summary: WeeklySummary?,
        val moodRange: MoodRange,
        val mood: MoodStats,
        val streaks: Streaks,
    ) : InsightsUiState {
        /** True when [summary] was written from exactly what the past week contains now. */
        val summaryIsCurrent: Boolean get() = summary?.fingerprint == week.fingerprint
    }
}

/** The state of the "summarize my week" request, separate from the data it is about. */
sealed interface SummaryRequest {
    data object Idle : SummaryRequest
    data object Running : SummaryRequest
    data class Failed(val message: String) : SummaryRequest
}

class InsightsViewModel(
    journal: JournalRepository,
    quickNotes: QuickNoteRepository,
    private val summaries: WeeklySummaryRepository,
    private val ai: JournalAi?,
) : ViewModel() {

    /** False when no API key is configured; the stats still work without it. */
    val aiEnabled: Boolean get() = ai != null

    private val moodRange = MutableStateFlow(MoodRange.MONTH)

    private val _summaryRequest = MutableStateFlow<SummaryRequest>(SummaryRequest.Idle)
    val summaryRequest: StateFlow<SummaryRequest> = _summaryRequest.asStateFlow()

    val uiState: StateFlow<InsightsUiState> =
        combine(journal.observeEntries(), quickNotes.observeNotes(), summaries.observe(), moodRange) {
                entries, notes, summary, range ->
            val today = LocalDate.now()
            InsightsUiState.Loaded(
                week = weekDigest(entries, notes, today),
                summary = summary,
                moodRange = range,
                mood = moodStats(entries, from = today.minusDays(range.days - 1), to = today),
                streaks = journalingStreaks(entries, today),
            ) as InsightsUiState
        }
            .catch { emit(InsightsUiState.Error(it.localizedMessage ?: "Couldn't load your journal.")) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState.Loading)

    fun onMoodRangeChange(range: MoodRange) { moodRange.value = range }

    /**
     * Asks the model for a recap of the past week and stores it. Only ever
     * called from the button: a summary costs an API call, so it is never
     * generated just because the screen was opened or an entry was edited.
     */
    fun summarizeWeek() {
        val ai = ai ?: return
        val week = (uiState.value as? InsightsUiState.Loaded)?.week ?: return
        if (week.isEmpty || _summaryRequest.value == SummaryRequest.Running) return

        _summaryRequest.value = SummaryRequest.Running
        viewModelScope.launch {
            _summaryRequest.value = try {
                val text = ai.summarizeWeek(week)
                summaries.save(WeeklySummary(text, week.fingerprint, week.start, week.end, Instant.now()))
                SummaryRequest.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("InsightsViewModel", "Weekly summary failed", e)
                SummaryRequest.Failed((e as? AiException)?.message ?: "Couldn't write the summary. Try again.")
            }
        }
    }
}
