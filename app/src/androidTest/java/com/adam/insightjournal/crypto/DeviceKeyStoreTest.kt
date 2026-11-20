package com.adam.insightjournal.crypto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device or emulator: the parts of the encryption that need the real
 * Android Keystore and the real PBKDF2 speed. The biometric mode needs a person
 * to touch the sensor, so it is tested by hand.
 */
@RunWith(AndroidJUnit4::class)
class DeviceKeyStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = DeviceKeyStore(context)
    private val uid = "test-user"

    @After
    fun cleanUp() = store.forget(uid)

    @Test
    fun plainCopyRoundTripsThroughKeystore() {
        val dataKey = JournalCrypto.newDataKey()
        store.savePlain(uid, dataKey)
        assertArrayEquals(dataKey, store.loadPlain(uid))
    }

    @Test
    fun storedCopyIsNotTheRawKey() {
        val dataKey = JournalCrypto.newDataKey()
        store.savePlain(uid, dataKey)
        val prefs = context.getSharedPreferences("security", 0).all.values.joinToString()
        val raw = java.util.Base64.getEncoder().encodeToString(dataKey)
        assertTrue(raw !in prefs)
    }

    @Test
    fun forgetRemovesTheCopy() {
        store.savePlain(uid, JournalCrypto.newDataKey())
        store.forget(uid)
        assertNull(store.loadPlain(uid))
    }

    @Test
    fun passphraseWrapTakesUnderFiveSeconds() {
        // The real iteration count, on real hardware: the user waits this long at unlock.
        val start = System.nanoTime()
        val wrapped = JournalCrypto.wrapDataKey(
            JournalCrypto.newDataKey(), "correct horse".toCharArray(), "aad", JournalCrypto.PASSPHRASE_ITERATIONS,
        )
        val millis = (System.nanoTime() - start) / 1_000_000
        Log.i("DeviceKeyStoreTest", "PBKDF2 with ${wrapped.iterations} iterations took $millis ms")
        assertTrue("took $millis ms", millis < 5_000)
    }
}
