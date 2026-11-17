package com.adam.insightjournal.search

import com.adam.insightjournal.data.model.JournalEntry
import com.adam.insightjournal.data.model.QuickNote
import java.time.Instant

/**
 * Keyword search over the journal, done in memory on the entries and notes the
 * app has already loaded. No network and no index: a personal journal is small
 * enough that scanning every text on each keystroke is instant.
 *
 * This is deliberately plain text matching. The semantic (embeddings) search
 * planned for Phase 3 answers a different question ("entries about feeling
 * stuck") and can sit beside this as a second source of [SearchHit]s.
 */

/** Text cut down to the part worth showing, with the places to highlight in it. */
data class Snippet(val text: String, val highlights: List<IntRange>)

data class SearchHit(
    val kind: Kind,
    /** Entry id or quick note id, depending on [kind]. */
    val id: String,
    /** For a quick note: the journal entry it was turned into, if any. */
    val linkedEntryId: String?,
    val date: Instant,
    /** Empty for quick notes and untitled entries. */
    val title: Snippet,
    val body: Snippet,
    /** Higher is a better match. Only meaningful compared with other hits of the same search. */
    val score: Int,
) {
    enum class Kind { ENTRY, QUICK_NOTE }
}

enum class SearchOrder { BEST_MATCH, NEWEST }

/** Splits what the user typed into the words to look for. */
fun parseQuery(query: String): List<String> =
    query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

/**
 * Finds everything that contains every word of [query] (in any order, ignoring
 * case, matching inside longer words so "run" finds "running").
 */
fun searchJournal(
    query: String,
    entries: List<JournalEntry>,
    notes: List<QuickNote>,
    order: SearchOrder = SearchOrder.BEST_MATCH,
): List<SearchHit> {
    val terms = parseQuery(query)
    if (terms.isEmpty()) return emptyList()

    val hits = entries.mapNotNull { entry ->
        match(terms, title = entry.title, body = entry.body)?.let { m ->
            SearchHit(SearchHit.Kind.ENTRY, entry.id, null, entry.entryDate, m.title, m.body, m.score)
        }
    } + notes.mapNotNull { note ->
        match(terms, title = "", body = note.text)?.let { m ->
            SearchHit(SearchHit.Kind.QUICK_NOTE, note.id, note.entryId, note.createdAt, m.title, m.body, m.score)
        }
    }

    return when (order) {
        SearchOrder.BEST_MATCH -> hits.sortedWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.date })
        SearchOrder.NEWEST -> hits.sortedByDescending { it.date }
    }
}

private class Match(val title: Snippet, val body: Snippet, val score: Int)

private fun match(terms: List<String>, title: String, body: String): Match? {
    var score = 0
    val titleRanges = mutableListOf<IntRange>()
    val bodyRanges = mutableListOf<IntRange>()
    for (term in terms) {
        val inTitle = findAll(title, term)
        val inBody = findAll(body, term)
        if (inTitle.isEmpty() && inBody.isEmpty()) return null
        titleRanges += inTitle
        bodyRanges += inBody
        // A word in the title says more about the entry than one in the text, and
        // the tenth repeat of a word says little more than the third.
        score += TITLE_WEIGHT * inTitle.size.coerceAtMost(1) + inBody.size.coerceAtMost(MAX_COUNTED_REPEATS)
        if (inTitle.any { isWholeWord(title, it) } || inBody.any { isWholeWord(body, it) }) score += WHOLE_WORD_BONUS
    }
    return Match(
        title = Snippet(title, mergeRanges(titleRanges)),
        body = snippetAround(body, mergeRanges(bodyRanges)),
        score = score,
    )
}

/** Every place [term] occurs in [text], ignoring case. Occurrences don't overlap. */
internal fun findAll(text: String, term: String): List<IntRange> {
    if (term.isEmpty()) return emptyList()
    val found = mutableListOf<IntRange>()
    var from = 0
    while (true) {
        val at = text.indexOf(term, from, ignoreCase = true)
        if (at == -1) return found
        found += at until at + term.length
        from = at + term.length
    }
}

private fun isWholeWord(text: String, range: IntRange): Boolean {
    val before = text.getOrNull(range.first - 1)
    val after = text.getOrNull(range.last + 1)
    return (before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())
}

/** Sorts ranges and joins the ones that touch or overlap ("sun" and "sunset" in the same place). */
internal fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
    val merged = mutableListOf<IntRange>()
    for (range in ranges.sortedBy { it.first }) {
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
}

/**
 * Cuts [text] to about [maxLength] characters around the first match (or the
 * start, when the match was only in the title), on one line, with "…" where
 * text was left out. [matches] must be sorted and are returned shifted to fit.
 */
internal fun snippetAround(text: String, matches: List<IntRange>, maxLength: Int = 160): Snippet {
    // Same length as the input, so match positions stay valid.
    val flat = text.map { if (it.isWhitespace()) ' ' else it }.joinToString("")
    if (flat.length <= maxLength) return Snippet(flat, matches)

    var start = ((matches.firstOrNull()?.first ?: 0) - LEAD_IN).coerceAtLeast(0)
    // Don't begin in the middle of a word.
    if (start > 0) flat.indexOf(' ', start).takeIf { it != -1 && it < start + LEAD_IN }?.let { start = it + 1 }
    val end = (start + maxLength).coerceAtMost(flat.length)
    start = (end - maxLength).coerceAtLeast(0).coerceAtMost(start)

    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < flat.length) "…" else ""
    val shift = prefix.length - start
    val visible = matches
        .filter { it.last >= start && it.first < end }
        .map { (it.first.coerceAtLeast(start) + shift)..(it.last.coerceAtMost(end - 1) + shift) }
    return Snippet(prefix + flat.substring(start, end) + suffix, visible)
}

private const val TITLE_WEIGHT = 5
private const val WHOLE_WORD_BONUS = 2
private const val MAX_COUNTED_REPEATS = 3
private const val LEAD_IN = 40
