package com.adam.insightjournal

import android.app.Application
import android.content.Context
import com.adam.insightjournal.ai.AnthropicJournalAi
import com.adam.insightjournal.ai.EntryDraftHolder
import com.adam.insightjournal.ai.JournalAi
import com.adam.insightjournal.ai.QuickNoteSummaries
import com.adam.insightjournal.data.AuthRepository
import com.adam.insightjournal.data.FirebaseAuthRepository
import com.adam.insightjournal.data.FirestoreJournalRepository
import com.adam.insightjournal.data.FirestoreQuickNoteRepository
import com.adam.insightjournal.data.InMemoryJournalRepository
import com.adam.insightjournal.data.InMemoryQuickNoteRepository
import com.adam.insightjournal.data.JournalRepository
import com.adam.insightjournal.data.LocalAuthRepository
import com.adam.insightjournal.data.QuickNoteRepository
import com.adam.insightjournal.data.FirestoreWeeklySummaryRepository
import com.adam.insightjournal.data.InMemoryWeeklySummaryRepository
import com.adam.insightjournal.data.WeeklySummaryRepository
import com.adam.insightjournal.ui.theme.ThemeSettingsRepository
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    val journalRepository: JournalRepository =
        if (firebaseConfigured) FirestoreJournalRepository(authRepository) else InMemoryJournalRepository()

    val quickNoteRepository: QuickNoteRepository =
        if (firebaseConfigured) FirestoreQuickNoteRepository(authRepository) else InMemoryQuickNoteRepository()

    /** Null when ANTHROPIC_API_KEY isn't set in local.properties; AI features are then switched off. */
    val journalAi: JournalAi? =
        BuildConfig.ANTHROPIC_API_KEY.takeIf { it.isNotBlank() }?.let(::AnthropicJournalAi)

    val quickNoteSummaries = QuickNoteSummaries(quickNoteRepository, journalAi, applicationScope)

    /** Carries an AI draft from the Quick notes screen to the editor. */
    val entryDraftHolder = EntryDraftHolder()

    val weeklySummaryRepository: WeeklySummaryRepository =
        if (firebaseConfigured) FirestoreWeeklySummaryRepository(authRepository) else InMemoryWeeklySummaryRepository()

    /** The user's color theme, light/dark choice and font. */
    val themeSettings = ThemeSettingsRepository(context)
}
