package com.adam.insightjournal.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class JournalCryptoTest {

    private val key = JournalCrypto.newDataKey()
    private val text = "Went to Disneyland with Sam today.".toByteArray()

    // Low iteration counts keep the tests fast; the real counts only change the speed.
    private val fastIterations = 1_000

    @Test
    fun `sealed text opens again with the same key and AAD`() {
        val sealed = JournalCrypto.seal(key, text, "entries/abc")
        assertArrayEquals(text, JournalCrypto.open(key, sealed, "entries/abc"))
    }

    @Test
    fun `sealed text does not contain the plaintext`() {
        val sealed = JournalCrypto.seal(key, text, "entries/abc")
        val bytes = Base64.getDecoder().decode(sealed)
        assertEquals(-1, String(bytes, Charsets.ISO_8859_1).indexOf("Disneyland"))
    }

    @Test
    fun `sealing the same text twice gives different output`() {
        // A fresh nonce each time: identical entries don't look identical in the database.
        assertNotEquals(
            JournalCrypto.seal(key, text, "entries/abc"),
            JournalCrypto.seal(key, text, "entries/abc"),
        )
    }

    @Test
    fun `wrong key fails`() {
        val sealed = JournalCrypto.seal(key, text, "entries/abc")
        assertThrows(CryptoException::class.java) {
            JournalCrypto.open(JournalCrypto.newDataKey(), sealed, "entries/abc")
        }
    }

    @Test
    fun `blob moved to another document fails`() {
        val sealed = JournalCrypto.seal(key, text, "entries/abc")
        assertThrows(CryptoException::class.java) { JournalCrypto.open(key, sealed, "entries/xyz") }
    }

    @Test
    fun `any changed byte fails`() {
        val bytes = Base64.getDecoder().decode(JournalCrypto.seal(key, text, "entries/abc"))
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 1).toByte()
        val tampered = Base64.getEncoder().encodeToString(bytes)
        assertThrows(CryptoException::class.java) { JournalCrypto.open(key, tampered, "entries/abc") }
    }

    @Test
    fun `garbage input fails cleanly`() {
        assertThrows(CryptoException::class.java) { JournalCrypto.open(key, "not base64!!", "a") }
        assertThrows(CryptoException::class.java) { JournalCrypto.open(key, "AAAA", "a") }
    }

    @Test
    fun `wrapped data key unwraps with the right passphrase only`() {
        val wrapped = JournalCrypto.wrapDataKey(key, "correct horse".toCharArray(), "dek/u1", fastIterations)
        assertArrayEquals(key, JournalCrypto.unwrapDataKey(wrapped, "correct horse".toCharArray(), "dek/u1"))
        assertThrows(WrongSecretException::class.java) {
            JournalCrypto.unwrapDataKey(wrapped, "correct horsf".toCharArray(), "dek/u1")
        }
    }

    @Test
    fun `same passphrase gives different keys for different salts`() {
        val a = JournalCrypto.deriveKey("hope".toCharArray(), JournalCrypto.newSalt(), fastIterations)
        val b = JournalCrypto.deriveKey("hope".toCharArray(), JournalCrypto.newSalt(), fastIterations)
        assertEquals(32, a.size)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `recovery codes are formatted and normalize back`() {
        val code = RecoveryCode.generate()
        assertEquals(29, code.length) // 24 characters + 5 dashes
        assertEquals(code.replace("-", ""), RecoveryCode.normalize(code.lowercase().replace("-", " ")))
    }

    @Test
    fun `recovery code input forgives look-alike letters`() {
        val code = "0000-1111-2222-3333-4444-5555"
        assertEquals(code.replace("-", ""), RecoveryCode.normalize("OOOO IIII 2222 3333 4444 5555".replace("IIII", "ILil")))
        assertNull(RecoveryCode.normalize("too short"))
        assertNull(RecoveryCode.normalize("UUUU-1111-2222-3333-4444-5555")) // U is not in the alphabet
    }

    @Test
    fun `passphrase rules`() {
        assertEquals("Use at least 8 characters.", passphraseProblem("short"))
        assertEquals("The passphrase can't be only spaces.", passphraseProblem("          "))
        assertNull(passphraseProblem("a long enough one"))
    }
}
