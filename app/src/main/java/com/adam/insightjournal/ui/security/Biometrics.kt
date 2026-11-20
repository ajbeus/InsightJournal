package com.adam.insightjournal.ui.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/** True when the phone has a strong biometric (fingerprint, or secure face unlock) set up. */
fun biometricsAvailable(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

/**
 * Shows the system fingerprint/face prompt, bound to [cipher]: Android only lets
 * the cipher be used if the check passes, so the key can't be unlocked by
 * skipping the prompt. [onError] is not called when the user just cancels.
 */
fun Context.showBiometricPrompt(
    title: String,
    subtitle: String,
    cipher: Cipher,
    onSuccess: (Cipher) -> Unit,
    onError: (String) -> Unit,
) {
    val activity = findFragmentActivity() ?: return onError("Biometrics aren't available here.")
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authenticated = result.cryptoObject?.cipher
                if (authenticated == null) onError("Biometric check didn't unlock the key.") else onSuccess(authenticated)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                if (!cancelled) onError(errString.toString())
            }
            // onAuthenticationFailed (a finger that didn't match) is left to the system
            // prompt, which shows its own "Not recognized" and lets the user retry.
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(BIOMETRIC_STRONG)
        .setNegativeButtonText("Use passphrase")
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
