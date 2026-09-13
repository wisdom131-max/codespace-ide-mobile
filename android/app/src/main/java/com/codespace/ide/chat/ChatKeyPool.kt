package com.codespace.ide.chat

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * MULTI-KEY (2026-09-13, Wisdom-approved): unlimited API keys per provider.
 *
 * Storage design (numbered slots — confirmed to scale to arbitrary N):
 *  - Key VALUES live in SecureTokenStore under "ai_<ID>", "ai_<ID>_2",
 *    "ai_<ID>_3", ... — the store already supports arbitrary names and has no
 *    slot limit, so allocation is "first free number" and grows forever.
 *  - ORDER lives here in plain prefs as a JSON array ("chat_key_pool" file,
 *    "index_<id>" keys). Slot 1 is always index[0] — zero-migration: existing
 *    single-key users keep their key exactly where it has always been.
 *  - LABELS live here as JSON "labels" (slot -> user label); non-secret.
 *
 * Selection is FULLY AUTOMATIC (ChatKeyFailover): keys are tried in list
 * order; 401/403 cools a key down for 10 minutes and moves to the next;
 * 429 backs off and retries the SAME key first. No manual "active" pick.
 */
object ChatKeyPool {
    @Volatile private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("chat_key_pool", Context.MODE_PRIVATE)
    }

    private fun idxName(providerId: String) = "index_" + providerId

    /** Secure-store slot suffix: "OPENAI" (slot 1), "OPENAI_2", "OPENAI_3", ... */
    fun slotSuffix(providerId: String, n: Int): String =
        providerId.uppercase() + (if (n <= 1) "" else "_" + n)

    /**
     * Ordered slot suffixes. Slot 1 is always first (even when empty — the
     * legacy location); the stored index keeps the rest in add order.
     */
    fun slots(providerId: String): List<String> {
        val p = prefs
        val list = mutableListOf(slotSuffix(providerId, 1))
        if (p != null) {
            val raw = try { p.getString(idxName(providerId), null) } catch (_: Exception) { null }
            if (raw != null) {
                try {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i)
                        if (s.isNotEmpty() && s !in list) list.add(s)
                    }
                } catch (_: Exception) { }
            }
        }
        return list
    }

    private fun saveIndex(providerId: String, slots: List<String>) {
        val p = prefs ?: return
        val arr = JSONArray()
        slots.forEach { arr.put(it) }
        try { p.edit().putString(idxName(providerId), arr.toString()).apply() } catch (_: Exception) { }
    }

    /** All non-empty keys of a provider: (slotSuffix, key) pairs.
     *  Order = MANUAL ACTIVE slot first (user's pick), then the rest in slot order —
     *  this single ordering drives both halves of the policy: the active key is the
     *  one tried first (manual selection wins while it works), and on a 401/403 the
     *  failover engine walks the remaining pairs automatically. */
    fun keys(tokenStore: com.codespace.ide.data.SecureTokenStore?, providerId: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (suf in slots(providerId)) {
            val k = try { tokenStore?.aiKey(suf) } catch (_: Exception) { null }
            if (!k.isNullOrBlank()) out.add(suf to k)
        }
        val act = activeSuffix(providerId) ?: return out
        return out.sortedBy { it.first != act } // stable — active first, rest keep order
    }

    /** The manually-selected active slot suffix, or null (default = slot 1 first). */
    fun activeSuffix(providerId: String): String? =
        try { prefs?.getString("active_" + providerId, null) } catch (_: Exception) { null }

    /** Manual selection — the user's pick is tried first until it actually fails. */
    fun setActive(providerId: String, suffix: String) {
        val p = prefs ?: return
        try { p.edit().putString("active_" + providerId, suffix).apply() } catch (_: Exception) { }
    }

    fun clearActive(providerId: String) {
        val p = prefs ?: return
        try { p.edit().remove("active_" + providerId).apply() } catch (_: Exception) { }
    }

    fun hasAnyKey(tokenStore: com.codespace.ide.data.SecureTokenStore?, providerId: String): Boolean =
        keys(tokenStore, providerId).isNotEmpty()

    /** Append a key at the first free slot number (2+). Returns the slot suffix. */
    fun addKey(tokenStore: com.codespace.ide.data.SecureTokenStore?, providerId: String, key: String): String {
        var n = 2
        while (true) {
            val existing = try { tokenStore?.aiKey(slotSuffix(providerId, n)) } catch (_: Exception) { null }
            if (existing.isNullOrBlank()) break
            n++
        }
        val suf = slotSuffix(providerId, n)
        tokenStore?.setAiKey(suf, key)
        val idx = slots(providerId).toMutableList()
        if (suf !in idx) idx.add(suf)
        saveIndex(providerId, idx)
        return suf
    }

    /** Delete one slot's key value and drop it from the order (slot 1 keeps its place — legacy). */
    fun removeKey(tokenStore: com.codespace.ide.data.SecureTokenStore?, providerId: String, suffix: String) {
        tokenStore?.setAiKey(suffix, null)
        if (activeSuffix(providerId) == suffix) clearActive(providerId)
        saveIndex(providerId, slots(providerId).filter { it != suffix })
    }

    fun setLabel(suffix: String, label: String) {
        val p = prefs ?: return
        val labels = readLabels()
        if (label.isBlank()) labels.remove(suffix) else labels.put(suffix, label.trim().take(40))
        try { p.edit().putString("labels", labels.toString()).apply() } catch (_: Exception) { }
    }

    fun label(suffix: String): String {
        val l = readLabels()
        return try { l.optString(suffix, "") } catch (_: Exception) { "" }
    }

    private fun readLabels(): org.json.JSONObject {
        val p = prefs ?: return org.json.JSONObject()
        val raw: String? = try { p.getString("labels") } catch (_: Exception) { null }
        val txt = raw ?: return org.json.JSONObject()
        return try { org.json.JSONObject(txt) } catch (_: Exception) { org.json.JSONObject() }
    }
}
