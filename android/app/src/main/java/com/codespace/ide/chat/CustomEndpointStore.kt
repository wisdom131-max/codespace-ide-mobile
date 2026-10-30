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
        /**
         * P0-B: is the row PRESENT? null means "the backend could not say" (a failed or
         * incomplete read), false means genuinely absent. Without this the store cannot tell
         * "this endpoint has no model ids yet" from "the read failed", and a Save then writes
         * an assumed-empty list over ids it never managed to read.
         */
        fun hasModelRow(key: String): Boolean? = if (readModelRow(key) != null) true else null
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
        override fun hasModelRow(key: String): Boolean? = try { p.contains(key) } catch (_: Exception) { null }
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
        override fun hasModelRow(key: String): Boolean? = rows.containsKey(key)
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
    /**
     * B1 (U9 P1, 2026-10-30): the endpoint DOCUMENT read, with its outcome.
     *
     * `endpointsJson()` below deliberately degrades a broken read to `[]` so the registry keeps
     * working; that is fine for a list view but catastrophic for selection, because "no endpoints
     * stored" and "storage would not answer" then look identical and a valid persisted selection
     * resolves to NeedsSetup. This is the read callers that care about that distinction must use.
     */
    sealed class EndpointsRead {
        /** The document was read. An EMPTY list here means genuinely no endpoints. */
        data class Loaded(val endpoints: List<Endpoint>) : EndpointsRead()
        /** The document could not be read (or is corrupt). Never treat as "empty". */
        object Unreadable : EndpointsRead()
    }

    fun readEndpoints(): EndpointsRead {
        val s = storage ?: return EndpointsRead.Unreadable
        val raw = try { s.read() } catch (_: Exception) { return EndpointsRead.Unreadable }
        // null = no document written yet: a real, successful empty state.
        val arr = try { JSONArray(raw ?: "[]") } catch (_: Exception) { return EndpointsRead.Unreadable }
        return EndpointsRead.Loaded(parseEndpoints(arr))
    }

    /**
     * WRITE-SIDE SAFETY (2026-10-30): every mutation must start from a document that was READ
     * successfully. `endpointsJson()` degrades a failed read to `[]`, so a mutation built on it
     * would rewrite the whole document from an empty array and destroy every other endpoint.
     * `null` here means "cannot read" and every caller aborts without writing.
     */
    private fun readDocument(): JSONArray? {
        val s = storage ?: return null
        val raw = try { s.read() } catch (_: Exception) { return null }
        return try { JSONArray(raw ?: "[]") } catch (_: Exception) { null }
    }

    private fun parseEndpoints(arr: JSONArray): List<Endpoint> {
        val out = ArrayList<Endpoint>(arr.length())
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

    /** Lenient list view (UI lists). Selection must use [readEndpoints] so failures stay visible. */
    fun list(): List<Endpoint> =
        (readEndpoints() as? EndpointsRead.Loaded)?.endpoints ?: emptyList()

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
        // Refuse rather than rewrite an unreadable document (would drop every endpoint).
        val arr = readDocument() ?: return null
        var slug = slugOf(trimmedLabel)
        val existing = parseEndpoints(arr).map { it.id }.toHashSet()
        var n = 2
        while (slug in existing) { slug = slugOf(trimmedLabel) + "_" + n; n++ }
        val ep = Endpoint(slug, trimmedLabel, trimmedUrl)
        val o = JSONObject()
        o.put("id", ep.id); o.put("label", ep.label); o.put("baseUrl", ep.baseUrl)
        o.put("enabled", true)
        arr.put(o)
        return if (persistEndpoints(arr)) ep else null
    }

    fun update(id: String, label: String, baseUrl: String): Boolean {
        val trimmedUrl = baseUrl.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) return false
        val arr = readDocument() ?: return false
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
        val arr = readDocument() ?: return false
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
        val arr = readDocument() ?: return false
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

    /**
     * P0-B: the outcome of reading one model list. `Loaded` means the read SUCCEEDED (an empty
     * list is then a real, saved empty list). `Unreadable` means storage could not answer, and
     * the editor must not treat that as "no models" - writing that back would erase ids it
     * never saw.
     */
    sealed class ModelRead {
        data class Loaded(val ids: List<String>) : ModelRead()
        object Unreadable : ModelRead()
    }

    /** User-typed model IDs with the read outcome. Never validated client-side. */
    fun readManualModels(id: String): ModelRead =
        readModelRow("manual_" + id, ',', ';', ' ', '\n')

    /** Discovered ids with the read outcome (the offline cache the picker can still use). */
    fun readLiveModels(id: String): ModelRead =
        readModelRow("live_" + id, '\n')

    private fun readModelRow(key: String, vararg separators: Char): ModelRead {
        val s = storage ?: return ModelRead.Unreadable
        val present = try { s.hasModelRow(key) } catch (_: Exception) { null }
            ?: return ModelRead.Unreadable
        if (!present) return ModelRead.Loaded(emptyList())
        val raw = try { s.readModelRow(key) } catch (_: Exception) { null }
            ?: return ModelRead.Unreadable
        return ModelRead.Loaded(
            raw.split(*separators).map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
    }

    /**
     * Compatibility accessor for read-only callers (the picker, tests): an unreadable row reads
     * as empty. Anything that WRITES must use [readManualModels] instead, so a failed read can
     * never become a destructive save.
     */
    fun manualModels(id: String): List<String> =
        (readManualModels(id) as? ModelRead.Loaded)?.ids ?: emptyList()

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

    fun liveModels(id: String): List<String> =
        (readLiveModels(id) as? ModelRead.Loaded)?.ids ?: emptyList()

    fun liveModelsFetchedAt(id: String): Long = storage?.readModelStamp("live_ts_" + id) ?: 0L

    /** G-C C-4(c): a model-not-found/404 response drops the endpoint's live list —
     * the next picker open refetches instead of serving a dead list. fetchedAt
     * resets with it, so the window logic can never resurrect the stale list. */
    fun clearLiveModels(id: String) {
        storage?.writeModelRow("live_" + id, null)
        storage?.writeModelRow("live_ts_" + id, null)
        versionCounter++
    }

    /**
     * P0-A: reports whether the cache row actually landed, so a caller that commits on Save can
     * tell the user the truth instead of assuming the write worked.
     */
    fun setLiveModels(id: String, models: List<String>): Boolean {
        val ok = storage?.writeModelRow("live_" + id, models.take(80).joinToString("\n")) ?: false
        storage?.writeModelRow("live_ts_" + id, System.currentTimeMillis().toString())
        versionCounter++
        return ok
    }

    // ── Legacy v1 migration (runs once at init) ───────────────────────────

    private var migrated = false

    /**
     * Migrates the v1 single slot (base_url + manual_models prefs) into endpoint
     * id "default" (provider id "custom" — existing ai_CUSTOM keys and saved
     * "custom:model" selections keep working untouched).
     */
    /**
     * WRITE-SIDE SAFETY: what a legacy migration is allowed to do, decided from a READ result.
     * Separated from the prefs plumbing so it is unit-testable and so "document unreadable"
     * can never be confused with "document empty" (which used to seed over real data AND mark
     * the migration complete, permanently skipping it).
     */
    internal sealed class LegacyMigrationAction {
        /** Nothing may be written and the migration must NOT be marked complete. */
        object Skip : LegacyMigrationAction()
        /** Document readable and nothing to seed: just record completion. */
        data class MarkComplete(val manualDefault: String?) : LegacyMigrationAction()
        /** Document readable and empty with a legacy URL: seed it, then mark complete. */
        data class Seed(val baseUrl: String, val manualDefault: String?) : LegacyMigrationAction()
    }

    internal fun planLegacyMigration(
        read: EndpointsRead,
        alreadyMigrated: Boolean,
        legacyUrl: String?,
        legacyManual: String?,
    ): LegacyMigrationAction {
        if (alreadyMigrated) return LegacyMigrationAction.Skip
        val ends = when (read) {
            is EndpointsRead.Loaded -> read.endpoints
            // Cannot tell empty from lost: never write, never mark, retry on a later launch.
            EndpointsRead.Unreadable -> return LegacyMigrationAction.Skip
        }
        if (legacyUrl != null && ends.isEmpty()) {
            return LegacyMigrationAction.Seed(legacyUrl, legacyManual)
        }
        return LegacyMigrationAction.MarkComplete(legacyManual)
    }

    private fun migrateLegacyOnce() {
        if (migrated) return
        try {
            val p = prefs ?: return
            if (p.contains("legacy_migrated_v2")) { migrated = true; return }
            val legacyUrl = p.getString("base_url", null)?.trim()?.takeIf { it.isNotEmpty() }
            val legacyManual = p.getString("manual_models", null)
            when (val action = planLegacyMigration(readEndpoints(), alreadyMigrated = false,
                legacyUrl = legacyUrl, legacyManual = legacyManual)) {
                LegacyMigrationAction.Skip -> { /* unreadable: leave flag + in-memory guard unset */ }
                is LegacyMigrationAction.MarkComplete -> {
                    p.edit().putBoolean("legacy_migrated_v2", true).apply()
                    migrated = true
                }
                is LegacyMigrationAction.Seed -> {
                    val arr = JSONArray()
                    val o = JSONObject()
                    o.put("id", "default"); o.put("label", "Custom"); o.put("baseUrl", action.baseUrl)
                    arr.put(o)
                    // Write through the storage seam: it reports success, prefs.edit() did not.
                    if (storage?.write(arr.toString()) == true) {
                        p.edit().putString("manual_default", action.manualDefault ?: "")
                            .putBoolean("legacy_migrated_v2", true).apply()
                        migrated = true
                        versionCounter++
                    }
                }
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
