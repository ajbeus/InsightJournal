package com.adam.insightjournal.data

import com.adam.insightjournal.crypto.ContentCipher
import com.adam.insightjournal.crypto.CryptoException
import com.adam.insightjournal.crypto.KeyManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * How encrypted documents are laid out in Firestore, shared by every repository.
 *
 * Each document keeps a few readable fields that queries need (dates, links
 * between notes and entries) and puts everything personal into one field,
 * [ENC_FIELD]: a JSON object sealed with the user's data key. The document's
 * path is the AAD, so a sealed blob only opens in the document it was written for.
 */
internal const val ENC_FIELD = "enc"

/** Seals [payload] for the document at [path] (relative to the user, e.g. "entries/abc"). */
internal fun ContentCipher.sealJson(payload: JSONObject, path: String): String = seal(payload.toString(), path)

/** Opens a sealed blob, or returns null if it can't be decrypted or parsed. */
internal fun ContentCipher.openJson(sealed: String, path: String): JSONObject? = try {
    JSONObject(open(sealed, path))
} catch (e: CryptoException) {
    null
} catch (e: JSONException) {
    null
}

/** org.json returns "null" text for missing strings unless asked like this. */
internal fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) getString(name) else null

/** An embedding as Base64 of little-endian floats: about a third of the size of a JSON number list. */
internal fun List<Float>.packFloats(): String {
    val buffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    forEach(buffer::putFloat)
    return Base64.getEncoder().encodeToString(buffer.array())
}

internal fun String.unpackFloats(): List<Float> {
    val buffer = ByteBuffer.wrap(Base64.getDecoder().decode(this)).order(ByteOrder.LITTLE_ENDIAN)
    return List(buffer.remaining() / 4) { buffer.float }
}

/**
 * Runs [whileUnlocked] for the signed-in user while the journal is unlocked,
 * and emits [whenLocked] otherwise (signed out, or no key on this phone yet).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> AuthRepository.whileUnlocked(
    keys: KeyManager,
    whenLocked: T,
    whileUnlocked: (uid: String, cipher: ContentCipher) -> Flow<T>,
): Flow<T> =
    combine(currentUser, keys.cipher) { user, cipher -> user?.uid to cipher }
        .flatMapLatest { (uid, cipher) ->
            if (uid == null || cipher == null) flowOf(whenLocked) else whileUnlocked(uid, cipher)
        }
