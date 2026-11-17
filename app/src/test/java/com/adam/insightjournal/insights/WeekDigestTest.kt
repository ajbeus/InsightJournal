package com.adam.insightjournal.insights

import com.adam.insightjournal.ai.weekPrompt
import com.adam.insightjournal.data.model.JournalEntry
import com.adam.insightjournal.data.model.QuickNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class WeekDigestTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 16)

    private fun entry(id: String, daysAgo: Long, title: String = "", body: String = "", mood: Int? = null) =
        JournalEntry(
            id = id,
            title = title,
            body = body,
            entryDate = today.minusDays(daysAgo).atTime(9, 0).toInstant(zone),
            mood = mood,
        )

    private fun note(id: String, daysAgo: Long, text: String, entryId: String? = null) =
        QuickNote(id = id, text = text, createdAt = today.minusDays(daysAgo).atTime(15, 0).toInstant(zone), entryId = entryId)

    private fun digest(entries: List<JournalEntry> = emptyList(), notes: List<QuickNote> = emptyList()) =
        weekDigest(entries, notes, today, zone)

    @Test
    fun `the week is today and the six days before, oldest first`() {
        val week = digest(listOf(entry("a", 0), entry("b", 6), entry("c", 7)))
        assertEquals(today.minusDays(6), week.start)
        assertEquals(today, week.end)
        assertEquals(listOf("b", "a"), week.entries.map { it.id })
    }

    @Test
    fun `notes already turned into an entry are left out`() {
        val week = digest(notes = listOf(note("loose", 1, "call the bank"), note("used", 1, "x", entryId = "a"), note("old", 8, "y")))
        assertEquals(listOf("loose"), week.looseNotes.map { it.id })
    }

    @Test
    fun `counts words, days and average mood`() {
        val week = digest(
            entries = listOf(
                entry("a", 0, title = "Two words", body = "three more words", mood = 5),
                entry("b", 0, body = "same   day\nagain", mood = 2),
                entry("c", 3, body = "unrated"),
            ),
            notes = listOf(note("n", 5, "a loose note")),
        )
        assertEquals(2 + 3 + 3 + 1 + 3, week.wordCount)
        assertEquals(3, week.daysWritten)
        assertEquals(3.5, week.averageMood!!, 1e-9)
    }

    @Test
    fun `an empty week is empty and has no mood`() {
        val week = digest(listOf(entry("old", 30, body = "long ago", mood = 4)))
        assertTrue(week.isEmpty)
        assertNull(week.averageMood)
        assertEquals(0, week.wordCount)
    }

    @Test
    fun `fingerprint is stable for the same material in any order`() {
        val a = entry("a", 1, body = "one")
        val b = entry("b", 2, body = "two")
        assertEquals(digest(listOf(a, b)).fingerprint, digest(listOf(b, a)).fingerprint)
    }

    @Test
    fun `fingerprint changes when text, mood or the set of items changes`() {
        val base = digest(listOf(entry("a", 1, body = "one")), listOf(note("n", 1, "note")))
        val changes = listOf(
            digest(listOf(entry("a", 1, body = "one edited")), listOf(note("n", 1, "note"))),
            digest(listOf(entry("a", 1, body = "one", mood = 4)), listOf(note("n", 1, "note"))),
            digest(listOf(entry("a", 1, body = "one")), listOf(note("n", 1, "note edited"))),
            digest(listOf(entry("a", 1, body = "one"))),
            digest(listOf(entry("a", 1, body = "one"), entry("b", 2, body = "two")), listOf(note("n", 1, "note"))),
        )
        changes.forEach { assertNotEquals(base.fingerprint, it.fingerprint) }
    }

    @Test
    fun `fingerprint ignores writing outside the week`() {
        val inWeek = entry("a", 1, body = "one")
        assertEquals(digest(listOf(inWeek)).fingerprint, digest(listOf(inWeek, entry("old", 20, body = "x"))).fingerprint)
    }

    @Test
    fun `the prompt lists entries then loose notes with dates and moods`() {
        val week = digest(
            entries = listOf(entry("a", 1, title = "Dinner", body = "Ramen with Sam.", mood = 5), entry("b", 0, body = "No title.")),
            notes = listOf(note("n", 2, "call the bank")),
        )
        assertEquals(
            """
            <entry date="Sunday, November 15" mood="5">
            Dinner
            Ramen with Sam.
            </entry>
            <entry date="Monday, November 16">
            No title.
            </entry>
            <note date="Saturday, November 14">
            call the bank
            </note>
            """.trimIndent(),
            weekPrompt(week, zone),
        )
    }

    @Test
    fun `very long texts are cut in the prompt`() {
        val prompt = weekPrompt(digest(listOf(entry("a", 0, body = "x".repeat(10_000)))), zone)
        assertTrue(prompt.length < 4_200)
        assertFalse(prompt.contains("x".repeat(4_001)))
    }
}
