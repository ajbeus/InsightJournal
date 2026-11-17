package com.adam.insightjournal.insights

import com.adam.insightjournal.data.model.JournalEntry
import com.adam.insightjournal.data.model.Mood
import com.adam.insightjournal.data.model.QuickNote
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How many days "the past week" covers, today included. */
const val WEEK_DAYS = 7L

/**
 * What was written in the past week: the material for the weekly summary and
 * the numbers shown next to it. Built by [weekDigest].
 */
data class WeekDigest(
    val start: LocalDate,
    val end: LocalDate,
    /** The week's entries, oldest first. */
    val entries: List<JournalEntry>,
    /** The week's quick notes that were not turned into an entry (those would be counted twice), oldest first. */
    val looseNotes: List<QuickNote>,
    val wordCount: Int,
    /** Days of the week with at least one entry or loose note. */
    val daysWritten: Int,
    val averageMood: Double?,
    /**
     * Identifies this exact material. A stored summary carries the fingerprint
     * it was written from; when the two differ, the text has changed since.
     */
    val fingerprint: String,
) {
    val isEmpty: Boolean get() = entries.isEmpty() && looseNotes.isEmpty()
}

fun weekDigest(
    entries: List<JournalEntry>,
    notes: List<QuickNote>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): WeekDigest {
    val start = today.minusDays(WEEK_DAYS - 1)
    fun Instant.day(): LocalDate = atZone(zone).toLocalDate()

    val weekEntries = entries.filter { it.entryDate.day() in start..today }.sortedBy { it.entryDate }
    val looseNotes = notes
        .filter { it.entryId == null && it.createdAt.day() in start..today }
        .sortedBy { it.createdAt }
    val moods = weekEntries.mapNotNull { it.mood?.takeIf(Mood::isValid) }

    return WeekDigest(
        start = start,
        end = today,
        entries = weekEntries,
        looseNotes = looseNotes,
        wordCount = weekEntries.sumOf { countWords(it.title) + countWords(it.body) } +
            looseNotes.sumOf { countWords(it.text) },
        daysWritten = (weekEntries.map { it.entryDate.day() } + looseNotes.map { it.createdAt.day() }).distinct().size,
        averageMood = if (moods.isEmpty()) null else moods.average(),
        fingerprint = fingerprintOf(weekEntries, looseNotes),
    )
}

internal fun countWords(text: String): Int = text.split(Regex("\\s+")).count { it.isNotEmpty() }

/**
 * Changes whenever an entry or note is added, removed or edited. The text
 * itself is hashed (rather than trusting updatedAt) so the summary is only
 * considered out of date when what the model would read is different.
 */
private fun fingerprintOf(entries: List<JournalEntry>, notes: List<QuickNote>): String {
    val parts = entries.map { "e:${it.id}:${it.mood}:${it.title.hashCode()}:${it.body.hashCode()}" } +
        notes.map { "n:${it.id}:${it.text.hashCode()}" }
    return parts.sorted().joinToString("|").hashCode().toUInt().toString(16)
}

/** An AI-written recap of a week, as stored. */
data class WeeklySummary(
    val text: String,
    /** [WeekDigest.fingerprint] of the material it was written from. */
    val fingerprint: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val generatedAt: Instant,
)
