package com.adam.insightjournal.search

import com.adam.insightjournal.data.model.JournalEntry
import com.adam.insightjournal.data.model.QuickNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class JournalSearchTest {

    private fun entry(id: String, title: String, body: String, day: Long = 0) =
        JournalEntry(id = id, title = title, body = body, entryDate = Instant.ofEpochSecond(day * 86_400))

    private fun note(id: String, text: String, day: Long = 0, entryId: String? = null) =
        QuickNote(id = id, text = text, createdAt = Instant.ofEpochSecond(day * 86_400), entryId = entryId)

    private val entries = listOf(
        entry("run", "Morning run", "Cold and clear. Ran the river loop.", day = 3),
        entry("hike", "Weekend hike", "Made it to the overlook before sunrise. Legs tired from the run on Friday.", day = 2),
        entry("plan", "Capstone planning", "Sketched out the journal app phases.", day = 1),
    )

    @Test
    fun `blank query finds nothing`() {
        assertEquals(emptyList<SearchHit>(), searchJournal("   ", entries, emptyList()))
    }

    @Test
    fun `matching ignores case and finds words inside longer words`() {
        val ids = searchJournal("PLAN", entries, emptyList()).map { it.id }
        assertEquals(listOf("plan"), ids)
    }

    @Test
    fun `every word must be present but in any order and any field`() {
        // "hike" is only in the title, "sunrise" only in the body.
        assertEquals(listOf("hike"), searchJournal("sunrise hike", entries, emptyList()).map { it.id })
        assertEquals(emptyList<String>(), searchJournal("sunrise capstone", entries, emptyList()).map { it.id })
    }

    @Test
    fun `a title match ranks above a body-only match`() {
        val ids = searchJournal("run", entries, emptyList()).map { it.id }
        assertEquals(listOf("run", "hike"), ids)
    }

    @Test
    fun `newest order ignores relevance`() {
        val ids = searchJournal("run", entries + entry("late", "Notes", "a short run", day = 9), emptyList(), SearchOrder.NEWEST)
            .map { it.id }
        assertEquals(listOf("late", "run", "hike"), ids)
    }

    @Test
    fun `quick notes are searched and keep their entry link`() {
        val notes = listOf(note("n1", "river was frozen", entryId = "run"), note("n2", "buy milk"))
        val hit = searchJournal("frozen", entries, notes).single()
        assertEquals(SearchHit.Kind.QUICK_NOTE, hit.kind)
        assertEquals("n1", hit.id)
        assertEquals("run", hit.linkedEntryId)
        assertNull(searchJournal("milk", entries, notes).single().linkedEntryId)
    }

    @Test
    fun `highlights mark each match in the title and the body`() {
        val hit = searchJournal("run", entries, emptyList()).first { it.id == "run" }
        assertEquals(listOf("run"), hit.title.highlights.map { hit.title.text.substring(it) })
        // "Ran" does not contain "run"; nothing in this body matches.
        assertEquals(emptyList<IntRange>(), hit.body.highlights)
    }

    @Test
    fun `overlapping words are highlighted once`() {
        val hit = searchJournal("sun sunrise", entries, emptyList()).single()
        assertEquals(listOf("sunrise"), hit.body.highlights.map { hit.body.text.substring(it) })
    }

    @Test
    fun `a long body is cut around the first match with the highlight still on the word`() {
        val body = "word ".repeat(100) + "needle in the haystack " + "tail ".repeat(100)
        val hit = searchJournal("needle", listOf(entry("long", "", body)), emptyList()).single()
        val snippet = hit.body
        assertTrue(snippet.text.length <= 162)
        assertTrue(snippet.text.startsWith("…") && snippet.text.endsWith("…"))
        assertEquals(listOf("needle"), snippet.highlights.map { snippet.text.substring(it) })
    }

    @Test
    fun `a title-only match shows the start of the body`() {
        val body = "First sentence of the entry. " + "more text ".repeat(40)
        val snippet = searchJournal("title", listOf(entry("t", "The title", body)), emptyList()).single().body
        assertTrue(snippet.text.startsWith("First sentence"))
        assertTrue(snippet.text.endsWith("…"))
        assertEquals(emptyList<IntRange>(), snippet.highlights)
    }

    @Test
    fun `line breaks in a snippet become spaces without moving highlights`() {
        val snippet = snippetAround("one\ntwo\tthree", listOf(4..6))
        assertEquals("one two three", snippet.text)
        assertEquals("two", snippet.text.substring(snippet.highlights.single()))
    }

    @Test
    fun `a match at the very end of a long text is kept in view`() {
        val text = "filler ".repeat(60) + "ending"
        val at = text.length - "ending".length
        val snippet = snippetAround(text, listOf(at until text.length))
        assertTrue(snippet.text.endsWith("ending"))
        assertEquals("ending", snippet.text.substring(snippet.highlights.single()))
    }

    @Test
    fun `merge joins touching and overlapping ranges`() {
        assertEquals(listOf(0..6, 10..12), mergeRanges(listOf(10..12, 0..2, 0..6, 3..5)))
    }

    @Test
    fun `query words are split on any whitespace and de-duplicated`() {
        assertEquals(listOf("Run", "hike"), parseQuery("  Run\thike  run "))
    }
}
