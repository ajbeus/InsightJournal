package com.adam.insightjournal.data

import android.util.Log
import com.adam.insightjournal.insights.WeeklySummary
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
 * summary is kept; a new one overwrites it.
 */
class FirestoreWeeklySummaryRepository(
    private val auth: AuthRepository,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : WeeklySummaryRepository {

    private fun document(uid: String): DocumentReference =
        db.collection("users").document(uid).collection("insights").document("weeklySummary")

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(): Flow<WeeklySummary?> =
        auth.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(null) else summaryFlow(user.uid)
        }

    private fun summaryFlow(uid: String): Flow<WeeklySummary?> = callbackFlow {
        val registration = document(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                // A missing summary is not worth failing the whole Insights screen for.
                Log.w(TAG, "Couldn't read the weekly summary", error)
                trySend(null)
            } else if (snapshot != null) {
                trySend(snapshot.toWeeklySummary())
            }
        }
        awaitClose { registration.remove() }
    }

    override fun save(summary: WeeklySummary) {
        val uid = auth.currentUser.value?.uid ?: return
        document(uid).set(
            mapOf(
                "text" to summary.text,
                "fingerprint" to summary.fingerprint,
                // ISO dates ("2026-11-16"): calendar days, not moments in time.
                "periodStart" to summary.periodStart.toString(),
                "periodEnd" to summary.periodEnd.toString(),
                "generatedAt" to Timestamp(summary.generatedAt.epochSecond, summary.generatedAt.nano),
            )
        ).addOnFailureListener { Log.w(TAG, "Couldn't save the weekly summary", it) }
    }

    private fun DocumentSnapshot.toWeeklySummary(): WeeklySummary? {
        if (!exists()) return null
        return try {
            WeeklySummary(
                text = getString("text") ?: return null,
                fingerprint = getString("fingerprint") ?: return null,
                periodStart = LocalDate.parse(getString("periodStart")),
                periodEnd = LocalDate.parse(getString("periodEnd")),
                generatedAt = getTimestamp("generatedAt")
                    ?.let { Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong()) }
                    ?: Instant.now(),
            )
        } catch (e: RuntimeException) {
            // A document in an unexpected shape is treated as "no summary yet".
            Log.w(TAG, "Ignoring a malformed weekly summary", e)
            null
        }
    }

    private companion object {
        const val TAG = "WeeklySummaryRepository"
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
