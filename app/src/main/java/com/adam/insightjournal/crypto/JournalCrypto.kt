package com.adam.insightjournal.crypto

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The app's encryption, in one place and with no Android dependencies, so it can
 * be unit-tested on the JVM and read top to bottom.
 *
 * Two ideas:
 *  1. Content is sealed with AES-256-GCM under one random "data key". GCM both
 *     hides the text and detects tampering: changing a single bit makes [open] fail.
 *  2. The data key itself is never stored as-is. It is "wrapped" (sealed) under a
 *     key derived from the user's passphrase, and separately under one derived
 *     from their recovery code. See [wrapDataKey].
 */
object JournalCrypto {
    const val KEY_BYTES = 32           // AES-256
    private const val NONCE_BYTES = 12 // the size GCM is designed for
    private const val TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val FORMAT_VERSION: Byte = 1

    /** PBKDF2 rounds for a passphrase: slows down anyone guessing passphrases offline. */
    const val PASSPHRASE_ITERATIONS = 600_000

    /** A recovery code is already 120 random bits, so it needs far less stretching. */
    const val RECOVERY_ITERATIONS = 100_000

    private val random = SecureRandom()

    fun newDataKey(): ByteArray = randomBytes(KEY_BYTES)

    fun newSalt(): ByteArray = randomBytes(SALT_BYTES)

    /**
     * Encrypts [plaintext] and returns Base64 text: version byte, nonce, then the
     * ciphertext with its authentication tag.
     *
     * [aad] ("additional authenticated data") is not stored but must be given again
     * to [open]. Callers pass where the data lives (e.g. "entries/abc123"), so a
     * sealed blob copied into a different document refuses to open.
     */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: String): String {
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad.toByteArray(Charsets.UTF_8))
        }
        val ciphertext = cipher.doFinal(plaintext)
        val out = ByteBuffer.allocate(1 + nonce.size + ciphertext.size)
            .put(FORMAT_VERSION).put(nonce).put(ciphertext)
        return Base64.getEncoder().encodeToString(out.array())
    }

    /** Reverses [seal]. Throws [CryptoException] if the key, the AAD or the data is wrong. */
    fun open(key: ByteArray, sealed: String, aad: String): ByteArray {
        val bytes = try {
            Base64.getDecoder().decode(sealed)
        } catch (e: IllegalArgumentException) {
            throw CryptoException("Not valid encrypted data", e)
        }
        if (bytes.size < 1 + NONCE_BYTES + TAG_BITS / 8) throw CryptoException("Encrypted data is too short")
        if (bytes[0] != FORMAT_VERSION) throw CryptoException("Unknown encryption format ${bytes[0]}")
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(TAG_BITS, bytes, 1, NONCE_BYTES),
                )
                updateAAD(aad.toByteArray(Charsets.UTF_8))
                doFinal(bytes, 1 + NONCE_BYTES, bytes.size - 1 - NONCE_BYTES)
            }
        } catch (e: AEADBadTagException) {
            throw CryptoException("Wrong key, or the data was changed", e)
        } catch (e: GeneralSecurityException) {
            throw CryptoException("Couldn't decrypt", e)
        }
    }

    /**
     * Turns a passphrase (or recovery code) into a 256-bit key with
     * PBKDF2-HMAC-SHA256. The salt makes the same passphrase give a different key
     * for every user; the iterations make each guess expensive.
     */
    fun deriveKey(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret, salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Seals [dataKey] under a key derived from [secret]. Everything returned is safe to store. */
    fun wrapDataKey(dataKey: ByteArray, secret: CharArray, aad: String, iterations: Int): WrappedKey {
        val salt = newSalt()
        val wrappingKey = deriveKey(secret, salt, iterations)
        return WrappedKey(
            salt = Base64.getEncoder().encodeToString(salt),
            iterations = iterations,
            sealed = seal(wrappingKey, dataKey, aad),
        ).also { wrappingKey.fill(0) }
    }

    /** Reverses [wrapDataKey]. Throws [WrongSecretException] for a wrong passphrase or code. */
    fun unwrapDataKey(wrapped: WrappedKey, secret: CharArray, aad: String): ByteArray {
        val wrappingKey = deriveKey(secret, Base64.getDecoder().decode(wrapped.salt), wrapped.iterations)
        return try {
            open(wrappingKey, wrapped.sealed, aad)
        } catch (e: CryptoException) {
            // GCM can't tell "wrong passphrase" from "corrupted"; the passphrase is far likelier.
            throw WrongSecretException(e)
        } finally {
            wrappingKey.fill(0)
        }
    }

    private fun randomBytes(count: Int) = ByteArray(count).also(random::nextBytes)
}

/** A data key sealed under a passphrase- or recovery-code-derived key, as stored in Firestore. */
data class WrappedKey(val salt: String, val iterations: Int, val sealed: String)

open class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

class WrongSecretException(cause: Throwable) : CryptoException("Wrong passphrase or recovery code", cause)

/**
 * The one-time code that can unlock the journal when the passphrase is forgotten.
 * 24 characters from Crockford's base32 alphabet (no I, L, O or U, so nothing
 * looks alike), shown in groups of four: 120 random bits.
 */
object RecoveryCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val LENGTH = 24
    private val random = SecureRandom()

    fun generate(): String =
        (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }
            .chunked(4) { it.joinToString("") }
            .joinToString("-")

    /**
     * Cleans up what the user typed: case, spaces and dashes don't matter, and
     * letters that look like digits are read as those digits. Null if it can't be a code.
     */
    fun normalize(input: String): String? {
        val cleaned = input.uppercase()
            .filterNot { it == '-' || it.isWhitespace() }
            .map {
                when (it) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    else -> it
                }
            }
            .joinToString("")
        return cleaned.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }
}

/** The rules a new passphrase must meet. Null when it's acceptable, otherwise why not. */
fun passphraseProblem(passphrase: String): String? = when {
    passphrase.length < 8 -> "Use at least 8 characters."
    passphrase.isBlank() -> "The passphrase can't be only spaces."
    else -> null
}
