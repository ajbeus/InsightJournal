package com.adam.insightjournal.data

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

data class AppUser(val uid: String, val email: String?)

interface AuthRepository {
    /** The signed-in user, or null when signed out. */
    val currentUser: StateFlow<AppUser?>
    suspend fun signIn(email: String, password: String)
    suspend fun signUp(email: String, password: String)
    /** Emails a link for choosing a new account password. Doesn't touch the journal passphrase. */
    suspend fun sendPasswordReset(email: String)
    fun signOut()
}

class FirebaseAuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : AuthRepository {

    private val _currentUser = MutableStateFlow(auth.currentUser?.let { AppUser(it.uid, it.email) })
    override val currentUser: StateFlow<AppUser?> = _currentUser.asStateFlow()

    init {
        // Lives as long as the app process, so the listener is never removed.
        auth.addAuthStateListener { a ->
            _currentUser.value = a.currentUser?.let { AppUser(it.uid, it.email) }
        }
    }

    override suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password).await()
    }

    override suspend fun signUp(email: String, password: String) {
        auth.createUserWithEmailAndPassword(email, password).await()
    }

    override suspend fun sendPasswordReset(email: String) {
        auth.sendPasswordResetEmail(email).await()
    }

    override fun signOut() = auth.signOut()
}

/** Used when Firebase isn't configured: one permanent local user, no sign-in screen. */
class LocalAuthRepository : AuthRepository {
    override val currentUser: StateFlow<AppUser?> = MutableStateFlow(AppUser("local", null))
    override suspend fun signIn(email: String, password: String) = Unit
    override suspend fun signUp(email: String, password: String) = Unit
    override suspend fun sendPasswordReset(email: String) = Unit
    override fun signOut() = Unit
}
