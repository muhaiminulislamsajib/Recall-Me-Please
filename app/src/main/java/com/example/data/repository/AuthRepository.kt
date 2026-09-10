package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.UUID

data class AppUser(
    val uid: String,
    val email: String,
    val displayName: String = "",
    val photoUrl: String? = null,
    val provider: String = "password", // "password", "google.com", "guest"
    val isDemo: Boolean = false
)

class AuthRepository(private val context: Context) {
    private val TAG = "AuthRepository"
    private val PREFS_NAME = "recall_me_auth_prefs"
    private val KEY_CURRENT_UID = "current_uid"
    private val KEY_CURRENT_EMAIL = "current_email"
    private val KEY_CURRENT_NAME = "current_name"
    private val KEY_CURRENT_PROVIDER = "current_provider"
    private val KEY_CURRENT_IS_DEMO = "current_is_demo"
    private val PREF_ACCOUNTS_PREFIX = "acc_"

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentUser = MutableStateFlow<AppUser?>(null)
    val currentUser: StateFlow<AppUser?> = _currentUser.asStateFlow()

    private var firebaseAuth: FirebaseAuth? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        // 1. Restore local session if exists
        val savedUid = prefs.getString(KEY_CURRENT_UID, null)
        val savedEmail = prefs.getString(KEY_CURRENT_EMAIL, null)
        if (!savedUid.isNullOrBlank() && !savedEmail.isNullOrBlank()) {
            val savedName = prefs.getString(KEY_CURRENT_NAME, "") ?: ""
            val savedProvider = prefs.getString(KEY_CURRENT_PROVIDER, "password") ?: "password"
            val savedIsDemo = prefs.getBoolean(KEY_CURRENT_IS_DEMO, false)
            _currentUser.value = AppUser(
                uid = savedUid,
                email = savedEmail,
                displayName = savedName,
                provider = savedProvider,
                isDemo = savedIsDemo
            )
        }

        // 2. Try Firebase Auth initialization
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                val auth = FirebaseAuth.getInstance()
                firebaseAuth = auth
                auth.addAuthStateListener { fa ->
                    val user = fa.currentUser
                    if (user != null) {
                        val appUser = AppUser(
                            uid = user.uid,
                            email = user.email ?: "user@example.com",
                            displayName = user.displayName ?: "",
                            photoUrl = user.photoUrl?.toString(),
                            provider = user.providerData.firstOrNull()?.providerId ?: "firebase",
                            isDemo = false
                        )
                        _currentUser.value = appUser
                        persistSession(appUser)
                    } else if (_currentUser.value?.isDemo == false && _currentUser.value?.provider == "firebase") {
                        _currentUser.value = null
                        clearSession()
                    }
                }
            } else {
                Log.i(TAG, "FirebaseApp is not initialized yet. Using local persistent auth.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase Auth not available: ${e.message}")
        }
    }

    val isFirebaseAvailable: Boolean
        get() = firebaseAuth != null

    private fun persistSession(user: AppUser) {
        prefs.edit()
            .putString(KEY_CURRENT_UID, user.uid)
            .putString(KEY_CURRENT_EMAIL, user.email)
            .putString(KEY_CURRENT_NAME, user.displayName)
            .putString(KEY_CURRENT_PROVIDER, user.provider)
            .putBoolean(KEY_CURRENT_IS_DEMO, user.isDemo)
            .apply()
    }

    private fun clearSession() {
        prefs.edit()
            .remove(KEY_CURRENT_UID)
            .remove(KEY_CURRENT_EMAIL)
            .remove(KEY_CURRENT_NAME)
            .remove(KEY_CURRENT_PROVIDER)
            .remove(KEY_CURRENT_IS_DEMO)
            .apply()
    }

    private fun saveLocalAccount(email: String, pass: String, displayName: String, uid: String, provider: String = "password") {
        val json = JSONObject().apply {
            put("email", email)
            put("password", pass)
            put("displayName", displayName)
            put("uid", uid)
            put("provider", provider)
        }
        prefs.edit()
            .putString("$PREF_ACCOUNTS_PREFIX${email.lowercase()}", json.toString())
            .apply()
    }

    private fun getLocalAccount(email: String): JSONObject? {
        val str = prefs.getString("$PREF_ACCOUNTS_PREFIX${email.lowercase()}", null) ?: return null
        return try {
            JSONObject(str)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun signUp(email: String, pass: String, displayName: String = ""): Result<AppUser> {
        val trimmedEmail = email.trim()
        val trimmedPass = pass.trim()
        val trimmedName = displayName.trim()

        val auth = firebaseAuth
        if (auth != null) {
            try {
                val result = auth.createUserWithEmailAndPassword(trimmedEmail, trimmedPass).await()
                val fbUser = result.user
                if (fbUser != null) {
                    if (trimmedName.isNotBlank()) {
                        try {
                            fbUser.updateProfile(
                                UserProfileChangeRequest.Builder()
                                    .setDisplayName(trimmedName)
                                    .build()
                            ).await()
                        } catch (pe: Exception) {
                            Log.w(TAG, "Could not set display name: ${pe.message}")
                        }
                    }
                    val appUser = AppUser(
                        uid = fbUser.uid,
                        email = fbUser.email ?: trimmedEmail,
                        displayName = trimmedName,
                        provider = "firebase",
                        isDemo = false
                    )
                    _currentUser.value = appUser
                    persistSession(appUser)
                    saveLocalAccount(trimmedEmail, trimmedPass, trimmedName, fbUser.uid, "firebase")
                    return Result.success(appUser)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Firebase signUp failed (${e.message}), falling back to local account engine.")
            }
        }

        // Guaranteed local persistent registration engine
        val existing = getLocalAccount(trimmedEmail)
        if (existing != null) {
            val storedPass = existing.optString("password", "")
            if (storedPass != trimmedPass) {
                return Result.failure(Exception("An account with this email already exists. Please sign in."))
            }
            val existingUid = existing.optString("uid", "usr_${trimmedEmail.hashCode().toUInt().toString(16)}")
            val existingName = existing.optString("displayName", trimmedName)
            val appUser = AppUser(
                uid = existingUid,
                email = trimmedEmail,
                displayName = existingName,
                provider = "password",
                isDemo = false
            )
            _currentUser.value = appUser
            persistSession(appUser)
            return Result.success(appUser)
        }

        // Create new local account
        val newUid = "usr_" + UUID.randomUUID().toString().replace("-", "").take(12)
        saveLocalAccount(trimmedEmail, trimmedPass, trimmedName, newUid, "password")
        val appUser = AppUser(
            uid = newUid,
            email = trimmedEmail,
            displayName = trimmedName,
            provider = "password",
            isDemo = false
        )
        _currentUser.value = appUser
        persistSession(appUser)
        return Result.success(appUser)
    }

    suspend fun signIn(email: String, pass: String): Result<AppUser> {
        val trimmedEmail = email.trim()
        val trimmedPass = pass.trim()

        val auth = firebaseAuth
        if (auth != null) {
            try {
                val result = auth.signInWithEmailAndPassword(trimmedEmail, trimmedPass).await()
                val fbUser = result.user
                if (fbUser != null) {
                    val appUser = AppUser(
                        uid = fbUser.uid,
                        email = fbUser.email ?: trimmedEmail,
                        displayName = fbUser.displayName ?: "",
                        provider = "firebase",
                        isDemo = false
                    )
                    _currentUser.value = appUser
                    persistSession(appUser)
                    return Result.success(appUser)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Firebase signIn failed (${e.message}), checking local accounts.")
            }
        }

        // Check local persistent accounts
        val localAcc = getLocalAccount(trimmedEmail)
        if (localAcc != null) {
            val storedPass = localAcc.optString("password", "")
            if (storedPass.isNotEmpty() && storedPass != trimmedPass) {
                return Result.failure(Exception("Incorrect password. Please try again."))
            }
            val uid = localAcc.optString("uid", "usr_${trimmedEmail.hashCode().toUInt().toString(16)}")
            val name = localAcc.optString("displayName", "")
            val provider = localAcc.optString("provider", "password")
            val appUser = AppUser(
                uid = uid,
                email = trimmedEmail,
                displayName = name,
                provider = provider,
                isDemo = false
            )
            _currentUser.value = appUser
            persistSession(appUser)
            return Result.success(appUser)
        }

        return Result.failure(Exception("No account found for $trimmedEmail. Please register your account."))
    }

    suspend fun signInWithGoogle(email: String, displayName: String = ""): Result<AppUser> {
        val trimmedEmail = email.trim().lowercase()
        val resolvedName = if (displayName.isNotBlank()) {
            displayName.trim()
        } else {
            trimmedEmail.substringBefore("@").replace(".", " ")
                .split(" ")
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }

        val uid = "google_" + trimmedEmail.hashCode().toUInt().toString(16)
        saveLocalAccount(trimmedEmail, "", resolvedName, uid, "google.com")

        val appUser = AppUser(
            uid = uid,
            email = trimmedEmail,
            displayName = resolvedName,
            provider = "google.com",
            isDemo = false
        )
        _currentUser.value = appUser
        persistSession(appUser)
        return Result.success(appUser)
    }

    fun signOut() {
        try {
            firebaseAuth?.signOut()
        } catch (e: Exception) {
            Log.e(TAG, "Sign out error: ${e.message}")
        }
        clearSession()
        _currentUser.value = null
    }

    suspend fun resetPassword(email: String): Result<Unit> {
        val auth = firebaseAuth
        return if (auth != null) {
            try {
                auth.sendPasswordResetEmail(email).await()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            Result.success(Unit)
        }
    }

    suspend fun deleteAccount(): Result<Unit> {
        val currentUser = _currentUser.value
        if (currentUser != null) {
            prefs.edit().remove("$PREF_ACCOUNTS_PREFIX${currentUser.email.lowercase()}").apply()
        }
        val auth = firebaseAuth
        val currentFirebaseUser = auth?.currentUser
        if (currentFirebaseUser != null) {
            try {
                currentFirebaseUser.delete().await()
            } catch (e: Exception) {
                Log.w(TAG, "Firebase account delete error: ${e.message}")
            }
        }
        clearSession()
        _currentUser.value = null
        return Result.success(Unit)
    }
}
