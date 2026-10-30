package com.codespace.ide.chat.keys

import android.util.Log
import com.codespace.ide.chat.CustomEndpointStore
import com.codespace.ide.data.SecureTokenStore
import org.json.JSONArray

/**
 * WP1 (c) - READ-ONLY migration of the owner's stored keys and endpoints.
 *
 * Rules baked in (advisor 2026-10-28):
 *  - NEVER writes or deletes anything. No edit() call exists on this path;
 *    SecureTokenStore, endpoints_v2, history and model prefs are untouched.
 *  - Empty key slots and DISABLED endpoints are PRESERVED and listed - they
 *    are not orphans.
 *  - Key VALUES surface only through the single keyValue() accessor (they go
 *    straight to the engine); logging prints counts and slot names ONLY.
 *
 * The PURE normalization logic (LegacyKeyShapes below) has no Android imports
 * and is unit-tested against fixtures built from the REAL writer shapes:
 * SecureTokenStore slots 'ai_<provider>' plus pool suffixes 'ai_<provider>_2'
 * (SecureTokenStore.aiKey/setAiKey, the old ChatKeyPool family), and
 * CustomEndpointStore's 'endpoints_v2' JSON array of {id,label,baseUrl}
 * (enabled defaults to true when the field is absent).
 */

/** One legacy key slot. The value itself is NEVER held here. */
data class StoredKeySlot(
    val providerId: String,
    val poolIndex: Int?,
    val slotName: String,
    val hasKey: Boolean,
)

/** One legacy custom endpoint, normalized (disabled ones stay; hasKey reflects its key slot). */
data class StoredEndpoint(
    val id: String,
    val label: String,
    val baseUrl: String,
    val enabled: Boolean,
    val hasKey: Boolean,
    val providerId: String,
)

/** Normalized snapshot the new provider system consumes - no key values inside. */
data class StoredCredentialsSnapshot(
    val keySlots: List<StoredKeySlot>,
    val endpoints: List<StoredEndpoint>,
)

/** Pure shape logic - no Android imports, fully unit-tested. */
object LegacyKeyShapes {

    const val SLOT_PREFIX = "ai_"

    /**
     * Parse 'ai_<PROVIDER>' / 'ai_<PROVIDER>_2' into ENGINE provider id + pool index.
     * U1: the real writer UPPERCASES ('ai_GEMINI', 'ai_CLAUDE', 'ai_CUSTOM_HF_2'); the
     * provider id is normalized to lowercase and the legacy 'claude' maps to the
     * 'anthropic' adapter. A trailing _N (N >= 2) is a pool index; _1 or a non-number
     * suffix is part of the id (custom endpoint ids may contain digits/underscores).
     * Returns null for non-ai_ entries (proj_*, github, pin are not provider keys)
     * and for the 'ai_active' marker (not a key).
     */
    fun parseSlot(slotName: String): Pair<String, Int?>? {
        if (!slotName.startsWith(SLOT_PREFIX)) return null
        val rest = slotName.removePrefix(SLOT_PREFIX)
        if (rest.isBlank() || rest.equals("active", ignoreCase = true)) return null
        val idx = rest.lastIndexOf('_')
        if (idx > 0) {
            val suffix = rest.substring(idx + 1)
            val n = suffix.toIntOrNull()
            if (n != null && n >= 2) return Pair(SlotNaming.engineProviderId(rest.substring(0, idx)), n)
        }
        return Pair(SlotNaming.engineProviderId(rest), null)
    }

    /**
     * Normalize raw secure-pref entries (slotName -> value). Empty values are
     * KEPT with hasKey=false. Values are read only to test presence - they are
     * never copied, logged, or stored in the result.
     */
    fun normalizeKeySlots(raw: Map<String, String>): List<StoredKeySlot> {
        val out = ArrayList<StoredKeySlot>()
        for ((slot, value) in raw) {
            val parsed = parseSlot(slot) ?: continue
            val (provider, pool) = parsed
            out.add(StoredKeySlot(provider, pool, slot, !value.isNullOrBlank()))
        }
        return out.sortedBy { it.slotName }
    }

    /**
     * Normalize an endpoints_v2 JSON array. Entries with blank id or baseUrl are
     * skipped (unreadable). DISABLED entries are KEPT - not orphans. hasKeyFor
     * is asked per endpoint provider id so key PRESENCE can be reported without
     * a key value ever crossing this function.
     */
    fun normalizeEndpoints(endpointsV2Json: String, hasKeyFor: (String) -> Boolean): List<StoredEndpoint> {
        val out = ArrayList<StoredEndpoint>()
        val arr = try { JSONArray(endpointsV2Json) } catch (_: Exception) { return out }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val baseUrl = o.optString("baseUrl")
            if (id.isBlank() || baseUrl.isBlank()) continue
            val label = o.optString("label").ifBlank { id }
            val enabled = o.optBoolean("enabled", true)
            val providerId = if (id == "default") "custom" else "custom_" + id
            out.add(StoredEndpoint(id, label, baseUrl, enabled, hasKeyFor(providerId), providerId))
        }
        return out
    }

    /**
     * B1: the same mapping from an ALREADY-READ endpoint list. The selection path uses this so
     * the document is read exactly once, with its outcome, instead of parsing a swallowed copy.
     */
    fun normalizeEndpoints(
        endpoints: List<CustomEndpointStore.Endpoint>,
        hasKeyFor: (String) -> Boolean,
    ): List<StoredEndpoint> {
        val out = ArrayList<StoredEndpoint>(endpoints.size)
        for (e in endpoints) {
            if (e.id.isBlank() || e.baseUrl.isBlank()) continue
            val providerId = if (e.id == "default") "custom" else "custom_" + e.id
            out.add(StoredEndpoint(e.id, e.label, e.baseUrl, e.enabled, hasKeyFor(providerId), providerId))
        }
        return out
    }
}

/**
 * B1 (U9 P1, 2026-10-30): the snapshot PLUS whether the stores actually answered.
 * `readable = false` means "unknown", never "empty" - callers must not turn it into
 * an unconfigured state.
 */
data class SnapshotRead(
    val snapshot: StoredCredentialsSnapshot,
    val readable: Boolean,
)

/** Android glue: enumerates the REAL stores once. Read-only. */
class StoredCredentialsReader(
    private val rawSlots: () -> Map<String, String>,
    private val slotValue: (String) -> String?,
    private val endpoints: CustomEndpointStore,
) {
    /** Production wiring: the encrypted token store plus the endpoint registry. */
    constructor(store: SecureTokenStore, endpoints: CustomEndpointStore) : this(
        { store.rawAiSlots() },
        { slot -> store.aiSlotValue(slot) },
        endpoints,
    )

    /**
     * B1: the read the SELECTION path must use. Keys and endpoints are read separately, and a
     * failure in either reports `readable = false` instead of degrading to an empty config.
     */
    fun readSnapshot(): SnapshotRead {
        val slotsRaw = try { rawSlots() } catch (_: Exception) { null }
        val endpointsRead = endpoints.readEndpoints()
        val slots = LegacyKeyShapes.normalizeKeySlots(slotsRaw ?: emptyMap())
        val providersWithKeys = slots.filter { it.hasKey }.map { it.providerId }.toSet()
        val eps = when (endpointsRead) {
            is CustomEndpointStore.EndpointsRead.Loaded ->
                LegacyKeyShapes.normalizeEndpoints(endpointsRead.endpoints) { pid -> pid in providersWithKeys }
            // Unreadable: the list is EMPTY but `readable` is false, so nobody may conclude
            // "no endpoints configured" from it.
            CustomEndpointStore.EndpointsRead.Unreadable -> emptyList()
        }
        val readable = slotsRaw != null && endpointsRead is CustomEndpointStore.EndpointsRead.Loaded
        Log.i(TAG, "WP1 read: " + slots.size + " key slots (" +
            slots.count { it.hasKey } + " with keys), " + eps.size +
            " endpoints (" + eps.count { it.enabled } + " enabled), readable=" + readable)
        return SnapshotRead(StoredCredentialsSnapshot(slots, eps), readable)
    }

    /** Lenient snapshot for surfaces that only render lists (docs/status/settings). */
    fun snapshot(): StoredCredentialsSnapshot = readSnapshot().snapshot

    /** The ONLY place a key value surfaces: straight to the engine. Never logged. */
    fun keyValue(slotName: String): String? = slotValue(slotName)

    companion object { private const val TAG = "StoredCreds" }
}
