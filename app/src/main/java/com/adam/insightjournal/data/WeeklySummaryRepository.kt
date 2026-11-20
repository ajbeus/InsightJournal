package com.adam.insightjournal.data

import android.util.Log
import com.adam.insightjournal.crypto.ContentCipher
import com.adam.insightjournal.crypto.KeyManager
import com.adam.insightjournal.insights.WeeklySummary
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/**
 * Keeps the latest AI weekly summary so it is generated once and then read
 * back, not regenerated every time the Insights screen opens.
 */
interface WeeklySummaryRepository {
    /** The stored summary, or null if none has been generated yet. */
    fun observe(): Flow<WeeklySummary?>

    /** Replaces the stored summary. Like other writes, it doesn't wait for the server. */
    fun save(summary: WeeklySummary)
}

/**
 * One document per user at users/{uid}/insights/weeklySummary. Only the latest
 * summary is kept; a new one overwrites it. Everything but the generation time
 * is encrypted, the fingerprint included (it is derived from the week's text).
 */
class FirestoreWeeklySummaryRepository(
    private val auth: AuthRepository,
    private val keys: KeyManager,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : WeeklySummaryRepository {

    private fun document(uid: String): DocumentReference =
        db.collection("users").document(uid).collection("insights").document("weeklySummary")

    override fun observe(): Flow<WeeklySummary?> =
        auth.whileUnlocked(keys, whenLocked = null, ::summaryFlow)

    private fun summaryFlow(uid: String, cipher: ContentCipher): Flow<WeeklySummary?> = callbackFlow {
        val registration = document(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                // A missing summary is not worth failing the whole Insights screen for.
                Log.w(TAG, "Couldn't read the weekly summary", error)
                trySend(null)
            } else if (snapshot != null) {
                trySend(snapshot.toWeeklySummary(cipher))
            }
        }
        awaitClose { registration.remove() }
    }

    override fun save(summary: WeeklySummary) {
        val uid = auth.currentUser.value?.uid ?: return
        val cipher = keys.cipher.value ?: return
        document(uid).set(summary.toMap(cipher))
            .addOnFailureListener { Log.w(TAG, "Couldn't save the weekly summary", it) }
    }

    /** Rewrites a summary saved before encryption existed. */
    suspend fun encryptLegacySummary() {
        val uid = auth.currentUser.value?.uid ?: return
        val cipher = keys.cipher.value ?: return
        val snapshot = document(uid).get().await()
        if (!snapshot.exists() || snapshot.contains(ENC_FIELD)) return
        val summary = snapshot.toWeeklySummary(cipher)
        if (summary == null) snapshot.reference.delete().await()
        else snapshot.reference.set(summary.toMap(cipher)).await()
    }

    private fun WeeklySummary.toMap(cipher: ContentCipher): Map<String, Any?> = mapOf(
        "generatedAt" to Timestamp(generatedAt.epochSecond, generatedAt.nano),
        ENC_FIELD to cipher.sealJson(
            JSONObject()
                .put("text", text)
                .put("fingerprint", fingerprint)
                // ISO dates ("2026-11-16"): calendar days, not moments in time.
                .put("periodStart", periodStart.toString())
                .put("periodEnd", periodEnd.toString()),
            PATH,
        ),
    )

    private fun DocumentSnapshot.toWeeklySummary(cipher: ContentCipher): WeeklySummary? {
        if (!exists()) return null
        val generatedAt = getTimestamp("generatedAt")
            ?.let { Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong()) }
            ?: Instant.now()
        return try {
            val sealed = getString(ENC_FIELD)
            if (sealed == null) {
                // Written before encryption existed.
                WeeklySummary(
                    text = getString("text") ?: return null,
                    fingerprint = getString("fingerprint") ?: return null,
                    periodStart = LocalDate.parse(getString("periodStart")),
                    periodEnd = LocalDate.parse(getString("periodEnd")),
                    generatedAt = generatedAt,
                )
            } else {
                val secret = cipher.openJson(sealed, PATH) ?: return null
                WeeklySummary(
                    text = secret.getString("text"),
                    fingerprint = secret.getString("fingerprint"),
                    periodStart = LocalDate.parse(secret.getString("periodStart")),
                    periodEnd = LocalDate.parse(secret.getString("periodEnd")),
                    generatedAt = generatedAt,
                )
            }
        } catch (e: Exception) {
            // A document in an unexpected shape is treated as "no summary yet".
            Log.w(TAG, "Ignoring a malformed weekly summary", e)
            null
        }
    }

    private companion object {
        const val TAG = "WeeklySummaryRepository"
        const val PATH = "insights/weeklySummary"
    }
}

/** Demo-mode storage used when Firebase isn't configured. Lost when the app closes. */
class InMemoryWeeklySummaryRepository : WeeklySummaryRepository {
    private val summary = MutableStateFlow<WeeklySummary?>(null)
    override fun observe(): Flow<WeeklySummary?> = summary
    override fun save(summary: WeeklySummary) {
        this.summary.value = summary
    }
}
