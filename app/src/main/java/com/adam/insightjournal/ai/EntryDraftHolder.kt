package com.adam.insightjournal.ai

import java.time.LocalDate

/** An AI draft waiting to be reviewed in the editor. Nothing is saved until the user saves it. */
data class PendingEntryDraft(
    val date: LocalDate,
    val title: String,
    val body: String,
    /** The quick notes the draft was written from; they get linked to the entry on save. */
    val noteIds: List<String>,
)

/**
 * Hands a draft from the Quick notes screen to the editor screen. A draft can
 * be long, so it is passed here rather than through a navigation argument.
 */
class EntryDraftHolder {
    private var pending: PendingEntryDraft? = null

    fun put(draft: PendingEntryDraft) {
        pending = draft
    }

    /** Returns the waiting draft, if any, and clears it so it is used only once. */
    fun take(): PendingEntryDraft? = pending.also { pending = null }
}
