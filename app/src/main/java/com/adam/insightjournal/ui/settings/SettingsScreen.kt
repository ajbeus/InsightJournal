package com.adam.insightjournal.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adam.insightjournal.ai.AiSettings
import com.adam.insightjournal.crypto.KeyManager
import com.adam.insightjournal.crypto.WrongSecretException
import com.adam.insightjournal.crypto.passphraseProblem
import com.adam.insightjournal.data.AppUser
import com.adam.insightjournal.ui.ai.rememberAiGate
import com.adam.insightjournal.ui.security.ErrorText
import com.adam.insightjournal.ui.security.PassphraseField
import com.adam.insightjournal.ui.security.RecoveryCodeCard
import com.adam.insightjournal.ui.security.biometricsAvailable
import com.adam.insightjournal.ui.security.showBiometricPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * One place for every setting: appearance, journal security, AI features and
 * the account. [keys] is null in demo mode, where there is no encryption.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    user: AppUser?,
    keys: KeyManager?,
    aiSettings: AiSettings,
    aiConfigured: Boolean,
    onAppearance: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Section("Appearance")
            ActionRow("Theme, colors and font", onClick = onAppearance)

            if (keys != null) {
                Section("Privacy and security")
                SecuritySettings(keys)
            }

            Section("AI features")
            AiToggle(aiSettings, aiConfigured)

            if (user != null && keys != null) {
                Section("Account")
                user.email?.let { InfoRow("Signed in as", it) }
                ActionRow("Sign out", detail = "Removes this phone's copy of your journal key", onClick = onSignOut)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SecuritySettings(keys: KeyManager) {
    val context = LocalContext.current
    val biometricOn by keys.biometricEnabled.collectAsStateWithLifecycle()
    val canUseBiometrics = remember { biometricsAvailable(context) }
    var dialog by rememberSaveable { mutableStateOf<SecurityDialog?>(null) }

    InfoRow("Encryption", "On. Entries are encrypted on this phone before they're saved.")

    SwitchRow(
        title = "Unlock with fingerprint",
        detail = when {
            !canUseBiometrics && !biometricOn -> "Set up a fingerprint or face unlock in your phone's settings first"
            biometricOn -> "Asks for your fingerprint each time the app opens, and after 5 minutes away"
            else -> "Opens without asking on this phone"
        },
        checked = biometricOn,
        enabled = canUseBiometrics || biometricOn,
        onCheckedChange = { on ->
            if (!on) {
                keys.disableBiometric()
                return@SwitchRow
            }
            try {
                context.showBiometricPrompt(
                    title = "Turn on fingerprint unlock",
                    subtitle = "Confirm it's you",
                    cipher = keys.biometricEnableCipher(),
                    onSuccess = keys::finishBiometricEnable,
                    onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                )
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't set up fingerprint unlock on this phone.", Toast.LENGTH_LONG).show()
            }
        },
    )
    ActionRow("Change passphrase", onClick = { dialog = SecurityDialog.CHANGE_PASSPHRASE })
    ActionRow(
        "Get a new recovery code",
        detail = "Your old code stops working",
        onClick = { dialog = SecurityDialog.NEW_RECOVERY_CODE },
    )
    ActionRow(
        "Lock now",
        detail = if (biometricOn) "Your fingerprint opens it again" else "Your passphrase will be needed to open it again",
        onClick = keys::lock,
    )

    when (dialog) {
        SecurityDialog.CHANGE_PASSPHRASE -> ChangePassphraseDialog(keys, onDismiss = { dialog = null })
        SecurityDialog.NEW_RECOVERY_CODE -> NewRecoveryCodeDialog(keys, onDismiss = { dialog = null })
        null -> Unit
    }
}

private enum class SecurityDialog { CHANGE_PASSPHRASE, NEW_RECOVERY_CODE }

@Composable
private fun ChangePassphraseDialog(keys: KeyManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val problem = passphraseProblem(new)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Change passphrase") },
        text = {
            Column {
                PassphraseField(current, { current = it; error = null }, "Current passphrase")
                PassphraseField(
                    new, { new = it; error = null }, "New passphrase",
                    supporting = if (new.isNotEmpty() && problem != null) problem else null,
                    isError = new.isNotEmpty() && problem != null,
                )
                PassphraseField(
                    confirm, { confirm = it; error = null }, "Type the new one again",
                    supporting = if (confirm.isNotEmpty() && confirm != new) "The two don't match" else null,
                    isError = confirm.isNotEmpty() && confirm != new,
                )
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && current.isNotEmpty() && problem == null && confirm == new,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            keys.changePassphrase(current, new)
                            Toast.makeText(context, "Passphrase changed", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: WrongSecretException) {
                            error = "Your current passphrase isn't right."
                        } catch (e: Exception) {
                            error = e.localizedMessage ?: "Couldn't change it. Check your connection."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Changing…" else "Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun NewRecoveryCodeDialog(keys: KeyManager, onDismiss: () -> Unit) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy && code == null) onDismiss() },
        title = { Text(if (code == null) "New recovery code" else "Save your new recovery code") },
        text = {
            Column {
                val shown = code
                if (shown == null) {
                    Text("Enter your passphrase. Your current recovery code will stop working.")
                    Spacer(Modifier.height(8.dp))
                    PassphraseField(passphrase, { passphrase = it; error = null }, "Passphrase")
                    ErrorText(error)
                } else {
                    Text("This replaces your old code. It won't be shown again.")
                    Spacer(Modifier.height(12.dp))
                    RecoveryCodeCard(shown)
                }
            }
        },
        confirmButton = {
            if (code == null) {
                TextButton(
                    enabled = !busy && passphrase.isNotEmpty(),
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                code = keys.newRecoveryCode(passphrase)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: WrongSecretException) {
                                error = "That passphrase isn't right."
                            } catch (e: Exception) {
                                error = e.localizedMessage ?: "Couldn't make a new code. Check your connection."
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(if (busy) "Working…" else "Continue") }
            } else {
                TextButton(onClick = onDismiss) { Text("I've saved it") }
            }
        },
        dismissButton = {
            if (code == null) TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

@Composable
private fun AiToggle(aiSettings: AiSettings, aiConfigured: Boolean) {
    val enabled by aiSettings.enabled.collectAsStateWithLifecycle()
    val gate = rememberAiGate(aiSettings)
    SwitchRow(
        title = "Use AI features",
        detail = when {
            !aiConfigured -> "Not available in this build (no Anthropic API key)"
            enabled -> "Summaries, entries from notes and weekly recaps. Text a feature uses is sent to Anthropic."
            else -> "Off. Nothing is sent to an AI service."
        },
        checked = enabled && aiConfigured,
        enabled = aiConfigured,
        // Turning on goes through the consent screen; turning off is immediate.
        onCheckedChange = { on -> if (on) gate.run {} else aiSettings.setEnabled(false) },
    )
}

// ---------------------------------------------------------------- Rows

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ActionRow(title: String, detail: String? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoRow(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
