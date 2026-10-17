package com.codespace.ide.chat

import android.content.Context

/**
 * P4 (BATCH3): OPT-IN 402 key failover — OFF by default.
 *
 * HTTP 402 means the provider ACCOUNT behind the key is out of credits.
 * Credits are per ACCOUNT (e.g. Hugging Face monthly included credits), so
 * switching keys only helps when the pool holds keys from DIFFERENT accounts
 * — the Settings toggle says exactly that. When enabled, ChatKeyFailover
 * makes ONE pass through the pool on 402 (no same-key retries, no loops:
 * a 402 never clears by retrying the same key) and the chat shows
 * "Switched to key N" when it moves on.
 *
 * The flag is cached in memory (read by the failover engine on every request,
 * no Context needed there) and initialized at app start alongside
 * ChatKeyPool.init; the Settings switch updates both atomically.
 */
object Chat402FailoverSetting {
    private const val PREFS = "copilot_chat"
    private const val KEY = "failover_402_enabled"

    @Volatile
    private var enabled: Boolean = false

    /** Read by ChatKeyFailover on every 402. Default false until init. */
    fun isEnabled(): Boolean = enabled

    fun init(context: Context) {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }

    /** JVM tests only: the engine reads the in-memory flag without a Context. */
    fun setEnabledForTest(value: Boolean) {
        enabled = value
    }
}
