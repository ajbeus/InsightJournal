package com.adam.insightjournal

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.adam.insightjournal.ui.auth.AuthScreen
import com.adam.insightjournal.ui.auth.AuthViewModel
import com.adam.insightjournal.ui.editor.EntryEditorScreen
import com.adam.insightjournal.ui.editor.EntryEditorViewModel
import com.adam.insightjournal.ui.entries.EntryListScreen
import com.adam.insightjournal.ui.entries.EntryListViewModel
import com.adam.insightjournal.ui.insights.InsightsScreen
import com.adam.insightjournal.ui.insights.InsightsViewModel
import com.adam.insightjournal.ui.notes.QuickNotesScreen
import com.adam.insightjournal.ui.notes.QuickNotesViewModel
import com.adam.insightjournal.ui.search.SearchScreen
import com.adam.insightjournal.ui.search.SearchViewModel
import com.adam.insightjournal.ui.security.VaultGate
import com.adam.insightjournal.ui.settings.AppearanceScreen
import com.adam.insightjournal.ui.settings.SettingsScreen
import com.adam.insightjournal.ui.splash.SplashScreen
import com.adam.insightjournal.ui.theme.InsightJournalTheme
import com.adam.insightjournal.ui.theme.isDark
import kotlinx.coroutines.delay

/** How long the loading artwork stays up. The journal loads underneath it meanwhile. */
private const val SPLASH_MILLIS = 1_600L

/** With fingerprint unlock on, the journal locks again after this long in the background. */
private const val RELOCK_AFTER_MILLIS = 5 * 60_000L

/** A FragmentActivity (still a ComponentActivity) because the biometric prompt needs one. */
class MainActivity : FragmentActivity() {
    private val container by lazy { (application as InsightJournalApp).container }
    private var stoppedAt = 0L

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > RELOCK_AFTER_MILLIS) {
            container.keyManager?.lockIfBiometric()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by container.themeSettings.settings.collectAsStateWithLifecycle()
            // Saveable, so rotating the phone or coming back to the app doesn't replay it.
            var showSplash by rememberSaveable { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                if (showSplash) {
                    delay(SPLASH_MILLIS)
                    showSplash = false
                }
            }

            // Status and navigation bar icons must contrast with what is behind them:
            // the splash is always dark, after that it depends on the chosen theme.
            val darkBackground = showSplash || settings.isDark()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkBackground
                    isAppearanceLightNavigationBars = !darkBackground
                }
            }

            InsightJournalTheme(settings) {
                Box(Modifier.fillMaxSize()) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        InsightJournalRoot(container)
                    }
                    AnimatedVisibility(
                        visible = showSplash,
                        enter = EnterTransition.None,
                        exit = fadeOut(tween(durationMillis = 450)),
                    ) {
                        SplashScreen()
                    }
                }
            }
        }
    }
}

/** Signed out -> sign-in screen. Signed in -> unlock (or set up) the journal key -> the journal. */
@Composable
fun InsightJournalRoot(container: AppContainer) {
    val user by container.authRepository.currentUser.collectAsStateWithLifecycle()
    val keys = container.keyManager
    if (user == null) {
        AuthScreen(viewModel(factory = viewModelFactory {
            initializer { AuthViewModel(container.authRepository) }
        }))
    } else if (keys == null) {
        // Demo mode: nothing is stored remotely, so there is nothing to encrypt.
        JournalNavHost(container)
    } else {
        val vault by keys.state.collectAsStateWithLifecycle()
        VaultGate(keys, vault, onSignOut = container.authRepository::signOut) {
            JournalNavHost(container)
        }
    }
}

private const val ROUTE_ENTRIES = "entries"
private const val ROUTE_NOTES = "notes"
private const val ROUTE_INSIGHTS = "insights"
private const val ROUTE_SEARCH = "search"
private const val ROUTE_APPEARANCE = "appearance"
private const val ROUTE_SETTINGS = "settings"
private const val ARG_ENTRY_ID = "entryId"
private const val ROUTE_EDITOR = "editor?$ARG_ENTRY_ID={$ARG_ENTRY_ID}"

@Composable
private fun JournalNavHost(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(navController, startDestination = ROUTE_ENTRIES) {
        composable(ROUTE_ENTRIES) {
            EntryListScreen(
                viewModel = viewModel(factory = viewModelFactory {
                    initializer { EntryListViewModel(container.journalRepository) }
                }),
                demoMode = !container.firebaseConfigured,
                bottomBar = { MainTabs(navController, selected = ROUTE_ENTRIES) },
                onNewEntry = { navController.navigate("editor") },
                onOpenEntry = { id -> navController.navigate("editor?$ARG_ENTRY_ID=$id") },
                onSearch = { navController.navigate(ROUTE_SEARCH) },
                onSettings = { navController.navigate(ROUTE_SETTINGS) },
                onSignOut = container.authRepository::signOut,
            )
        }
        composable(ROUTE_NOTES) {
            QuickNotesScreen(
                viewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        QuickNotesViewModel(
                            container.quickNoteRepository,
                            container.quickNoteSummaries,
                            container.journalAi,
                            container.entryDraftHolder,
                        )
                    }
                }),
                aiSettings = container.aiSettings,
                onOpenDraft = { navController.navigate("editor") },
                bottomBar = { MainTabs(navController, selected = ROUTE_NOTES) },
            )
        }
        composable(ROUTE_INSIGHTS) {
            InsightsScreen(
                viewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        InsightsViewModel(
                            container.journalRepository,
                            container.quickNoteRepository,
                            container.weeklySummaryRepository,
                            container.journalAi,
                        )
                    }
                }),
                aiSettings = container.aiSettings,
                bottomBar = { MainTabs(navController, selected = ROUTE_INSIGHTS) },
            )
        }
        composable(ROUTE_SEARCH) {
            SearchScreen(
                viewModel = viewModel(factory = viewModelFactory {
                    initializer { SearchViewModel(container.journalRepository, container.quickNoteRepository) }
                }),
                onBack = { navController.popBackStack() },
                onOpenEntry = { id -> navController.navigate("editor?$ARG_ENTRY_ID=$id") },
                onOpenQuickNotes = {
                    // Leave search first, so the Journal tab doesn't reopen on it later.
                    navController.popBackStack()
                    navController.switchToTab(ROUTE_NOTES)
                },
            )
        }
        composable(ROUTE_SETTINGS) {
            val user by container.authRepository.currentUser.collectAsStateWithLifecycle()
            SettingsScreen(
                user = user,
                keys = container.keyManager,
                aiSettings = container.aiSettings,
                aiConfigured = container.journalAi != null,
                onAppearance = { navController.navigate(ROUTE_APPEARANCE) },
                onSignOut = container.authRepository::signOut,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ROUTE_APPEARANCE) {
            AppearanceScreen(container.themeSettings, onBack = { navController.popBackStack() })
        }
        composable(
            route = ROUTE_EDITOR,
            arguments = listOf(navArgument(ARG_ENTRY_ID) { type = NavType.StringType; nullable = true }),
        ) { backStackEntry ->
            val entryId = backStackEntry.arguments?.getString(ARG_ENTRY_ID)
            EntryEditorScreen(
                viewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        EntryEditorViewModel(
                            repository = container.journalRepository,
                            quickNotes = container.quickNoteRepository,
                            entryId = entryId,
                            // A new entry picks up an AI draft if one is waiting.
                            draft = if (entryId == null) container.entryDraftHolder.take() else null,
                        )
                    }
                }),
                // Back to whichever screen opened the editor.
                onDone = { navController.popBackStack() },
            )
        }
    }
}

private fun NavHostController.switchToTab(route: String) {
    navigate(route) {
        // Keep one copy of each tab and restore its scroll position when coming back.
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Bottom tabs shown on the top-level screens. */
@Composable
private fun MainTabs(navController: NavHostController, selected: String) {
    fun switchTo(route: String) {
        if (route != selected) navController.switchToTab(route)
    }
    NavigationBar {
        NavigationBarItem(
            selected = selected == ROUTE_ENTRIES,
            onClick = { switchTo(ROUTE_ENTRIES) },
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            label = { Text("Journal") },
        )
        NavigationBarItem(
            selected = selected == ROUTE_NOTES,
            onClick = { switchTo(ROUTE_NOTES) },
            icon = { Icon(Icons.Default.Create, contentDescription = null) },
            label = { Text("Quick notes") },
        )
        NavigationBarItem(
            selected = selected == ROUTE_INSIGHTS,
            onClick = { switchTo(ROUTE_INSIGHTS) },
            icon = { Icon(Icons.Default.Star, contentDescription = null) },
            label = { Text("Insights") },
        )
    }
}
