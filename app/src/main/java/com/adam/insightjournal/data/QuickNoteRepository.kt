package com.adam.insightjournal.data

import android.util.Log
import com.adam.insightjournal.crypto.ContentCipher
import com.adam.insightjournal.crypto.KeyManager
import com.adam.insightjournal.data.model.QuickNote
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

interface QuickNoteRepository {
    /** The current user's quick notes, newest first. */
    fun observeNotes(): Flow<List<QuickNote>>

    /** Saves a new note and returns it (with its id). Like entries, writes don't wait for the server. */
    fun addNote(text: String): QuickNote

    /** Stores the AI summary on [note]. The whole note is passed because its text is re-encrypted with the summary. */
    fun setSummary(note: QuickNote, summary: String)

    /**
     * Records which journal entry the notes were turned into, or clears the
     * link (entryId = null) when that entry is deleted.
     */
    fun setEntryLink(noteIds: List<String>, entryId: String?)

    fun deleteNote(id: String)

    val writeErrors: SharedFlow<String>
}

/**
 * Quick notes live at users/{uid}/quickNotes/{noteId}. The text, summary and
 * embedding are encrypted on this phone (see EncryptedFields.kt); the creation
 * time and the link to a journal entry stay readable for sorting.
 */
class FirestoreQuickNoteRepository(
    private val auth: AuthRepository,
    private val keys: KeyManager,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : QuickNoteRepository {

    private val _writeErrors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val writeErrors: SharedFlow<String> = _writeErrors.asSharedFlow()

    private fun notes(uid: String): CollectionReference =
        db.collection("users").document(uid).collection("quickNotes")

    private fun requireUid(): String =
        auth.currentUser.value?.uid ?: error("No signed-in user")

    private fun requireCipher(): ContentCipher =
        keys.cipher.value ?: error("The journal is locked")

    override fun observeNotes(): Flow<List<QuickNote>> =
        auth.whileUnlocked(keys, whenLocked = emptyList(), ::notesFlow)

    private fun notesFlow(uid: String, cipher: ContentCipher): Flow<List<QuickNote>> = callbackFlow {
        val registration = notes(uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                } else if (snapshot != null) {
                    trySend(snapshot.documents.map { it.toQuickNote(cipher) })
                }
            }
        awaitClose { registration.remove() }
    }

    override fun addNote(text: String): QuickNote {
        val doc = notes(requireUid()).document()
        val note = QuickNote(id = doc.id, text = text, createdAt = Instant.now())
        doc.set(note.toMap(requireCipher())).addOnFailureListener { report("save note", it) }
        return note
    }

    override fun setSummary(note: QuickNote, summary: String) {
        // Only the sealed field changes; the readable entry link is left alone.
        val sealed = note.copy(summary = summary).sealedPayload(requireCipher())
        notes(requireUid()).document(note.id).update(ENC_FIELD, sealed)
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

    /** Rewrites notes saved before encryption existed. Returns how many it changed. */
    suspend fun encryptLegacyNotes(): Int {
        val cipher = requireCipher()
        val legacy = notes(requireUid()).get().await().documents.filter { !it.contains(ENC_FIELD) }
        legacy.forEach { doc -> doc.reference.set(doc.toQuickNote(cipher).toMap(cipher)).await() }
        return legacy.size
    }

    private fun report(action: String, e: Exception) {
        Log.e("QuickNoteRepository", "Couldn't $action", e)
        _writeErrors.tryEmit("Couldn't $action: ${e.localizedMessage ?: "unknown error"}")
    }

    private fun notePath(id: String) = "quickNotes/$id"

    private fun QuickNote.sealedPayload(cipher: ContentCipher): String = cipher.sealJson(
        JSONObject()
            .put("text", text)
            .put("summary", summary ?: JSONObject.NULL)
            .put("embedding", embedding?.packFloats() ?: JSONObject.NULL)
            .put("embeddingModel", embeddingModel ?: JSONObject.NULL),
        notePath(id),
    )

    private fun QuickNote.toMap(cipher: ContentCipher): Map<String, Any?> = mapOf(
        "createdAt" to Timestamp(createdAt.epochSecond, createdAt.nano),
        "entryId" to entryId,
        ENC_FIELD to sealedPayload(cipher),
    )

    private fun DocumentSnapshot.toQuickNote(cipher: ContentCipher): QuickNote {
        val readable = QuickNote(
            id = id,
            createdAt = getTimestamp("createdAt")
                ?.let { Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong()) }
                ?: Instant.now(),
            entryId = getString("entryId"),
        )
        val sealed = getString(ENC_FIELD) ?: return readable.copy(
            // Written before encryption existed.
            text = getString("text").orEmpty(),
            summary = getString("summary"),
            embedding = (get("embedding") as? List<*>)?.filterIsInstance<Number>()?.map { it.toFloat() },
            embeddingModel = getString("embeddingModel"),
        )
        val secret = cipher.openJson(sealed, notePath(id))
            ?: return readable.copy(text = "Can't decrypt this note.", unreadable = true)
        return readable.copy(
            text = secret.optString("text"),
            summary = secret.optStringOrNull("summary"),
            embedding = secret.optStringOrNull("embedding")?.unpackFloats(),
            embeddingModel = secret.optStringOrNull("embeddingModel"),
        )
    }
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

    override fun setSummary(note: QuickNote, summary: String) {
        notes.update { list -> list.map { if (it.id == note.id) it.copy(summary = summary) else it } }
    }

    override fun setEntryLink(noteIds: List<String>, entryId: String?) {
        notes.update { list -> list.map { if (it.id in noteIds) it.copy(entryId = entryId) else it } }
    }

    override fun deleteNote(id: String) {
        notes.update { list -> list.filterNot { it.id == id } }
    }
}
