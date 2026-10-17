package com.codespace.ide.chat

import android.content.SharedPreferences

/**
 * BATCH4-P3/P4 (S01 family): prefs seam for the chat stores.
 *
 * Why a seam at all: ChatPlanStore and ChatInputHistory persist through
 * SharedPreferences, which has the two silent-failure modes the S01 audit
 * flagged:
 *   1. apply() is fire-and-forget — a failed disk write reports nothing, so
 *      the UI says "saved" while nothing reached storage (the
 *      CustomEndpointStore "saved lie" class, which the locked MK-B plan will
 *      rebuild properly for endpoints; this seam is for the plan and input
 *      history stores).
 *   2. a corrupt payload parses to an empty store with no trace — the user's
 *      data vanishes silently on the next launch.
 *
 * The seam exists for two reasons: (a) writes go through commit() so success
 * is a typed Boolean, not a silent fire-and-forget; (b) JVM tests get a fake
 * backend so the quarantine and rollback behavior is unit-testable without
 * Android.
 */
internal interface ChatPrefsBackend {
    /** null = key absent. */
    fun getString(key: String): String?

    /** Synchronous, typed write: true = the value reached storage. */
    fun putString(key: String, value: String): Boolean

    /** Quarantine housekeeping: every string key starting with [prefix], with its raw value. */
    fun allWithPrefix(prefix: String): Map<String, String>
}

/** Production backend: the app's SharedPreferences, commit()-based. */
internal class AndroidChatPrefsBackend(private val prefs: SharedPreferences) : ChatPrefsBackend {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String): Boolean =
        prefs.edit().putString(key, value).commit()
    override fun allWithPrefix(prefix: String): Map<String, String> =
        prefs.all.entries
            .filter { it.key.startsWith(prefix) && it.value is String }
            .associate { it.key to (it.value as String) }
}
