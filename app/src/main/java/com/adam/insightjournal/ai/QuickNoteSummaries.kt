package com.adam.insightjournal.ai

import android.util.Log
import com.adam.insightjournal.data.QuickNoteRepository
import com.adam.insightjournal.data.model.QuickNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SummaryStatus {
    data object InProgress : SummaryStatus
    data class Failed(val message: String) : SummaryStatus
}

/**
 * Decides when a quick note gets an AI summary and runs the request.
 *
 * The caching rule lives here: a note is summarized at most once. The result is
 * stored on the note in Firestore, and a note that already has a summary is
 * never sent to the API again. Short notes are never sent at all.
 *
 * Runs on the app-wide scope so a request finishes even if the user leaves the screen.
 */
class QuickNoteSummaries(
    private val repository: QuickNoteRepository,
    private val ai: JournalAi?,
    private val settings: AiSettings,
    private val scope: CoroutineScope,
) {
    /** False when no API key is configured. */
    val isEnabled: Boolean get() = ai != null

    private val _status = MutableStateFlow<Map<String, SummaryStatus>>(emptyMap())
    /** Notes with a summary request running or failed, by note id. Absent = nothing to show. */
    val status: StateFlow<Map<String, SummaryStatus>> = _status.asStateFlow()

    fun needsSummary(note: QuickNote): Boolean =
        note.summary == null && note.text.length >= MIN_CHARS_TO_SUMMARIZE

    /** Does nothing while AI features are switched off, so new notes are never sent automatically. */
    fun summarize(note: QuickNote) {
        val ai = ai ?: return
        if (!settings.enabled.value) return
        if (note.unreadable || !needsSummary(note) || _status.value[note.id] is SummaryStatus.InProgress) return

        _status.update { it + (note.id to SummaryStatus.InProgress) }
        scope.launch {
            try {
                val summary = ai.summarize(note.text)
                repository.setSummary(note, summary)
                _status.update { it - note.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("QuickNoteSummaries", "Summary failed for note ${note.id}", e)
                val message = (e as? AiException)?.message ?: "Couldn't summarize this note."
                _status.update { it + (note.id to SummaryStatus.Failed(message)) }
            }
        }
    }

    companion object {
        /** Below this length the note is already its own summary. */
        const val MIN_CHARS_TO_SUMMARIZE = 200
    }
}
