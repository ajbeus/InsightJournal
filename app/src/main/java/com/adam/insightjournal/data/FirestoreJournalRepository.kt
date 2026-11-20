package com.adam.insightjournal.data

import android.util.Log
import com.adam.insightjournal.crypto.ContentCipher
import com.adam.insightjournal.crypto.KeyManager
import com.adam.insightjournal.data.model.Attachment
import com.adam.insightjournal.data.model.JournalEntry
import com.adam.insightjournal.data.model.Mood
import com.adam.insightjournal.data.model.monthKeyOf
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Entries live at users/{uid}/entries/{entryId}. Keeping everything under the
 * user's own document is what lets the security rules be a single line.
 *
 * Everything personal (title, body, mood, summary, embedding, attachment info)
 * is encrypted on this phone before it is written; see EncryptedFields.kt.
 * Dates, the month key, the source and the note links stay readable so
 * Firestore can still sort and the web app can still query by month.
 *
 * Firestore's offline cache is on by default on Android: reads are served from
 * disk when offline and writes queue until the connection returns. The cache
 * holds the encrypted form too.
 */
class FirestoreJournalRepository(
    private val auth: AuthRepository,
    private val keys: KeyManager,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : JournalRepository {

    private val _writeErrors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val writeErrors: SharedFlow<String> = _writeErrors.asSharedFlow()

    private fun entries(uid: String): CollectionReference =
        db.collection("users").document(uid).collection("entries")

    private fun requireUid(): String =
        auth.currentUser.value?.uid ?: error("No signed-in user")

    private fun requireCipher(): ContentCipher =
        keys.cipher.value ?: error("The journal is locked")

    /** Re-subscribes whenever the user or the lock state changes; empty while locked. */
    override fun observeEntries(): Flow<List<JournalEntry>> =
        auth.whileUnlocked(keys, whenLocked = emptyList(), ::entriesFlow)

    private fun entriesFlow(uid: String, cipher: ContentCipher): Flow<List<JournalEntry>> = callbackFlow {
        val registration = entries(uid)
            .orderBy("entryDate", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                } else if (snapshot != null) {
                    trySend(snapshot.documents.map { it.toJournalEntry(cipher) })
                }
            }
        awaitClose { registration.remove() }
    }

    override suspend fun getEntry(id: String): JournalEntry? {
        val snapshot = entries(requireUid()).document(id).get().await()
        return if (snapshot.exists()) snapshot.toJournalEntry(requireCipher()) else null
    }

    override fun saveEntry(entry: JournalEntry): String {
        val collection = entries(requireUid())
        val doc = if (entry.id.isEmpty()) collection.document() else collection.document(entry.id)
        // A full set, not a merge: it also removes any readable fields left from before encryption.
        doc.set(entry.toMap(requireCipher(), doc.id)).addOnFailureListener { reportWriteError("save", it) }
        return doc.id
    }

    override fun deleteEntry(id: String) {
        entries(requireUid()).document(id).delete()
            .addOnFailureListener { reportWriteError("delete", it) }
    }

    /** Rewrites entries saved before encryption existed. Returns how many it changed. */
    suspend fun encryptLegacyEntries(): Int {
        val uid = requireUid()
        val cipher = requireCipher()
        val legacy = entries(uid).get().await().documents.filter { !it.contains(ENC_FIELD) }
        legacy.forEach { doc ->
            val entry = doc.toJournalEntry(cipher)
            doc.reference.set(entry.toMap(cipher, doc.id)).await()
        }
        return legacy.size
    }

    private fun reportWriteError(action: String, e: Exception) {
        Log.e("JournalRepository", "Entry $action failed", e)
        _writeErrors.tryEmit("Couldn't $action entry: ${e.localizedMessage ?: "unknown error"}")
    }
}

// ---- Firestore <-> model mapping. Written by hand so the document shape is explicit. ----

private fun entryPath(id: String) = "entries/$id"

private fun JournalEntry.toMap(cipher: ContentCipher, id: String): Map<String, Any?> {
    val secret = JSONObject()
        .put("title", title)
        .put("body", body)
        .put("mood", mood ?: JSONObject.NULL)
        .put("summary", summary ?: JSONObject.NULL)
        .put("embedding", embedding?.packFloats() ?: JSONObject.NULL)
        .put("embeddingModel", embeddingModel ?: JSONObject.NULL)
        .put("attachments", JSONArray(attachments.map { it.toJson() }))
    return mapOf(
        "entryDate" to entryDate.toTimestamp(),
        "monthKey" to monthKey,
        "source" to source,
        "sourceNoteIds" to sourceNoteIds,
        "createdAt" to createdAt.toTimestamp(),
        "updatedAt" to updatedAt.toTimestamp(),
        ENC_FIELD to cipher.sealJson(secret, entryPath(id)),
    )
}

private fun Attachment.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("fileName", fileName)
    .put("mimeType", mimeType)
    .put("storagePath", storagePath)
    .put("sizeBytes", sizeBytes)

private fun DocumentSnapshot.toJournalEntry(cipher: ContentCipher): JournalEntry {
    val entryDate = getTimestamp("entryDate")?.toInstantCompat() ?: Instant.now()
    val readable = JournalEntry(
        id = id,
        entryDate = entryDate,
        monthKey = getString("monthKey") ?: monthKeyOf(entryDate),
        source = getString("source") ?: JournalEntry.SOURCE_TYPED,
        sourceNoteIds = (get("sourceNoteIds") as? List<*>).orEmpty().filterIsInstance<String>(),
        createdAt = getTimestamp("createdAt")?.toInstantCompat() ?: entryDate,
        updatedAt = getTimestamp("updatedAt")?.toInstantCompat() ?: entryDate,
    )
    val sealed = getString(ENC_FIELD) ?: return readable.withLegacyFields(this)
    val secret = cipher.openJson(sealed, entryPath(id))
        ?: return readable.copy(title = "Can't decrypt this entry", unreadable = true)
    return readable.copy(
        title = secret.optString("title"),
        body = secret.optString("body"),
        mood = (secret.opt("mood") as? Number)?.toInt()?.takeIf(Mood::isValid),
        summary = secret.optStringOrNull("summary"),
        embedding = secret.optStringOrNull("embedding")?.unpackFloats(),
        embeddingModel = secret.optStringOrNull("embeddingModel"),
        attachments = secret.optJSONArray("attachments")?.let { array ->
            List(array.length()) { array.getJSONObject(it).toAttachment() }
        }.orEmpty(),
    )
}

/** Entries written before encryption kept these fields in the open. */
private fun JournalEntry.withLegacyFields(doc: DocumentSnapshot) = copy(
    title = doc.getString("title").orEmpty(),
    body = doc.getString("body").orEmpty(),
    attachments = (doc.get("attachments") as? List<*>).orEmpty()
        .filterIsInstance<Map<*, *>>()
        .map { it.toAttachment() },
    // Firestore stores every whole number as a Long.
    mood = doc.getLong("mood")?.toInt()?.takeIf(Mood::isValid),
    summary = doc.getString("summary"),
    embedding = (doc.get("embedding") as? List<*>)?.filterIsInstance<Number>()?.map { it.toFloat() },
    embeddingModel = doc.getString("embeddingModel"),
)

private fun JSONObject.toAttachment() = Attachment(
    id = optString("id"),
    fileName = optString("fileName"),
    mimeType = optString("mimeType"),
    storagePath = optString("storagePath"),
    sizeBytes = optLong("sizeBytes"),
)

private fun Map<*, *>.toAttachment() = Attachment(
    id = this["id"] as? String ?: "",
    fileName = this["fileName"] as? String ?: "",
    mimeType = this["mimeType"] as? String ?: "",
    storagePath = this["storagePath"] as? String ?: "",
    sizeBytes = (this["sizeBytes"] as? Number)?.toLong() ?: 0,
)

private fun Instant.toTimestamp() = Timestamp(epochSecond, nano)
private fun Timestamp.toInstantCompat(): Instant = Instant.ofEpochSecond(seconds, nanoseconds.toLong())
