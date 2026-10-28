package com.codespace.ide.chat

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * CUSTOM ENDPOINT REGISTRY v2 — MK-RESTRUCTURE (2026-09-16, Wisdom-approved plan A+B).
 *
 * v1 (2026-09-11) had ONE baseUrl slot + ONE shared ai_CUSTOM key + ONE merged
 * model list. Every custom endpoint the user tried (Hugging Face, Mistral, ...)
 * overwrote the same slot: one key got sent to every endpoint, and manual model
 * IDs typed for endpoint A stayed pickable (and 404ing) while pointed at
 * endpoint B. That single-slot design is what broke the MK batch.
 *
 * v2 mirrors VS Code's Language Models editor:
 *   - Endpoints are a CRUD LIST: {id, label, baseUrl} — add, edit, DELETE.
 *   - Each endpoint is its own PROVIDER INSTANCE (provider id: "custom" for the
 *     migrated legacy endpoint, "custom_<slug>" for new ones — never colons,
 *     because selections persist as "provider:model").
 *   - Each endpoint owns its OWN key slots in SecureTokenStore (the old ChatKeyPool
 *     pool class is archived and removed with the old chat; slots/data remain —
 *     docs/stored-formats.md is the rebuild's read-only contract)
 *     (keys are addressed by provider id, so per-endpoint keys work for free).
 *   - Each endpoint owns its own model registry: manual IDs (user-typed, never
 *     client-side validated — Cline's rule) + a cached live /models list with a
 *     fetch timestamp. The picker shows both groups, labeled, per-entry deletable.
 *
 * Persistence: plain SharedPreferences ("custom_endpoint" prefs, kept from v1 so
 * migration is one read of the two legacy keys). Endpoint config is settings,
 * not credentials — matches VS Code (endpoint/model config in settings; only the
 * key goes in SecretStorage).
 */
object CustomEndpointStore {
    @Volatile private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("custom_endpoint", Context.MODE_PRIVATE)
        if (prefs != null) useStorage(PrefsEndpointStorage(prefs!!))
        migrateLegacyOnce()
        // Registry may have bootstrapped BEFORE init (prefs null -> no endpoints);
        // re-register now that the store is live.
        syncProviders()
    }

    // ── Model ────────────────────────────────────────────────────────────

    data class Endpoint(
        val id: String,
        val label: String,
        val baseUrl: String,
        /** WP4: enable/disable without deleting; disabled endpoints are NOT orphans. */
        val enabled: Boolean = true,
    )

    // ── Registry CRUD ─────────────────────────────────────────────────────

    /** Version bump on every mutation — Settings/picker use it to re-enumerate. */
    @Volatile private var versionCounter = 0
    fun version(): Int = versionCounter

    /** WP1 (c) migration: raw endpoints_v2 JSON, read-only (never mutated here). */
    fun rawJson(): String = endpointsJson().toString()

    /**
     * U1 (build 657 finding c): the persistence SEAM. Reads/writes went through a
     * null-safe `prefs?.edit()?.apply()` whose failure was invisible: a failed or
     * pre-init write silently discarded the endpoint. Storage now reports the
     * outcome, so the form can say "could not save" instead of closing on a lie.
     * JVM tests swap in MemoryEndpointStorage and get a real save -> read-back round trip.
     */
    interface EndpointStorage {
        fun read(): String?
        fun write(json: String): Boolean
        fun removeModelRows(id: String): Boolean = true
        fun readModelRow(key: String): String? = null
        fun writeModelRow(key: String, value: String?): Boolean = true
        // The stamp is written as a row; the default reader derives it, so every
        // backend (prefs or in-memory) agrees without re-implementing it.
        fun readModelStamp(key: String): Long = readModelRow(key)?.toLongOrNull() ?: 0L
    }

    class PrefsEndpointStorage(private val p: SharedPreferences) : EndpointStorage {
        override fun read(): String? = p.getString("endpoints_v2", null)
        // commit(), not apply(): the caller must be able to TELL whether it saved.
        override fun write(json: String): Boolean =
            try { p.edit().putString("endpoints_v2", json).commit() } catch (_: Exception) { false }
        override fun removeModelRows(id: String): Boolean = try {
            p.edit().remove("manual_" + id).remove("live_" + id).remove("live_ts_" + id).commit()
        } catch (_: Exception) { false }
        override fun readModelRow(key: String): String? = try { p.getString(key, null) } catch (_: Exception) { null }
        override fun writeModelRow(key: String, value: String?): Boolean = try {
            val e = p.edit()
            if (value == null) e.remove(key) else e.putString(key, value)
            e.commit()
        } catch (_: Exception) { false }
        override fun readModelStamp(key: String): Long = try {
            if (p.contains(key)) p.getLong(key, 0L)
            else p.getString(key, null)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L }
    }

    /** Test double: a plain in-memory string, same semantics, no Android needed. */
    class MemoryEndpointStorage(var json: String? = null) : EndpointStorage {
        val rows = HashMap<String, String>()
        override fun read(): String? = json
        override fun write(json: String): Boolean { this.json = json; return true }
        override fun removeModelRows(id: String): Boolean {
            rows.keys.removeAll { it == "manual_" + id || it == "live_" + id || it == "live_ts_" + id }
            return true
        }
        override fun readModelRow(key: String): String? = rows[key]
        override fun writeModelRow(key: String, value: String?): Boolean {
            if (value == null) rows.remove(key) else rows[key] = value
            return true
        }
    }

    @Volatile internal var storage: EndpointStorage? = null
        private set

    /** U1: test/init entry point. Production calls it from init() with the prefs backend. */
    internal fun useStorage(s: EndpointStorage) { storage = s }

    private fun endpointsJson(): JSONArray = try {
        JSONArray(storage?.read() ?: "[]")
    } catch (_: Exception) { JSONArray() }

    /** U1: returns whether the write actually landed (no more silent loss). */
    private fun persistEndpoints(arr: JSONArray): Boolean {
        val ok = storage?.write(arr.toString()) ?: false
        versionCounter++
        syncProviders()
        return ok
    }

    /** All endpoints, registry order (legacy "custom" first if present). */
    fun list(): List<Endpoint> {
        val out = ArrayList<Endpoint>()
        val arr = endpointsJson()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val label = o.optString("label").ifBlank { id }
                val baseUrl = o.optString("baseUrl")
                if (id.isNotBlank() && baseUrl.isNotBlank())
                    out.add(Endpoint(id, label, baseUrl, o.optBoolean("enabled", true)))
            } catch (_: Exception) { }
        }
        return out
    }

    fun byId(id: String): Endpoint? = list().firstOrNull { it.id == id }

    /** Stable provider id for an endpoint ("custom" keeps the legacy slot). */
    fun providerIdFor(id: String): String = if (id == "default") "custom" else "custom_" + id

    fun endpointIdForProvider(providerId: String): String? {
        if (providerId == "custom") return "default"
        if (providerId.startsWith("custom_")) return providerId.removePrefix("custom_")
        return null
    }

    private fun slugOf(label: String): String {
        val base = label.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)
        return base.ifBlank { "endpoint" }
    }

    /** Add a new endpoint; returns its assigned id (unique slug). */
    fun add(label: String, baseUrl: String): Endpoint? {
        val trimmedUrl = baseUrl.trim()
        val trimmedLabel = label.trim().ifBlank { "Custom" }
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) return null
        var slug = slugOf(trimmedLabel)
        val existing = list().map { it.id }.toHashSet()
        var n = 2
        while (slug in existing) { slug = slugOf(trimmedLabel) + "_" + n; n++ }
        val ep = Endpoint(slug, trimmedLabel, trimmedUrl)
        val arr = endpointsJson()
        val o = JSONObject()
        o.put("id", ep.id); o.put("label", ep.label); o.put("baseUrl", ep.baseUrl)
        o.put("enabled", true)
        arr.put(o)
        return if (persistEndpoints(arr)) ep else null
    }

    fun update(id: String, label: String, baseUrl: String): Boolean {
        val trimmedUrl = baseUrl.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) return false
        val arr = endpointsJson()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) {
                o.put("label", label.trim().ifBlank { id })
                o.put("baseUrl", trimmedUrl)
                // G-C C-1b: a base-URL edit invalidates the window cache — models
                return persistEndpoints(arr)
            }
        }
        return false
    }

    /** Delete an endpoint + all its models (manual + live cache). */
    /** WP4: enable/disable in place; disabled endpoints stay listed (not orphans). */
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val arr = endpointsJson()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) {
                o.put("enabled", enabled)
                return persistEndpoints(arr)
            }
        }
        return false
    }

    /**
     * WP4 delete-with-keys rule: delete() removes the ENDPOINT and its model
     * prefs ONLY. Stored key slots (ai_custom, ai_custom_<id>) are NEVER touched
     * here - the editor UI asks the owner explicitly before any key deletion.
     */
    fun delete(id: String): Boolean {
        val arr = endpointsJson()
        var removed = false
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) { arr.remove(i); removed = true }
        }
        if (removed) {
            storage?.removeModelRows(id)
            return persistEndpoints(arr)
        }
        return false
    }

    // ── Per-endpoint manual model registry (plan B) ───────────────────────

    /** User-typed model IDs — never validated client-side; the server is the judge. */
    fun manualModels(id: String): List<String> = try {
        (storage?.readModelRow("manual_" + id) ?: "")
            .split(',', ';', ' ', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    } catch (_: Exception) { emptyList() }

    /** U1: returns whether the row was stored (callers can report a real failure). */
    fun setManualModels(id: String, raw: String): Boolean {
        val ok = storage?.writeModelRow("manual_" + id, raw) ?: false
        versionCounter++
        return ok
    }

    fun addManualModel(id: String, model: String) {
        val m = model.trim()
        if (m.isEmpty()) return
        val cur = manualModels(id).toMutableList()
        if (m !in cur) cur.add(m)
        setManualModels(id, cur.joinToString(","))
    }

    fun removeManualModel(id: String, model: String) {
        val cur = manualModels(id).toMutableList()
        if (cur.remove(model)) setManualModels(id, cur.joinToString(","))
    }

    // ── Per-endpoint live-model cache (plan B) ───────────────────────────

    fun liveModels(id: String): List<String> = try {
        (storage?.readModelRow("live_" + id) ?: "")
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    } catch (_: Exception) { emptyList() }

    fun liveModelsFetchedAt(id: String): Long = storage?.readModelStamp("live_ts_" + id) ?: 0L

    /** G-C C-4(c): a model-not-found/404 response drops the endpoint's live list —
     * the next picker open refetches instead of serving a dead list. fetchedAt
     * resets with it, so the window logic can never resurrect the stale list. */
    fun clearLiveModels(id: String) {
        storage?.writeModelRow("live_" + id, null)
        storage?.writeModelRow("live_ts_" + id, null)
        versionCounter++
    }

    fun setLiveModels(id: String, models: List<String>) {
        storage?.writeModelRow("live_" + id, models.take(80).joinToString("\n"))
        storage?.writeModelRow("live_ts_" + id, System.currentTimeMillis().toString())
        versionCounter++
    }

    // ── Legacy v1 migration (runs once at init) ───────────────────────────

    private var migrated = false

    /**
     * Migrates the v1 single slot (base_url + manual_models prefs) into endpoint
     * id "default" (provider id "custom" — existing ai_CUSTOM keys and saved
     * "custom:model" selections keep working untouched).
     */
    private fun migrateLegacyOnce() {
        if (migrated) return
        migrated = true
        try {
            val p = prefs ?: return
            if (p.contains("legacy_migrated_v2")) return
            val legacyUrl = p.getString("base_url", null)?.trim()?.takeIf { it.isNotEmpty() }
            val legacyManual = p.getString("manual_models", null)
            val arr = endpointsJson()
            if (legacyUrl != null && arr.length() == 0) {
                val o = JSONObject()
                o.put("id", "default"); o.put("label", "Custom"); o.put("baseUrl", legacyUrl)
                arr.put(o)
                p.edit().putString("endpoints_v2", arr.toString())
                    .putString("manual_default", legacyManual ?: "")
                    .putBoolean("legacy_migrated_v2", true).apply()
                versionCounter++
            } else {
                p.edit().putBoolean("legacy_migrated_v2", true).apply()
            }
        } catch (_: Exception) { }
    }

    // ── Provider sync ─────────────────────────────────────────────────────

    /**
     * Registers one provider instance per endpoint into ChatProviderRegistry so
     * each endpoint appears as its own pickable provider ("Custom · HF Router").
     * Old instances (deleted endpoints) are dropped. Called from every mutation
     * and after init — the registry stays authoritative for enumeration.
     */
    fun syncProviders() {
        // REMOVAL 4/7 (advisor ruling 2026-10-27): the provider registry is gone
        // with the old chat engine; this endpoint-store hook no longer syncs into
        // it. The stored endpoints_v2 DATA is untouched — the rebuild's migration
        // reads it read-only (docs/stored-formats.md).
    }

}
