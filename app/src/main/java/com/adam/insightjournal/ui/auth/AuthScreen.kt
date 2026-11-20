package com.adam.insightjournal.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adam.insightjournal.R
import com.adam.insightjournal.data.AuthRepository
import kotlinx.coroutines.launch

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val isCreatingAccount: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** A non-error message, e.g. "reset link sent". */
    val info: String? = null,
) {
    val canSubmit: Boolean get() = email.isNotBlank() && password.length >= 6 && !isLoading
}

class AuthViewModel(private val auth: AuthRepository) : ViewModel() {

    var uiState by mutableStateOf(AuthUiState())
        private set

    fun onEmailChange(value: String) { uiState = uiState.copy(email = value, error = null, info = null) }
    fun onPasswordChange(value: String) { uiState = uiState.copy(password = value, error = null, info = null) }
    fun toggleMode() {
        uiState = uiState.copy(isCreatingAccount = !uiState.isCreatingAccount, error = null, info = null)
    }

    fun sendPasswordReset() {
        val email = uiState.email.trim()
        if (email.isEmpty()) {
            uiState = uiState.copy(error = "Enter your email above first.", info = null)
            return
        }
        uiState = uiState.copy(isLoading = true, error = null, info = null)
        viewModelScope.launch {
            uiState = try {
                auth.sendPasswordReset(email)
                // Worded so it doesn't reveal whether an account exists for that address.
                uiState.copy(isLoading = false, info = "If there's an account for $email, a reset link is on its way.")
            } catch (e: Exception) {
                uiState.copy(isLoading = false, error = e.localizedMessage ?: "Couldn't send the email. Try again.")
            }
        }
    }

    /** On success the auth state changes and the root composable swaps this screen out. */
    fun submit() {
        val state = uiState
        if (!state.canSubmit) return
        uiState = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                if (state.isCreatingAccount) auth.signUp(state.email.trim(), state.password)
                else auth.signIn(state.email.trim(), state.password)
                uiState = uiState.copy(isLoading = false)
            } catch (e: Exception) {
                uiState = uiState.copy(
                    isLoading = false,
                    error = e.localizedMessage ?: "Something went wrong. Try again.",
                )
            }
        }
    }
}

@Composable
fun AuthScreen(viewModel: AuthViewModel) {
    val state = viewModel.uiState
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.logo_tile),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("Insight Journal", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.isCreatingAccount) "Create your account" else "Sign in to your journal",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("Password") },
                supportingText = { if (state.isCreatingAccount) Text("At least 6 characters") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.info != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    state.info,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = viewModel::submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (state.isCreatingAccount) "Create account" else "Sign in")
                }
            }
            if (!state.isCreatingAccount) {
                TextButton(onClick = viewModel::sendPasswordReset, enabled = !state.isLoading) {
                    Text("Forgot password?")
                }
            }
            TextButton(onClick = viewModel::toggleMode) {
                Text(
                    if (state.isCreatingAccount) "Have an account? Sign in"
                    else "New here? Create an account"
                )
            }
        }
    }
}
