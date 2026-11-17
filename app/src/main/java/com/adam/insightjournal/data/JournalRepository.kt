package com.adam.insightjournal.data

import com.adam.insightjournal.data.model.JournalEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

interface JournalRepository {
    /** All of the current user's entries, newest first. Emits again on every change. */
    fun observeEntries(): Flow<List<JournalEntry>>

    suspend fun getEntry(id: String): JournalEntry?

    /**
     * Creates the entry (blank id) or overwrites it. Returns the id.
     *
     * Not a suspend function on purpose: Firestore applies the write to its
     * local cache immediately and syncs when online, so the UI should not
     * wait for the server. Failures arrive on [writeErrors].
     */
    fun saveEntry(entry: JournalEntry): String

    fun deleteEntry(id: String)

    /** Messages for writes the server rejected (e.g. security rules). */
    val writeErrors: SharedFlow<String>
}

/** Demo-mode storage used when Firebase isn't configured. Lost when the app closes. */
class InMemoryJournalRepository : JournalRepository {

    private val entries = MutableStateFlow(sampleEntries())

    override val writeErrors: SharedFlow<String> = MutableSharedFlow<String>().asSharedFlow()

    override fun observeEntries(): Flow<List<JournalEntry>> = entries

    override suspend fun getEntry(id: String): JournalEntry? = entries.value.find { it.id == id }

    override fun saveEntry(entry: JournalEntry): String {
        val saved = if (entry.id.isEmpty()) entry.copy(id = UUID.randomUUID().toString()) else entry
        entries.update { list ->
            (list.filterNot { it.id == saved.id } + saved).sortedByDescending { it.entryDate }
        }
        return saved.id
    }

    override fun deleteEntry(id: String) {
        entries.update { list -> list.filterNot { it.id == id } }
    }

    private fun sampleEntries(): List<JournalEntry> {
        val now = Instant.now()
        fun sample(daysAgo: Long, title: String, body: String, mood: Int? = null) = JournalEntry(
            id = UUID.randomUUID().toString(),
            title = title,
            body = body,
            entryDate = now.minus(daysAgo, ChronoUnit.DAYS),
            mood = mood,
        )
        return listOf(
            sample(0, "Demo mode", "Firebase isn't connected yet, so these sample entries live in memory and reset when the app restarts.", 4),
            sample(1, "Slow Sunday", "Coffee on the porch, then finally cleaned out the garage. Called Mom in the evening.", 4),
            sample(2, "Stuck on the bug", "Spent most of the day chasing an off-by-one in the search snippets. Frustrating, but I found it right before dinner.", 2),
            sample(3, "Morning run", "Cold and clear. Ran the river loop and felt better than I expected.", 5),
            sample(5, "Midterm week", "Two exams back to back. Tired, but the algorithms one went well.", 3),
            sample(6, "Dinner with Sam", "Tried the new ramen place downtown. We talked about the capstone demo and summer plans.", 5),
            sample(9, "Rainy day", "Stayed in and read. Didn't get much done and felt a bit flat about it.", 2),
            sample(12, "Study group", "Went through the practice problems together. Explaining recursion to someone else helped me too.", 4),
            sample(16, "Long shift", "Covered an extra shift at work. Exhausted.", 2),
            sample(20, "First snow", "Walked to campus in the snow. Everything was quiet.", 4),
            sample(24, "Presentation", "Gave the project update in class. Nervous at the start, fine by the end.", 3),
            sample(35, "Capstone planning", "Sketched out the journal app phases. RAG search is the part I most want to get right.", 4),
            sample(70, "Weekend hike", "Made it to the overlook before sunrise.", 5),
        )
    }
}
