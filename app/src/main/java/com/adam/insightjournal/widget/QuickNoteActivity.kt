package com.adam.insightjournal.widget

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adam.insightjournal.AppContainer
import com.adam.insightjournal.InsightJournalApp
import com.adam.insightjournal.MainActivity
import com.adam.insightjournal.ui.theme.InsightJournalTheme

/**
 * A small pop-up over the home screen for writing one quick note. Opened by
 * [QuickNoteWidget]. It saves through the same repository as the Quick notes
 * tab, so the note shows up there (and gets its AI summary) like any other.
 */
class QuickNoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as InsightJournalApp).container
        setContent {
            val settings by container.themeSettings.settings.collectAsStateWithLifecycle()
            val user by container.authRepository.currentUser.collectAsStateWithLifecycle()
            InsightJournalTheme(settings) {
                CaptureSheet(onDismiss = ::finish) {
                    if (user == null) {
                        SignedOutMessage(
                            onOpenApp = {
                                startActivity(Intent(this, MainActivity::class.java))
                                finish()
                            },
                            onCancel = ::finish,
                        )
                    } else {
                        NoteForm(
                            onSave = { text ->
                                saveNote(container, text)
                                Toast.makeText(this, "Saved to Quick notes", Toast.LENGTH_SHORT).show()
                                finish()
                            },
                            onCancel = ::finish,
                        )
                    }
                }
            }
        }
    }
}

/** Saves first, then summarizes: the same order as the Quick notes screen. */
private fun saveNote(container: AppContainer, text: String) {
    val note = container.quickNoteRepository.addNote(text.trim())
    // Runs on the app-wide scope, so it carries on after this pop-up closes.
    container.quickNoteSummaries.summarize(note)
}

/** Dims the home screen and holds the card at the bottom, above the keyboard. Tapping outside closes it. */
@Composable
private fun CaptureSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            // pointerInput rather than clickable: clickable would also make the scrim
            // focusable, and it must never take keyboard focus from the text field.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .systemBarsPadding()
            .imePadding()
            .padding(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                // Taps on the card itself must not fall through to the dismiss area.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.padding(20.dp)) { content() }
        }
    }
}

@Composable
private fun NoteForm(onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Text("Quick note", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("What's on your mind?") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 220.dp).focusRequester(focusRequester),
    )
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Spacer(Modifier.padding(4.dp))
        Button(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") }
    }
}

/** Notes belong to an account, so without one there is nowhere to save. */
@Composable
private fun SignedOutMessage(onOpenApp: () -> Unit, onCancel: () -> Unit) {
    Text("Sign in first", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(
        "Open Insight Journal and sign in, then quick notes from the widget are saved to your journal.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Spacer(Modifier.padding(4.dp))
        Button(onClick = onOpenApp) { Text("Open app") }
    }
}
