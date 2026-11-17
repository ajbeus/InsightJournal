package com.adam.insightjournal.data

import android.util.Log
import com.adam.insightjournal.data.model.QuickNote
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

interface QuickNoteRepository {
    /** The current user's quick notes, newest first. */
    fun observeNotes(): Flow<List<QuickNote>>

    /** Saves a new note and returns it (with its id). Like entries, writes don't wait for the server. */
    fun addNote(text: String): QuickNote

    /** Stores the AI summary on an existing note. */
    fun setSummary(id: String, summary: String)

    /**
     * Records which journal entry the notes were turned into, or clears the
     * link (entryId = null) when that entry is deleted.
     */
    fun setEntryLink(noteIds: List<String>, entryId: String?)

    fun deleteNote(id: String)

    val writeErrors: SharedFlow<String>
}

/** Quick notes live at users/{uid}/quickNotes/{noteId}. */
class FirestoreQuickNoteRepository(
    private val auth: AuthRepository,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : QuickNoteRepository {

    private val _writeErrors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val writeErrors: SharedFlow<String> = _writeErrors.asSharedFlow()

    private fun notes(uid: String): CollectionReference =
        db.collection("users").document(uid).collection("quickNotes")

    private fun requireUid(): String =
        auth.currentUser.value?.uid ?: error("No signed-in user")

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeNotes(): Flow<List<QuickNote>> =
        auth.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else notesFlow(user.uid)
        }

    private fun notesFlow(uid: String): Flow<List<QuickNote>> = callbackFlow {
        val registration = notes(uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                } else if (snapshot != null) {
                    trySend(snapshot.documents.map { it.toQuickNote() })
                }
            }
        awaitClose { registration.remove() }
    }

    override fun addNote(text: String): QuickNote {
        val doc = notes(requireUid()).document()
        val note = QuickNote(id = doc.id, text = text, createdAt = Instant.now())
        doc.set(
            mapOf(
                "text" to note.text,
                "createdAt" to Timestamp(note.createdAt.epochSecond, note.createdAt.nano),
                "entryId" to null,
                "summary" to null,
                "embedding" to null,
                "embeddingModel" to null,
            )
        ).addOnFailureListener { report("save note", it) }
        return note
    }

    override fun setSummary(id: String, summary: String) {
        notes(requireUid()).document(id).update("summary", summary)
            .addOnFailureListener { report("save summary", it) }
    }

    override fun setEntryLink(noteIds: List<String>, entryId: String?) {
        val collection = notes(requireUid())
        // One update per note rather than a batch: a batch fails as a whole if
        // any note in it has been deleted since.
        noteIds.forEach { id ->
            collection.document(id).update("entryId", entryId)
                .addOnFailureListener { Log.w("QuickNoteRepository", "Couldn't link note $id", it) }
        }
    }

    override fun deleteNote(id: String) {
        notes(requireUid()).document(id).delete()
            .addOnFailureListener { report("delete note", it) }
    }

    private fun report(action: String, e: Exception) {
        Log.e("QuickNoteRepository", "Couldn't $action", e)
        _writeErrors.tryEmit("Couldn't $action: ${e.localizedMessage ?: "unknown error"}")
    }

    private fun DocumentSnapshot.toQuickNote() = QuickNote(
        id = id,
        text = getString("text").orEmpty(),
        createdAt = getTimestamp("createdAt")
            ?.let { Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong()) }
            ?: Instant.now(),
        entryId = getString("entryId"),
        summary = getString("summary"),
        embedding = (get("embedding") as? List<*>)?.filterIsInstance<Number>()?.map { it.toFloat() },
        embeddingModel = getString("embeddingModel"),
    )
}

/** Demo-mode storage used when Firebase isn't configured. Lost when the app closes. */
class InMemoryQuickNoteRepository : QuickNoteRepository {

    private val notes = MutableStateFlow<List<QuickNote>>(emptyList())

    override val writeErrors: SharedFlow<String> = MutableSharedFlow<String>().asSharedFlow()

    override fun observeNotes(): Flow<List<QuickNote>> = notes

    override fun addNote(text: String): QuickNote {
        val note = QuickNote(id = UUID.randomUUID().toString(), text = text)
        notes.update { listOf(note) + it }
        return note
    }

    override fun setSummary(id: String, summary: String) {
        notes.update { list -> list.map { if (it.id == id) it.copy(summary = summary) else it } }
    }

    override fun setEntryLink(noteIds: List<String>, entryId: String?) {
        notes.update { list -> list.map { if (it.id in noteIds) it.copy(entryId = entryId) else it } }
    }

    override fun deleteNote(id: String) {
        notes.update { list -> list.filterNot { it.id == id } }
    }
}
