package com.codespace.ide.chat

import android.content.Context
import android.content.SharedPreferences
import com.codespace.ide.diagnostics.AppOutputLog
import org.json.JSONArray

/**
 * G-C C-1b (advisor-approved 2026-10-08): a short per-provider LIVE model cache
 * window so the panel-mount fetch does not hit EVERY provider's /models on
 * EVERY panel open.
 *
 *  - Providers fetched within WINDOW_MS (default 10 min) reuse the cached list
 *    — mount fetch skips the network call for them and logs
 *    "[model-cache] window: <provider>, age <t>" so the reuse is observable.
 *  - The explicit per-group Refetch (C-1) BYPASSES the window: its success
 *    overwrites this cache, so one tap always forces real data.
 *  - Invalidation: endpoint base-URL edits and endpoint DELETES drop the cache
 *    ("cache dropped: <provider>" log) — models cached from an old URL must
 *    never serve; manual-model edits need no drop because customs cache only
 *    the LIVE portion and manual entries are merged at reuse time.
 *  - Applies UNIFORMLY to built-ins and customs (advisor resolution: (a) +
 *    C-1b — built-ins get the same data savings in phase C).
 *
 * Storage mirrors CustomEndpointStore: plain SharedPreferences; this is
 * transient model data, not credentials.
 */
object ProviderModelCache {
    @Volatile private var prefs: SharedPreferences? = null

    /** Advisor default: 10 minutes. */
    const val WINDOW_MS = 10 * 60 * 1000L

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("provider_model_cache", Context.MODE_PRIVATE)
    }

    /**
     * The cached list when it is FRESH (fetched within the window), else null.
     * Empty and corrupt entries read as a miss. For custom endpoints the stored
     * list is the LIVE portion only (manual models merge at reuse time).
     */
    fun freshModels(providerId: String, now: Long = System.currentTimeMillis()): List<String>? {
        val p = prefs ?: return null
        val ts = p.getLong("$providerId.fetchedAt", 0L)
        if (ts <= 0L || now - ts >= WINDOW_MS) return null
        val arr = try {
            JSONArray(p.getString("$providerId.models", "[]") ?: "[]")
        } catch (_: Exception) { return null }
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val m = arr.optString(i)
            if (m.isNotEmpty()) out.add(m)
        }
        if (out.isEmpty()) return null
        val ageSec = (now - ts) / 1000
        AppOutputLog.log(
            "[model-cache] window: " + providerId + ", age " + ageSec + "s (" + out.size + " models) \u2014 reusing, Refetch bypasses",
            "chat",
        )
        return out
    }

    /** Stamp the cache with the fresh [models] (live portion for customs). */
    fun put(providerId: String, models: List<String>, now: Long = System.currentTimeMillis()) {
        val p = prefs ?: return
        val arr = JSONArray()
        models.forEach { arr.put(it) }
        p.edit()
            .putLong("$providerId.fetchedAt", now)
            .putString("$providerId.models", arr.toString())
            .apply()
    }

    /** Invalidate — endpoint URL edited or endpoint deleted; never serve stale models. */
    fun drop(providerId: String) {
        val p = prefs ?: return
        p.edit()
            .remove("$providerId.fetchedAt")
            .remove("$providerId.models")
            .apply()
        AppOutputLog.log("[model-cache] cache dropped: " + providerId, "chat")
    }
}
