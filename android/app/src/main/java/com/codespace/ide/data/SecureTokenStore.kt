package com.codespace.ide.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.security.MessageDigest

/**
 * Encrypted, Keystore-backed storage for tokens, role, BYOK AI API keys,
 * and app-level security preferences (biometric lock).
 * Access tokens are kept in memory only.
 */
@Singleton
class SecureTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "codespace_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) = prefs.edit().putString(KEY_REFRESH, value).apply()

    /** "owner" | "user" — persisted across restarts */
    var userRole: String
        get() = prefs.getString(KEY_ROLE, "user") ?: "user"
        set(value) = prefs.edit().putString(KEY_ROLE, value).apply()

    val isOwner: Boolean get() = userRole == "owner"

    /** When true, app shows a PIN/biometric prompt on every launch */
    var biometricLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_LOCK, value).apply()

    /** SHA-256 hash of the user's PIN (null = no PIN registered) */
    var pinHash: String?
        get() = prefs.getString(KEY_PIN_HASH, null)
        set(value) {
            if (value != null) prefs.edit().putString(KEY_PIN_HASH, value).apply()
            else prefs.edit().remove(KEY_PIN_HASH).apply()
        }

    /** Whether the user has registered a PIN */
    val hasPinRegistered: Boolean get() = pinHash != null

    /** Verify a PIN against the stored hash */
    fun verifyPin(pin: String): Boolean {
        val stored = pinHash ?: return false
        val inputHash = this.hashPin(pin)
        return stored == inputHash
    }

    /** Hash a PIN with SHA-256 + salt for secure storage */
    fun hashPin(pin: String): String {
        val salt = "codespace_ide_2026"  // app-specific salt
        val digest = MessageDigest.getInstance("SHA-256")
        val input = (salt + pin).toByteArray(Charsets.UTF_8)
        return digest.digest(input).joinToString("") { "%02x".format(it) }
    }

    fun aiKey(provider: String): String? = prefs.getString("ai_$provider", null)
    fun setAiKey(provider: String, key: String?) =
        prefs.edit().putString("ai_$provider", key).apply()

    /** GitHub OAuth device-flow access token — used both for Source Control (git push/pull
     *  auth) and to identify who's signed in. Null when signed out. */
    var githubToken: String?
        get() = prefs.getString(KEY_GITHUB_TOKEN, null)
        set(value) = if (value != null) prefs.edit().putString(KEY_GITHUB_TOKEN, value).apply()
                     else prefs.edit().remove(KEY_GITHUB_TOKEN).apply()

    var githubUsername: String?
        get() = prefs.getString(KEY_GITHUB_USER, null)
        set(value) = if (value != null) prefs.edit().putString(KEY_GITHUB_USER, value).apply()
                     else prefs.edit().remove(KEY_GITHUB_USER).apply()

    /** Last JWT access token — stored so HomeScreen can warm-start without re-auth */
    var lastAccessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(value) = if (value != null) prefs.edit().putString(KEY_ACCESS, value).apply()
                     else prefs.edit().remove(KEY_ACCESS).apply()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_REFRESH        = "refresh_token"
        const val KEY_ROLE           = "user_role"
        const val KEY_BIOMETRIC_LOCK = "biometric_lock_enabled"
        const val KEY_ACCESS         = "last_access_token"
        const val KEY_GITHUB_TOKEN   = "github_oauth_token"
        const val KEY_GITHUB_USER    = "github_username"
        const val KEY_PIN_HASH       = "pin_hash"
    }
}
