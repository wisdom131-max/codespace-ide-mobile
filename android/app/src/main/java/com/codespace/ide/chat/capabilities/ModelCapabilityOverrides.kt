package com.codespace.ide.chat.capabilities

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * G-E-a USER OVERRIDE STORE — per endpoint+model capability overrides.
 *
 * Source 1 of the resolution chain. Persists as plain SharedPreferences JSON
 * (same pattern as CustomEndpointStore): booleans ONLY, NO secrets ever
 * (advisor condition f). Plain wording for UI lives here too, so every
 * consumer uses the same human sentence.
 *
 * UNKNOWN semantics: an absent override simply contributes NOTHING to the
 * resolution (all-null flags) — it never asserts either way.
 */
object ModelCapabilityOverrides {

    private const val PREFS_NAME = "capability_overrides"
    private const val KEY_OVERRIDES = "overrides_v1"
    private const val MAX_ENTRIES = 500
    private const val MAX_KEY_LEN = 128

    /** Version bump on every mutation — Settings UI re-enumerates on it (G-E-c). */
    @Volatile private var versionCounter = 0
    fun version(): Int = versionCounter

    @Volatile private var prefs: SharedPreferences? = null

    /** Injectable persistence seam — tests use [MemoryPersistence]. */
    internal interface Persistence {
        fun load(): String?
        fun save(value: String)
    }

    private class PrefsPersistence(private val p: SharedPreferences) : Persistence {
        override fun load(): String? = p.getString(KEY_OVERRIDES, null)
        override fun save(value: String) { p.edit().putString(KEY_OVERRIDES, value).apply() }
    }

    @Volatile private var persistence: Persistence? = null

    fun init(ctx: Context) {
        persistence = PrefsPersistence(
            ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    internal fun usePersistence(p: Persistence) { persistence = p }

    // ── Model ────────────────────────────────────────────────────────────

    /** User-set override for one endpoint+model pair (images toggle first). */
    data class Override(
        /** "This model accepts images" — the plain wording shown in the UI. */
        val acceptsImages: Boolean? = null,
        /** "This model accepts audio" — U5 toggle, stored from day one. */
        val acceptsAudio: Boolean? = null,
        /** "This model accepts video" — U5 toggle, stored from day one. */
        val acceptsVideo: Boolean? = null,
    ) {
        fun isEmpty(): Boolean = acceptsImages == null && acceptsAudio == null && acceptsVideo == null
    }

    private fun key(providerId: String, modelId: String): String =
        "${providerId.take(MAX_KEY_LEN)}||${modelId.take(MAX_KEY_LEN)}"

    fun get(providerId: String?, modelId: String?): Override? {
        if (providerId == null || modelId == null) return null
        return all()[key(providerId, modelId)]
    }

    /**
     * Sets fields from [patch] for the pair; clears the pair when the merge
     * leaves it empty (a reset falls back to the next source — never asserts
     * UNKNOWN as either true or false).
     */
    fun set(providerId: String, modelId: String, patch: Override) {
        val map = all().toMutableMap()
        val k = key(providerId, modelId)
        val merged = map[k] ?: Override()
        val next = merged.copy(
            acceptsImages = patch.acceptsImages ?: merged.acceptsImages,
            acceptsAudio = patch.acceptsAudio ?: merged.acceptsAudio,
            acceptsVideo = patch.acceptsVideo ?: merged.acceptsVideo,
        )
        if (next.isEmpty()) map.remove(k) else map[k] = next
        // Bounded: oldest entries drop off at the cap (a cap hit is a user
        // behavior anomaly, not a crash).
        while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
        persist(map)
        versionCounter++
    }

    /** Removes one pair entirely (UI "reset to defaults" row). */
    fun clear(providerId: String, modelId: String) {
        val map = all().toMutableMap()
        if (map.remove(key(providerId, modelId)) != null) {
            persist(map)
            versionCounter++
        }
    }

    fun clearAll() {
        persist(emptyMap())
        versionCounter++
    }

    // ── Bounded JSON persistence ────────────────────────────────────────

    private fun all(): Map<String, Override> {
        return try {
            val raw = persistence?.load() ?: return emptyMap()
            if (raw.isBlank()) return emptyMap()
            val arr = JSONArray(raw)
            val out = LinkedHashMap<String, Override>()
            for (i in 0 until arr.length()) {
                if (out.size >= MAX_ENTRIES) break
                val o = arr.optJSONObject(i) ?: continue
                val k = o.optString("k", "").take(MAX_KEY_LEN * 2)
                if (k.isBlank()) continue
                out[k] = Override(
                    acceptsImages = if (o.has("i")) o.getBoolean("i") else null,
                    acceptsAudio = if (o.has("a")) o.getBoolean("a") else null,
                    acceptsVideo = if (o.has("v")) o.getBoolean("v") else null,
                )
            }
            out
        } catch (_: Exception) {
            // Poisoned store -> treat as empty overrides (source 1 contributes
            // nothing); never crash, never guess.
            emptyMap()
        }
    }

    private fun persist(map: Map<String, Override>) {
        val arr = JSONArray()
        for ((k, v) in map.entries.take(MAX_ENTRIES)) {
            arr.put(JSONObject().put("k", k).apply {
                if (v.acceptsImages != null) put("i", v.acceptsImages)
                if (v.acceptsAudio != null) put("a", v.acceptsAudio)
                if (v.acceptsVideo != null) put("v", v.acceptsVideo)
            })
        }
        persistence?.save(arr.toString())
    }
}
