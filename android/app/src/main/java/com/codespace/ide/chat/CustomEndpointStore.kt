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
 *   - Each endpoint owns its OWN key slots via ChatKeyPool/SecureTokenStore
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
    )

    // ── Registry CRUD ─────────────────────────────────────────────────────

    /** Version bump on every mutation — Settings/picker use it to re-enumerate. */
    @Volatile private var versionCounter = 0
    fun version(): Int = versionCounter

    private fun endpointsJson(): JSONArray = try {
        JSONArray(prefs?.getString("endpoints_v2", "[]") ?: "[]")
    } catch (_: Exception) { JSONArray() }

    private fun persistEndpoints(arr: JSONArray) {
        try {
            prefs?.edit()?.putString("endpoints_v2", arr.toString())?.apply()
        } catch (_: Exception) { }
        versionCounter++
        syncProviders()
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
                if (id.isNotBlank() && baseUrl.isNotBlank()) out.add(Endpoint(id, label, baseUrl))
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
        arr.put(o)
        persistEndpoints(arr)
        return ep
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
                persistEndpoints(arr)
                return true
            }
        }
        return false
    }

    /** Delete an endpoint + all its models (manual + live cache). */
    fun delete(id: String): Boolean {
        val arr = endpointsJson()
        var removed = false
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) { arr.remove(i); removed = true }
        }
        if (removed) {
            prefs?.edit()?.remove("manual_" + id)?.remove("live_" + id)?.remove("live_ts_" + id)?.apply()
            persistEndpoints(arr)
        }
        return removed
    }

    // ── Per-endpoint manual model registry (plan B) ───────────────────────

    /** User-typed model IDs — never validated client-side; the server is the judge. */
    fun manualModels(id: String): List<String> = try {
        (prefs?.getString("manual_" + id, "") ?: "")
            .split(',', ';', ' ', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    } catch (_: Exception) { emptyList() }

    fun setManualModels(id: String, raw: String) {
        try {
            prefs?.edit()?.putString("manual_" + id, raw)?.apply()
        } catch (_: Exception) { }
        versionCounter++
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
        (prefs?.getString("live_" + id, "") ?: "")
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    } catch (_: Exception) { emptyList() }

    fun liveModelsFetchedAt(id: String): Long = try {
        prefs?.getLong("live_ts_" + id, 0L) ?: 0L
    } catch (_: Exception) { 0L }

    fun setLiveModels(id: String, models: List<String>) {
        try {
            prefs?.edit()?.putString("live_" + id, models.take(80).joinToString("\n"))
                ?.putLong("live_ts_" + id, System.currentTimeMillis())?.apply()
        } catch (_: Exception) { }
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
        try {
            val want = list().map { providerIdFor(it.id) }
            // Drop provider instances for endpoints that no longer exist.
            com.codespace.ide.chat.ChatProviderRegistry.all()
                .filter { it.id == "custom" || it.id.startsWith("custom_") }
                .filter { it.id !in want }
                .forEach { com.codespace.ide.chat.ChatProviderRegistry.unregister(it.id) }
            // (Re)register every current endpoint — register() replaces by id, so
            // label/baseUrl edits are reflected immediately.
            com.codespace.ide.chat.ProviderBootstrap.registerCustomEndpoints()
        } catch (_: Exception) { }
    }
}
