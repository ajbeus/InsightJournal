package com.adam.insightjournal.ui.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adam.insightjournal.R
import com.adam.insightjournal.crypto.KeyManager
import com.adam.insightjournal.crypto.VaultState
import com.adam.insightjournal.crypto.WrongSecretException
import com.adam.insightjournal.crypto.passphraseProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Everything between "signed in" and "journal open": first-time passphrase
 * setup, unlocking, recovery, and the offline/error case. [journal] is shown
 * only once the key is unlocked.
 */
@Composable
fun VaultGate(keys: KeyManager, state: VaultState, onSignOut: () -> Unit, journal: @Composable () -> Unit) {
    when (state) {
        VaultState.Unlocked -> journal()
        VaultState.NeedsSetup -> SetupScreen(keys, onSignOut)
        is VaultState.Locked -> UnlockScreen(keys, state.biometricReady, onSignOut)
        is VaultState.Unavailable -> CenteredPage {
            Text("Can't open your journal yet", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(state.message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = keys::refresh) { Text("Try again") }
            TextButton(onClick = onSignOut) { Text("Sign out") }
        }
        VaultState.Checking, VaultState.NoUser -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

// ---------------------------------------------------------------- Setup

@Composable
private fun SetupScreen(keys: KeyManager, onSignOut: () -> Unit) {
    // Saveable so the code survives rotation; it is shown exactly once.
    var recoveryCode by rememberSaveable { mutableStateOf<String?>(null) }
    val code = recoveryCode
    if (code == null) {
        ChoosePassphrase(keys, onSignOut, onCreated = { recoveryCode = it })
    } else {
        SaveRecoveryCode(code, onDone = keys::finishSetup)
    }
}

@Composable
private fun ChoosePassphrase(keys: KeyManager, onSignOut: () -> Unit, onCreated: (String) -> Unit) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val problem = passphraseProblem(passphrase)
    val mismatch = confirm.isNotEmpty() && confirm != passphrase
    val canCreate = !busy && problem == null && confirm == passphrase

    CenteredPage {
        Logo()
        Text("Lock your journal", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your entries are encrypted on this phone before they're saved, with a passphrase only you know. " +
                "No one else can read them, including the app's developer.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "This is separate from your account password. Choose something you'll remember.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        PassphraseField(
            value = passphrase,
            onValueChange = { passphrase = it; error = null },
            label = "Passphrase",
            supporting = if (passphrase.isNotEmpty() && problem != null) problem else "At least 8 characters",
            isError = passphrase.isNotEmpty() && problem != null,
        )
        Spacer(Modifier.height(8.dp))
        PassphraseField(
            value = confirm,
            onValueChange = { confirm = it; error = null },
            label = "Type it again",
            supporting = if (mismatch) "The two don't match" else null,
            isError = mismatch,
        )
        ErrorText(error)
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = canCreate,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                scope.launch {
                    try {
                        onCreated(keys.setUp(passphrase))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.localizedMessage ?: "Couldn't set up encryption. Check your connection."
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            if (busy) BusyIndicator() else Text("Encrypt my journal")
        }
        TextButton(onClick = onSignOut) { Text("Sign out") }
    }
}

@Composable
private fun SaveRecoveryCode(code: String, onDone: () -> Unit) {
    var saved by rememberSaveable { mutableStateOf(false) }
    CenteredPage {
        Text("Save your recovery code", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "If you forget your passphrase, this code is the only way back into your journal. " +
                "Write it down or keep it in a password manager. It won't be shown again.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        RecoveryCodeCard(code)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = saved, onCheckedChange = { saved = it })
            Text("I've saved my recovery code somewhere safe")
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onDone, enabled = saved, modifier = Modifier.fillMaxWidth()) { Text("Open my journal") }
    }
}

/** The code in large monospace type with a copy button. Also used by Settings. */
@Composable
fun RecoveryCodeCard(code: String) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Two lines of three groups read more easily than one long line.
            code.split("-").chunked(3).forEach { groups ->
                Text(
                    groups.joinToString("  "),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { context.copySensitive(code) }) { Text("Copy") }
        }
    }
}

// ---------------------------------------------------------------- Unlock

@Composable
private fun UnlockScreen(keys: KeyManager, biometricReady: Boolean, onSignOut: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(UnlockMode.PASSPHRASE) }
    when (mode) {
        UnlockMode.PASSPHRASE -> PassphraseUnlock(
            keys = keys,
            biometricReady = biometricReady,
            onForgot = { mode = UnlockMode.RECOVERY },
            onSignOut = onSignOut,
        )
        UnlockMode.RECOVERY -> RecoveryUnlock(keys, onBack = { mode = UnlockMode.PASSPHRASE })
    }
}

private enum class UnlockMode { PASSPHRASE, RECOVERY }

@Composable
private fun PassphraseUnlock(
    keys: KeyManager,
    biometricReady: Boolean,
    onForgot: () -> Unit,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    var passphrase by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var showReset by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun promptBiometric() {
        val cipher = keys.biometricUnlockCipher()
        if (cipher == null) {
            error = "Fingerprint unlock was turned off because this phone's biometrics changed. Use your passphrase."
            return
        }
        context.showBiometricPrompt(
            title = "Unlock your journal",
            subtitle = "Insight Journal",
            cipher = cipher,
            onSuccess = keys::finishBiometricUnlock,
            onError = { error = it },
        )
    }

    // Offer the fingerprint straight away; the passphrase stays available underneath.
    LaunchedEffect(biometricReady) { if (biometricReady) promptBiometric() }

    fun submit() {
        if (passphrase.isEmpty() || busy) return
        busy = true
        scope.launch {
            try {
                keys.unlockWithPassphrase(passphrase)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WrongSecretException) {
                error = "That passphrase isn't right."
            } catch (e: Exception) {
                error = e.localizedMessage ?: "Couldn't unlock. Check your connection."
            } finally {
                busy = false
            }
        }
    }

    CenteredPage {
        Logo()
        Text("Unlock your journal", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        PassphraseField(
            value = passphrase,
            onValueChange = { passphrase = it; error = null },
            label = "Passphrase",
            onDone = ::submit,
        )
        ErrorText(error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = ::submit, enabled = passphrase.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) BusyIndicator() else Text("Unlock")
        }
        if (biometricReady) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = ::promptBiometric, modifier = Modifier.fillMaxWidth()) { Text("Use fingerprint") }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onForgot) { Text("Forgot passphrase? Use recovery code") }
        TextButton(onClick = { showReset = true }) { Text("Lost both? Start over") }
        TextButton(onClick = onSignOut) { Text("Sign out") }
    }

    if (showReset) ResetDialog(keys, onDismiss = { showReset = false })
}

@Composable
private fun RecoveryUnlock(keys: KeyManager, onBack: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val problem = passphraseProblem(passphrase)

    CenteredPage {
        Text("Use your recovery code", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter the code you saved when you set up encryption, then choose a new passphrase.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it; error = null },
            label = { Text("Recovery code") },
            placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
            ),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        PassphraseField(
            value = passphrase,
            onValueChange = { passphrase = it; error = null },
            label = "New passphrase",
            supporting = if (passphrase.isNotEmpty() && problem != null) problem else "At least 8 characters",
            isError = passphrase.isNotEmpty() && problem != null,
        )
        ErrorText(error)
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !busy && code.isNotBlank() && problem == null,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                scope.launch {
                    try {
                        keys.recover(code, passphrase)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: WrongSecretException) {
                        error = "That recovery code isn't right."
                    } catch (e: Exception) {
                        error = e.localizedMessage ?: "Couldn't unlock. Check your connection."
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            if (busy) BusyIndicator() else Text("Unlock and set passphrase")
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}

/** Deletes everything after the user types DELETE. The only way out with no passphrase and no code. */
@Composable
private fun ResetDialog(keys: KeyManager, onDismiss: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Delete your journal and start over?") },
        text = {
            Column {
                Text(
                    "Without your passphrase or recovery code, your entries can't be decrypted by anyone. " +
                        "Starting over permanently deletes every entry, quick note and insight in this account, " +
                        "on all your devices. This can't be undone.",
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Type DELETE to confirm") },
                    singleLine = true,
                )
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = typed.trim() == "DELETE" && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            keys.deleteEverything()
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.localizedMessage ?: "Couldn't delete. Check your connection."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Deleting…" else "Delete everything") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- Shared pieces

@Composable
fun PassphraseField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String? = null,
    isError: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        isError = isError,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
            autoCorrectEnabled = false,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun ErrorText(error: String?) {
    if (error == null) return
    Spacer(Modifier.height(8.dp))
    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun BusyIndicator() {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
}

@Composable
private fun Logo() {
    Image(painter = painterResource(R.drawable.logo_tile), contentDescription = null, modifier = Modifier.size(72.dp))
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun CenteredPage(content: @Composable () -> Unit) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

/** Copies text and marks it sensitive, so Android 13+ doesn't show it in the clipboard preview. */
private fun Context.copySensitive(text: String) {
    val clip = ClipData.newPlainText("Recovery code", text).apply {
        description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
}
