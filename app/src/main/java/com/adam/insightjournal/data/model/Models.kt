package com.adam.insightjournal.data.model

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * A full journal entry. Stored at users/{uid}/entries/{id}.
 *
 * The nullable fields at the bottom are filled in by later phases
 * (summary in Phase 2, embedding in Phase 3). They exist now so that
 * documents written today have the same shape as documents written later.
 */
data class JournalEntry(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    /** The day the entry is about (user can change it), not when it was typed. */
    val entryDate: Instant = Instant.now(),
    /** "2026-11". Derived from entryDate; stored so the web app can query by month. */
    val monthKey: String = monthKeyOf(entryDate),
    /** How the text got in: "typed", "quick_notes", "voice" or "ocr". */
    val source: String = SOURCE_TYPED,
    /** Quick notes this entry was drafted from (empty for typed entries). */
    val sourceNoteIds: List<String> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    /** How the day felt, [Mood.MIN]..[Mood.MAX], or null if the writer didn't say. */
    val mood: Int? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    // Phase 2/3
    val summary: String? = null,
    val embedding: List<Float>? = null,
    val embeddingModel: String? = null,
    /** True when the stored copy couldn't be decrypted; such an entry must not be edited or saved. */
    val unreadable: Boolean = false,
) {
    companion object {
        const val SOURCE_TYPED = "typed"
        const val SOURCE_QUICK_NOTES = "quick_notes"
    }
}

/** The five-point mood scale an entry can carry. Stored as the plain number. */
object Mood {
    const val MIN = 1
    const val MAX = 5
    val all: IntRange = MIN..MAX

    fun isValid(value: Int?): Boolean = value != null && value in all

    fun emoji(value: Int): String = when (value) {
        1 -> "😞"
        2 -> "😕"
        3 -> "😐"
        4 -> "🙂"
        else -> "😄"
    }

    fun label(value: Int): String = when (value) {
        1 -> "Awful"
        2 -> "Bad"
        3 -> "Okay"
        4 -> "Good"
        else -> "Great"
    }
}

/** A short note. Stored at users/{uid}/quickNotes/{id}. Used from Phase 2. */
data class QuickNote(
    val id: String = "",
    val text: String = "",
    val createdAt: Instant = Instant.now(),
    /** The journal entry this note was turned into, or null if it hasn't been used yet. */
    val entryId: String? = null,
    // Phase 2/3
    val summary: String? = null,
    val embedding: List<Float>? = null,
    val embeddingModel: String? = null,
    /** True when the stored copy couldn't be decrypted. */
    val unreadable: Boolean = false,
)

/** Metadata for a file in Cloud Storage. Embedded inside its JournalEntry. */
data class Attachment(
    val id: String = "",
    val fileName: String = "",
    val mimeType: String = "",
    /** Path in Cloud Storage: users/{uid}/attachments/{entryId}/{id} */
    val storagePath: String = "",
    val sizeBytes: Long = 0,
)

fun monthKeyOf(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    YearMonth.from(instant.atZone(zone)).toString()
