package com.adam.insightjournal.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps this phone's copy of the data key, so the passphrase is needed once per
 * device rather than on every launch.
 *
 * The data key is never written down as-is. It is encrypted with a key that
 * lives inside Android Keystore (hardware-backed on most phones) and cannot be
 * read out of it, then stored in app-private preferences that are excluded from
 * backups. Two modes:
 *  - plain: the Keystore key can be used freely, so the app unlocks silently.
 *  - biometric: the Keystore key only works right after a fingerprint/face check,
 *    so every unlock needs one.
 */
class DeviceKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    // ---- Plain mode ----

    fun savePlain(uid: String, dataKey: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey(PLAIN_ALIAS, biometric = false))
        }
        prefs.edit()
            .putString(plainPref(uid), encode(cipher.iv, cipher.doFinal(dataKey)))
            .remove(biometricPref(uid))
            .apply()
    }

    /** The data key, or null if this phone has no plain copy (or it can't be read any more). */
    fun loadPlain(uid: String): ByteArray? {
        val stored = prefs.getString(plainPref(uid), null) ?: return null
        return try {
            val (iv, ciphertext) = decode(stored)
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(PLAIN_ALIAS, biometric = false), GCMParameterSpec(128, iv))
                doFinal(ciphertext)
            }
        } catch (e: GeneralSecurityException) {
            // E.g. restored onto a new phone, where the Keystore key doesn't exist.
            Log.w(TAG, "Dropping an unreadable device key copy", e)
            prefs.edit().remove(plainPref(uid)).apply()
            null
        }
    }

    // ---- Biometric mode ----

    fun hasBiometric(uid: String): Boolean = prefs.contains(biometricPref(uid))

    /**
     * A cipher ready to encrypt the data key under a fresh biometric-only Keystore
     * key. It only works after the user passes a biometric prompt that is given
     * this cipher; then hand it to [saveBiometric].
     */
    fun biometricEncryptCipher(): Cipher {
        keyStore.deleteEntry(BIOMETRIC_ALIAS) // start clean, e.g. after fingerprints changed
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey(BIOMETRIC_ALIAS, biometric = true))
        }
    }

    /** Stores the data key under the biometric key and removes the plain copy. */
    fun saveBiometric(uid: String, authenticatedCipher: Cipher, dataKey: ByteArray) {
        val ciphertext = authenticatedCipher.doFinal(dataKey)
        prefs.edit()
            .putString(biometricPref(uid), encode(authenticatedCipher.iv, ciphertext))
            .remove(plainPref(uid))
            .apply()
    }

    /**
     * A cipher ready to decrypt this phone's biometric copy once the user passes
     * the prompt. Null when there is no copy, or when it became unusable because
     * the phone's fingerprints or face data changed (Android then destroys the key).
     */
    fun biometricDecryptCipher(uid: String): Cipher? {
        val stored = prefs.getString(biometricPref(uid), null) ?: return null
        return try {
            val (iv, _) = decode(stored)
            val key = keyStore.getKey(BIOMETRIC_ALIAS, null) as? SecretKey ?: throw KeyPermanentlyInvalidatedException()
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Biometric key is no longer usable", e)
            forgetBiometric(uid)
            null
        }
    }

    fun openBiometric(uid: String, authenticatedCipher: Cipher): ByteArray {
        val stored = prefs.getString(biometricPref(uid), null) ?: error("No biometric copy")
        return authenticatedCipher.doFinal(decode(stored).second)
    }

    fun forgetBiometric(uid: String) {
        prefs.edit().remove(biometricPref(uid)).apply()
    }

    /** Removes every copy for this user, e.g. on sign-out. */
    fun forget(uid: String) {
        prefs.edit().remove(plainPref(uid)).remove(biometricPref(uid)).apply()
    }

    // ---- Helpers ----

    private fun getOrCreateKey(alias: String, biometric: Boolean): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (biometric) {
                    setUserAuthenticationRequired(true)
                    // New fingerprints added later must not be able to unlock this key.
                    setInvalidatedByBiometricEnrollment(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // 0 = a fresh biometric check for every single use.
                        setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    }
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun encode(iv: ByteArray, ciphertext: ByteArray): String =
        Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)

    private fun decode(stored: String): Pair<ByteArray, ByteArray> {
        val (iv, ciphertext) = stored.split(":", limit = 2)
        return Base64.decode(iv, Base64.NO_WRAP) to Base64.decode(ciphertext, Base64.NO_WRAP)
    }

    private fun plainPref(uid: String) = "dek_plain_$uid"
    private fun biometricPref(uid: String) = "dek_biometric_$uid"

    private companion object {
        const val TAG = "DeviceKeyStore"
        /** Excluded from backups in res/xml/backup_rules.xml and data_extraction_rules.xml. */
        const val PREFS = "security"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PLAIN_ALIAS = "insightjournal_device_key"
        const val BIOMETRIC_ALIAS = "insightjournal_biometric_key"
    }
}
