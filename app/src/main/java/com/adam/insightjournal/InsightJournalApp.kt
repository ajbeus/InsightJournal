package com.adam.insightjournal

import android.app.Application
import android.content.Context
import android.util.Log
import com.adam.insightjournal.ai.AiSettings
import com.adam.insightjournal.ai.AnthropicJournalAi
import com.adam.insightjournal.ai.EntryDraftHolder
import com.adam.insightjournal.ai.JournalAi
import com.adam.insightjournal.ai.QuickNoteSummaries
import com.adam.insightjournal.crypto.DeviceKeyStore
import com.adam.insightjournal.crypto.KeyManager
import com.adam.insightjournal.data.AuthRepository
import com.adam.insightjournal.data.FirebaseAuthRepository
import com.adam.insightjournal.data.FirestoreJournalRepository
import com.adam.insightjournal.data.FirestoreQuickNoteRepository
import com.adam.insightjournal.data.FirestoreWeeklySummaryRepository
import com.adam.insightjournal.data.InMemoryJournalRepository
import com.adam.insightjournal.data.InMemoryQuickNoteRepository
import com.adam.insightjournal.data.InMemoryWeeklySummaryRepository
import com.adam.insightjournal.data.JournalRepository
import com.adam.insightjournal.data.LocalAuthRepository
import com.adam.insightjournal.data.QuickNoteRepository
import com.adam.insightjournal.data.WeeklySummaryRepository
import com.adam.insightjournal.ui.theme.ThemeSettingsRepository
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class InsightJournalApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Manual dependency injection: the one place that decides which implementations
 * the app uses. ViewModels get their repositories from here.
 */
class AppContainer(context: Context) {
    /** False when app/google-services.json was missing at build time. */
    val firebaseConfigured: Boolean = FirebaseApp.getApps(context).isNotEmpty()

    /** Outlives any screen; for work that must finish after the user navigates away. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val authRepository: AuthRepository =
        if (firebaseConfigured) FirebaseAuthRepository() else LocalAuthRepository()

    /**
     * Holds the journal's encryption key. Null in demo mode, where nothing leaves
     * the phone and there is nothing to encrypt.
     */
    val keyManager: KeyManager? =
        if (firebaseConfigured) KeyManager(authRepository, DeviceKeyStore(context), applicationScope) else null

    private val firestoreJournal = keyManager?.let { FirestoreJournalRepository(authRepository, it) }
    private val firestoreNotes = keyManager?.let { FirestoreQuickNoteRepository(authRepository, it) }
    private val firestoreWeekly = keyManager?.let { FirestoreWeeklySummaryRepository(authRepository, it) }

    val journalRepository: JournalRepository = firestoreJournal ?: InMemoryJournalRepository()

    val quickNoteRepository: QuickNoteRepository = firestoreNotes ?: InMemoryQuickNoteRepository()

    val weeklySummaryRepository: WeeklySummaryRepository = firestoreWeekly ?: InMemoryWeeklySummaryRepository()

    /** Null when ANTHROPIC_API_KEY isn't set in local.properties; AI features then can't be offered at all. */
    val journalAi: JournalAi? =
        BuildConfig.ANTHROPIC_API_KEY.takeIf { it.isNotBlank() }?.let(::AnthropicJournalAi)

    /** The user's AI on/off switch and consent. */
    val aiSettings = AiSettings(context)

    val quickNoteSummaries = QuickNoteSummaries(quickNoteRepository, journalAi, aiSettings, applicationScope)

    /** Carries an AI draft from the Quick notes screen to the editor. */
    val entryDraftHolder = EntryDraftHolder()

    /** The user's color theme, light/dark choice and font. */
    val themeSettings = ThemeSettingsRepository(context)

    init {
        // Every time the journal unlocks, encrypt anything still stored in the open
        // from before encryption existed. Usually there is nothing to do.
        keyManager?.let { keys ->
            applicationScope.launch {
                keys.cipher.filterNotNull().collect {
                    try {
                        val entries = firestoreJournal?.encryptLegacyEntries() ?: 0
                        val notes = firestoreNotes?.encryptLegacyNotes() ?: 0
                        firestoreWeekly?.encryptLegacySummary()
                        if (entries + notes > 0) Log.i(TAG, "Encrypted $entries old entries and $notes old notes")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Tried again at the next unlock; old documents stay readable meanwhile.
                        Log.w(TAG, "Couldn't encrypt older documents yet", e)
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "AppContainer"
    }
}
