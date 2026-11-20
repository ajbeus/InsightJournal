package com.adam.insightjournal.ui.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adam.insightjournal.ai.AiSettings

/**
 * Wraps every AI action. While AI features are on it just runs the action;
 * while they're off it first shows the consent screen, and runs the action only
 * if the user turns them on there.
 */
@Stable
class AiGate internal constructor(private val settings: AiSettings) {
    internal var pending by mutableStateOf<(() -> Unit)?>(null)

    fun run(action: () -> Unit) {
        if (settings.enabled.value) action() else pending = action
    }
}

/** Remembers an [AiGate] for this screen and shows its consent dialog when needed. */
@Composable
fun rememberAiGate(settings: AiSettings): AiGate {
    val gate = remember(settings) { AiGate(settings) }
    gate.pending?.let { action ->
        AiConsentDialog(
            onAccept = {
                settings.setEnabled(true)
                gate.pending = null
                action()
            },
            onDismiss = { gate.pending = null },
        )
    }
    return gate
}

/** What turning on AI features means, said plainly before anything is sent. */
@Composable
fun AiConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn on AI features?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "AI features send text from your journal to Anthropic, the company that makes Claude, " +
                        "so its model can write the result. Only the text a feature needs is sent:",
                )
                Spacer(Modifier.height(8.dp))
                Text("• Note summaries: that note")
                Text("• Journal entry from notes: that day's notes")
                Text("• Weekly summary: the past 7 days of entries and notes")
                Spacer(Modifier.height(8.dp))
                Text(
                    "That text leaves your phone without the journal's encryption, and Anthropic handles it " +
                        "under its own API data policy. Everything else stays encrypted, and nothing is sent " +
                        "while AI features are off.",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "You can turn this off any time in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
