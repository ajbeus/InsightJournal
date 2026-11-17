package com.adam.insightjournal.data

import android.util.Log
import com.adam.insightjournal.data.model.Attachment
import com.adam.insightjournal.data.model.JournalEntry
import com.adam.insightjournal.data.model.Mood
import com.adam.insightjournal.data.model.monthKeyOf
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import java.time.Instant

/**
 * Entries live at users/{uid}/entries/{entryId}. Keeping everything under the
 * user's own document is what lets the security rules be a single line.
 *
 * Firestore's offline cache is on by default on Android: reads are served from
 * disk when offline and writes queue until the connection returns.
 */
class FirestoreJournalRepository(
    private val auth: AuthRepository,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : JournalRepository {

    private val _writeErrors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val writeErrors: SharedFlow<String> = _writeErrors.asSharedFlow()

    private fun entries(uid: String): CollectionReference =
        db.collection("users").document(uid).collection("entries")

    private fun requireUid(): String =
        auth.currentUser.value?.uid ?: error("No signed-in user")

    /** Re-subscribes whenever the signed-in user changes; empty when signed out. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeEntries(): Flow<List<JournalEntry>> =
        auth.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else entriesFlow(user.uid)
        }

    private fun entriesFlow(uid: String): Flow<List<JournalEntry>> = callbackFlow {
        val registration = entries(uid)
            .orderBy("entryDate", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                } else if (snapshot != null) {
                    trySend(snapshot.documents.map { it.toJournalEntry() })
                }
            }
        awaitClose { registration.remove() }
    }

    override suspend fun getEntry(id: String): JournalEntry? {
        val snapshot = entries(requireUid()).document(id).get().await()
        return if (snapshot.exists()) snapshot.toJournalEntry() else null
    }

    override fun saveEntry(entry: JournalEntry): String {
        val collection = entries(requireUid())
        val doc = if (entry.id.isEmpty()) collection.document() else collection.document(entry.id)
        doc.set(entry.toMap()).addOnFailureListener { reportWriteError("save", it) }
        return doc.id
    }

    override fun deleteEntry(id: String) {
        entries(requireUid()).document(id).delete()
            .addOnFailureListener { reportWriteError("delete", it) }
    }

    private fun reportWriteError(action: String, e: Exception) {
        Log.e("JournalRepository", "Entry $action failed", e)
        _writeErrors.tryEmit("Couldn't $action entry: ${e.localizedMessage ?: "unknown error"}")
    }
}

// ---- Firestore <-> model mapping. Written by hand so the document shape is explicit. ----

private fun JournalEntry.toMap(): Map<String, Any?> = mapOf(
    "title" to title,
    "body" to body,
    "entryDate" to entryDate.toTimestamp(),
    "monthKey" to monthKey,
    "source" to source,
    "sourceNoteIds" to sourceNoteIds,
    "attachments" to attachments.map { it.toMap() },
    "mood" to mood,
    "createdAt" to createdAt.toTimestamp(),
    "updatedAt" to updatedAt.toTimestamp(),
    "summary" to summary,
    "embedding" to embedding?.map { it.toDouble() },
    "embeddingModel" to embeddingModel,
)

private fun Attachment.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "fileName" to fileName,
    "mimeType" to mimeType,
    "storagePath" to storagePath,
    "sizeBytes" to sizeBytes,
)

private fun DocumentSnapshot.toJournalEntry(): JournalEntry {
    val entryDate = getTimestamp("entryDate")?.toInstantCompat() ?: Instant.now()
    return JournalEntry(
        id = id,
        title = getString("title").orEmpty(),
        body = getString("body").orEmpty(),
        entryDate = entryDate,
        monthKey = getString("monthKey") ?: monthKeyOf(entryDate),
        source = getString("source") ?: JournalEntry.SOURCE_TYPED,
        sourceNoteIds = (get("sourceNoteIds") as? List<*>).orEmpty().filterIsInstance<String>(),
        attachments = (get("attachments") as? List<*>).orEmpty()
            .filterIsInstance<Map<*, *>>()
            .map { it.toAttachment() },
        // Firestore stores every whole number as a Long.
        mood = getLong("mood")?.toInt()?.takeIf(Mood::isValid),
        createdAt = getTimestamp("createdAt")?.toInstantCompat() ?: entryDate,
        updatedAt = getTimestamp("updatedAt")?.toInstantCompat() ?: entryDate,
        summary = getString("summary"),
        embedding = (get("embedding") as? List<*>)?.filterIsInstance<Number>()?.map { it.toFloat() },
        embeddingModel = getString("embeddingModel"),
    )
}

private fun Map<*, *>.toAttachment() = Attachment(
    id = this["id"] as? String ?: "",
    fileName = this["fileName"] as? String ?: "",
    mimeType = this["mimeType"] as? String ?: "",
    storagePath = this["storagePath"] as? String ?: "",
    sizeBytes = (this["sizeBytes"] as? Number)?.toLong() ?: 0,
)

private fun Instant.toTimestamp() = Timestamp(epochSecond, nano)
private fun Timestamp.toInstantCompat(): Instant = Instant.ofEpochSecond(seconds, nanoseconds.toLong())
