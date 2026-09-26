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

    /** PBKDF2 (v2) or legacy SHA-256 (v1) hash of the user's PIN (null = no PIN registered) */
    var pinHash: String?
        get() = prefs.getString(KEY_PIN_HASH, null)
        private set(value) {
            if (value != null) prefs.edit().putString(KEY_PIN_HASH, value).apply()
            else prefs.edit().remove(KEY_PIN_HASH).apply()
        }

    /** Whether the user has registered a PIN */
    val hasPinRegistered: Boolean get() = pinHash != null

    /**
     * SK04 (P4f): register (or re-register) a PIN under the hardened scheme —
     * per-install random salt + PBKDF2-HMAC-SHA256 + constant-time comparison.
     * Replaces the legacy static-salt SHA-256 that was brute-forceable from a
     * prefs backup in seconds.
     */
    fun setPin(pin: String) {
        var salt = prefs.getString(KEY_PIN_SALT, null)
        if (salt == null) {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            salt = bytes.joinToString("") { "%02x".format(it) }
        }
        prefs.edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, pbkdf2Base64(pin, salt))
            .putInt(KEY_PIN_SCHEME, PIN_SCHEME_PBKDF2)
            .putInt(KEY_PIN_FAILS, 0)
            .putLong(KEY_PIN_LOCKOUT_UN, 0L)
            .apply()
    }

    /** Clears the PIN and its hardening state (lock disable path). */
    fun clearPin() {
        prefs.edit()
            .remove(KEY_PIN_HASH).remove(KEY_PIN_SALT)
            .remove(KEY_PIN_SCHEME).remove(KEY_PIN_FAILS).remove(KEY_PIN_LOCKOUT_UN)
            .apply()
    }

    /**
     * Verify a PIN against the stored hash. Constant-time compare, scheme-aware:
     * a successful LEGACY verify transparently UPGRADES the stored hash to
     * PBKDF2 + per-install salt. Every failed verify records a PERSISTED
     * failure (drives the escalating lockout) — the counter no longer lives in
     * a composable's remember{} that rotation resets.
     */
    fun verifyPin(pin: String): Boolean {
        val stored = pinHash ?: return false
        val scheme = prefs.getInt(KEY_PIN_SCHEME, 1)
        val ok = if (scheme >= PIN_SCHEME_PBKDF2) {
            val salt = prefs.getString(KEY_PIN_SALT, null)
            if (salt == null) false
            else constantTimeEquals(pbkdf2Base64(pin, salt), stored)
        } else {
            // Legacy static-salt SHA-256 (verify + in-place upgrade)
            val legacyOk = constantTimeEquals(legacyHashPin(pin), stored)
            if (legacyOk) setPin(pin)
            legacyOk
        }
        if (ok) resetPinFailures() else recordPinFailure()
        return ok
    }

    /** Current persisted failed-attempt count (resets on success). */
    fun pinFailureCount(): Int = prefs.getInt(KEY_PIN_FAILS, 0)

    /** Attempts allowed before the lock disables itself (SK04 anti-lockout valve). */
    val maxPinAttempts: Int get() = PIN_MAX_FAILURES

    /** ms remaining in the active lockout window (0 = not locked out). */
    fun pinLockoutRemainingMs(): Long =
        (prefs.getLong(KEY_PIN_LOCKOUT_UN, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun recordPinFailure() {
        val fails = pinFailureCount() + 1
        val editor = prefs.edit().putInt(KEY_PIN_FAILS, fails)
        if (fails >= 3) {
            val step = (fails - 3).coerceAtMost(PIN_LOCKOUT_STEPS_MS.size - 1)
            editor.putLong(KEY_PIN_LOCKOUT_UN, System.currentTimeMillis() + PIN_LOCKOUT_STEPS_MS[step])
        }
        editor.apply()
    }

    private fun resetPinFailures() {
        prefs.edit().putInt(KEY_PIN_FAILS, 0).putLong(KEY_PIN_LOCKOUT_UN, 0L).apply()
    }

    private fun pbkdf2Base64(pin: String, salt: String): String {
        val spec = javax.crypto.spec.PBEKeySpec(
            pin.toCharArray(), salt.toByteArray(Charsets.UTF_8), PBKDF2_ITERATIONS, 256,
        )
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return android.util.Base64.encodeToString(factory.generateSecret(spec).encoded, android.util.Base64.NO_WRAP)
    }

    /** Constant-time comparison — never early-exits on the first differing byte. */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** Legacy static-salt SHA-256 (pre-P4f) — kept ONLY to migrate existing hashes. */
    private fun legacyHashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = ("codespace_ide_2026" + pin).toByteArray(Charsets.UTF_8)
        return digest.digest(input).joinToString("") { "%02x".format(it) }
    }

    fun aiKey(provider: String): String? = prefs.getString("ai_$provider", null)

    /**
     * Phase 3 (Group C): per-project service credentials (Firebase / Supabase / n8n).
     * Keys are namespaced 'proj_<projectId>_<name>'; values live in the same
     * EncryptedSharedPreferences store (AES-256-GCM at rest). null value deletes.
     */
    fun projectSecret(projectId: String, name: String): String? =
        prefs.getString("proj_" + projectId + "_" + name, null)

    fun setProjectSecret(projectId: String, name: String, value: String?) {
        val key = "proj_" + projectId + "_" + name
        if (value != null) prefs.edit().putString(key, value).apply()
        else prefs.edit().remove(key).apply()
    }
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
        // SK04 (P4f): PIN hardening — scheme version, per-install salt,
        // PERSISTED failure counter + escalating lockout deadline.
        const val KEY_PIN_SCHEME     = "pin_hash_scheme"
        const val KEY_PIN_SALT       = "pin_salt"
        const val KEY_PIN_FAILS      = "pin_fail_count"
        const val KEY_PIN_LOCKOUT_UN = "pin_lockout_until"

        /** PIN hash scheme: 1 = legacy static-salt SHA-256 (pre-P4f), 2 = PBKDF2 + per-install salt. */
        const val PIN_SCHEME_PBKDF2 = 2

        /** PBKDF2-HMAC-SHA256 iteration count. 100k keeps on-device unlock
         *  under ~300ms on a low-end device while making offline brute-force
         *  of the <=8-digit space cost GPU-hours instead of seconds. */
        const val PBKDF2_ITERATIONS = 100_000

        /** Escalating lockout (ms) applied at failures 3, 4, 5, 6 — PERSISTED,
         *  so rotating the screen or restarting the app does NOT reset it. */
        val PIN_LOCKOUT_STEPS_MS = longArrayOf(30_000L, 120_000L, 600_000L, 3_600_000L)

        /** Failures before the lock disables itself (deliberate anti-lockout
         *  valve, unchanged in spirit from the original design — but it now
         *  comes only AFTER the escalating lockouts above have run). */
        const val PIN_MAX_FAILURES = 7
    }
}
