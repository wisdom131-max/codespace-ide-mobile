package com.codespace.ide.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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

    /** When true, app shows a biometric / device-credential prompt on every launch */
    var biometricLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_LOCK, value).apply()

    fun aiKey(provider: String): String? = prefs.getString("ai_$provider", null)
    fun setAiKey(provider: String, key: String?) =
        prefs.edit().putString("ai_$provider", key).apply()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_REFRESH        = "refresh_token"
        const val KEY_ROLE           = "user_role"
        const val KEY_BIOMETRIC_LOCK = "biometric_lock_enabled"
    }
}
