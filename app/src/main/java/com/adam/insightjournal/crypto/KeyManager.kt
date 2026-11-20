package com.adam.insightjournal.crypto

import android.util.Log
import com.adam.insightjournal.data.AuthRepository
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

/** Where the signed-in user's journal key stands. The UI shows a screen per state. */
sealed interface VaultState {
    data object NoUser : VaultState
    data object Checking : VaultState
    /** This account has never set a passphrase. */
    data object NeedsSetup : VaultState
    /** A key exists but this phone doesn't hold it right now. */
    data class Locked(val biometricReady: Boolean) : VaultState
    data object Unlocked : VaultState
    /** Couldn't tell which of the above applies, e.g. offline on first use. */
    data class Unavailable(val message: String) : VaultState
}

/**
 * Owns the journal's data key for the signed-in user: creating it, unlocking it
 * with the passphrase, recovery code or biometrics, and forgetting it.
 *
 * The key exists in plain form only in memory, inside [cipher]. Firestore holds
 * it wrapped at users/{uid}/keys/main (see [JournalCrypto.wrapDataKey]) and this
 * phone holds it wrapped by Android Keystore (see [DeviceKeyStore]).
 */
class KeyManager(
    private val auth: AuthRepository,
    private val deviceKeys: DeviceKeyStore,
    private val scope: CoroutineScope,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private val _state = MutableStateFlow<VaultState>(VaultState.Checking)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    private val _cipher = MutableStateFlow<ContentCipher?>(null)
    /** Non-null exactly while unlocked. Repositories seal and open content with it. */
    val cipher: StateFlow<ContentCipher?> = _cipher.asStateFlow()

    private val _biometricEnabled = MutableStateFlow(false)
    /** Whether this phone asks for a fingerprint/face to unlock. */
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    /** The key made by [setUp], held back until the user confirms they saved the recovery code. */
    private var pendingSetupKey: ByteArray? = null

    init {
        scope.launch {
            var previousUid: String? = null
            auth.currentUser.collect { user ->
                // Signing out removes this phone's copy, so the next person to sign in can't use it.
                if (user == null && previousUid != null) deviceKeys.forget(previousUid!!)
                previousUid = user?.uid
                lockInMemory()
                if (user == null) _state.value = VaultState.NoUser else refresh()
            }
        }
    }

    private fun uid(): String = auth.currentUser.value?.uid ?: error("No signed-in user")

    private fun keysDoc(uid: String): DocumentReference =
        db.collection("users").document(uid).collection("keys").document("main")

    /** Works out the state from scratch. Also the "Try again" action. */
    fun refresh() {
        val uid = auth.currentUser.value?.uid ?: return
        _state.value = VaultState.Checking
        _biometricEnabled.value = deviceKeys.hasBiometric(uid)
        deviceKeys.loadPlain(uid)?.let { dataKey ->
            unlockWith(dataKey)
            return
        }
        if (deviceKeys.hasBiometric(uid)) {
            // No network needed: the fingerprint opens this phone's own copy.
            _state.value = VaultState.Locked(biometricReady = true)
            return
        }
        scope.launch {
            _state.value = try {
                val snapshot = keysDoc(uid).get().await()
                when {
                    snapshot.exists() -> VaultState.Locked(biometricReady = false)
                    // "Missing" from the offline cache may just mean "never downloaded".
                    snapshot.metadata.isFromCache ->
                        VaultState.Unavailable("Connect to the internet to unlock your journal on this phone.")
                    else -> VaultState.NeedsSetup
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't check for a journal key", e)
                VaultState.Unavailable("Connect to the internet to unlock your journal on this phone.")
            }
        }
    }

    // ---- First-time setup ----

    /**
     * Creates the data key, stores it wrapped by [passphrase] and by a new
     * recovery code, and returns the code. The journal stays locked until
     * [finishSetup], so the user sees the code before anything else.
     */
    suspend fun setUp(passphrase: String): String {
        val uid = uid()
        val doc = keysDoc(uid)
        // Ask the server, not the cache: overwriting an existing key would make
        // every entry written with it unreadable.
        if (doc.get(Source.SERVER).await().exists()) {
            refresh()
            throw IllegalStateException("This account already has a passphrase. Unlock with it instead.")
        }
        val dataKey = JournalCrypto.newDataKey()
        val recoveryCode = RecoveryCode.generate()
        val (byPassphrase, byRecovery) = withContext(Dispatchers.Default) {
            wrapForPassphrase(uid, dataKey, passphrase) to wrapForRecovery(uid, dataKey, recoveryCode)
        }
        doc.set(
            mapOf(
                "version" to 1,
                "passphrase" to byPassphrase.toMap(),
                "recovery" to byRecovery.toMap(),
                "createdAt" to Timestamp.now(),
            )
        ).await()
        pendingSetupKey = dataKey
        return recoveryCode
    }

    /** Called once the user confirms the recovery code is saved. */
    fun finishSetup() {
        val dataKey = pendingSetupKey ?: return
        pendingSetupKey = null
        deviceKeys.savePlain(uid(), dataKey)
        unlockWith(dataKey)
    }

    // ---- Unlocking ----

    /** Throws [WrongSecretException] for a wrong passphrase. */
    suspend fun unlockWithPassphrase(passphrase: String) {
        val uid = uid()
        val wrapped = readWrapped(uid, "passphrase")
        val dataKey = withContext(Dispatchers.Default) {
            JournalCrypto.unwrapDataKey(wrapped, passphrase.toCharArray(), passphraseAad(uid))
        }
        rememberOnDevice(uid, dataKey)
        unlockWith(dataKey)
    }

    /**
     * Unlocks with the recovery code and sets [newPassphrase] in place of the
     * forgotten one. The same code keeps working afterwards.
     */
    suspend fun recover(code: String, newPassphrase: String) {
        val uid = uid()
        val normalized = RecoveryCode.normalize(code) ?: throw WrongSecretException(IllegalArgumentException())
        val wrapped = readWrapped(uid, "recovery")
        val dataKey = withContext(Dispatchers.Default) {
            JournalCrypto.unwrapDataKey(wrapped, normalized.toCharArray(), recoveryAad(uid))
        }
        val byPassphrase = withContext(Dispatchers.Default) { wrapForPassphrase(uid, dataKey, newPassphrase) }
        keysDoc(uid).update("passphrase", byPassphrase.toMap()).await()
        rememberOnDevice(uid, dataKey)
        unlockWith(dataKey)
    }

    /** For the biometric prompt that unlocks the journal; null if biometrics can't be used now. */
    fun biometricUnlockCipher(): Cipher? {
        val uid = uid()
        val cipher = deviceKeys.biometricDecryptCipher(uid)
        if (cipher == null) {
            _biometricEnabled.value = false
            _state.value = VaultState.Locked(biometricReady = false)
        }
        return cipher
    }

    fun finishBiometricUnlock(authenticatedCipher: Cipher) {
        unlockWith(deviceKeys.openBiometric(uid(), authenticatedCipher))
    }

    // ---- Settings (only while unlocked) ----

    /** For the biometric prompt that turns on fingerprint unlock. */
    fun biometricEnableCipher(): Cipher = deviceKeys.biometricEncryptCipher()

    fun finishBiometricEnable(authenticatedCipher: Cipher) {
        val dataKey = requireDataKey()
        deviceKeys.saveBiometric(uid(), authenticatedCipher, dataKey)
        _biometricEnabled.value = true
    }

    fun disableBiometric() {
        val dataKey = requireDataKey()
        deviceKeys.savePlain(uid(), dataKey) // also removes the biometric copy
        _biometricEnabled.value = false
    }

    /** Re-wraps the same data key under a new passphrase. No entry is rewritten. */
    suspend fun changePassphrase(current: String, new: String) {
        val uid = uid()
        val dataKey = withContext(Dispatchers.Default) {
            JournalCrypto.unwrapDataKey(readWrapped(uid, "passphrase"), current.toCharArray(), passphraseAad(uid))
        }
        val byPassphrase = withContext(Dispatchers.Default) { wrapForPassphrase(uid, dataKey, new) }
        keysDoc(uid).update("passphrase", byPassphrase.toMap()).await()
    }

    /** Replaces the recovery code (the old one stops working) and returns the new one. */
    suspend fun newRecoveryCode(currentPassphrase: String): String {
        val uid = uid()
        val dataKey = withContext(Dispatchers.Default) {
            JournalCrypto.unwrapDataKey(
                readWrapped(uid, "passphrase"),
                currentPassphrase.toCharArray(),
                passphraseAad(uid),
            )
        }
        val code = RecoveryCode.generate()
        val byRecovery = withContext(Dispatchers.Default) { wrapForRecovery(uid, dataKey, code) }
        keysDoc(uid).update("recovery", byRecovery.toMap()).await()
        return code
    }

    /**
     * Locks the journal. With biometrics on, the phone keeps its copy and a
     * fingerprint opens it again; otherwise the copy is removed and the
     * passphrase is needed.
     */
    fun lock() {
        val uid = auth.currentUser.value?.uid ?: return
        if (!deviceKeys.hasBiometric(uid)) deviceKeys.forget(uid)
        lockInMemory()
        _state.value = VaultState.Locked(biometricReady = deviceKeys.hasBiometric(uid))
    }

    /** Used after the app has been in the background a while. Only biometric mode re-locks. */
    fun lockIfBiometric() {
        if (_biometricEnabled.value && _state.value == VaultState.Unlocked) lock()
    }

    /**
     * The way out when both passphrase and recovery code are lost: deletes every
     * entry, note, insight and the key itself, then starts setup again.
     */
    suspend fun deleteEverything() {
        val uid = uid()
        val user = db.collection("users").document(uid)
        for (name in listOf("entries", "quickNotes", "insights")) {
            val docs = user.collection(name).get(Source.SERVER).await().documents
            docs.chunked(400).forEach { chunk ->
                db.batch().apply { chunk.forEach { delete(it.reference) } }.commit().await()
            }
        }
        keysDoc(uid).delete().await()
        deviceKeys.forget(uid)
        lockInMemory()
        _biometricEnabled.value = false
        _state.value = VaultState.NeedsSetup
    }

    // ---- Internals ----

    private fun unlockWith(dataKey: ByteArray) {
        _cipher.value = ContentCipher(dataKey)
        _state.value = VaultState.Unlocked
    }

    private fun lockInMemory() {
        // Not zero-filled: a write already in flight may still be sealing with it,
        // and sealing with a wiped key would make that document unreadable.
        _cipher.value = null
    }

    private fun requireDataKey(): ByteArray = _cipher.value?.dataKeyCopy() ?: error("Journal is locked")

    /** Keeps the key on this phone in whichever mode the phone is in. */
    private fun rememberOnDevice(uid: String, dataKey: ByteArray) {
        // In biometric mode the existing biometric copy stays valid; nothing to do.
        if (!deviceKeys.hasBiometric(uid)) deviceKeys.savePlain(uid, dataKey)
    }

    private suspend fun readWrapped(uid: String, field: String): WrappedKey {
        val snapshot = keysDoc(uid).get().await()
        return snapshot.wrappedKey(field) ?: error("This account has no $field key")
    }

    private fun wrapForPassphrase(uid: String, dataKey: ByteArray, passphrase: String) =
        JournalCrypto.wrapDataKey(dataKey, passphrase.toCharArray(), passphraseAad(uid), JournalCrypto.PASSPHRASE_ITERATIONS)

    private fun wrapForRecovery(uid: String, dataKey: ByteArray, code: String) =
        JournalCrypto.wrapDataKey(
            dataKey,
            RecoveryCode.normalize(code)!!.toCharArray(),
            recoveryAad(uid),
            JournalCrypto.RECOVERY_ITERATIONS,
        )

    private fun passphraseAad(uid: String) = "keys/passphrase/$uid"
    private fun recoveryAad(uid: String) = "keys/recovery/$uid"

    private fun WrappedKey.toMap() = mapOf("salt" to salt, "iterations" to iterations, "sealed" to sealed)

    private fun DocumentSnapshot.wrappedKey(field: String): WrappedKey? {
        val map = get(field) as? Map<*, *> ?: return null
        return WrappedKey(
            salt = map["salt"] as? String ?: return null,
            iterations = (map["iterations"] as? Number)?.toInt() ?: return null,
            sealed = map["sealed"] as? String ?: return null,
        )
    }

    private companion object {
        const val TAG = "KeyManager"
    }
}

/**
 * The unlocked data key, as the repositories see it: seal text for a document,
 * open it again. [aad] is the document's path, e.g. "entries/abc123".
 */
class ContentCipher(dataKey: ByteArray) {
    private val key = dataKey.copyOf()

    fun seal(plaintext: String, aad: String): String =
        JournalCrypto.seal(key, plaintext.toByteArray(Charsets.UTF_8), aad)

    /** Throws [CryptoException] if the data can't be decrypted. */
    fun open(sealed: String, aad: String): String =
        String(JournalCrypto.open(key, sealed, aad), Charsets.UTF_8)

    internal fun dataKeyCopy(): ByteArray = key.copyOf()
}
