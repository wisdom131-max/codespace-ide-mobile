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
}

/** Android glue: enumerates the REAL stores once. Read-only. */
class StoredCredentialsReader(
    private val store: SecureTokenStore,
    private val endpoints: CustomEndpointStore,
) {
    fun snapshot(): StoredCredentialsSnapshot {
        val slots = LegacyKeyShapes.normalizeKeySlots(store.rawAiSlots())
        val providersWithKeys = slots.filter { it.hasKey }.map { it.providerId }.toSet()
        val eps = LegacyKeyShapes.normalizeEndpoints(endpoints.rawJson()) { pid -> pid in providersWithKeys }
        Log.i(TAG, "WP1 read: " + slots.size + " key slots (" +
            slots.count { it.hasKey } + " with keys), " + eps.size +
            " endpoints (" + eps.count { it.enabled } + " enabled)")
        return StoredCredentialsSnapshot(slots, eps)
    }

    /** The ONLY place a key value surfaces: straight to the engine. Never logged. */
    fun keyValue(slotName: String): String? = store.aiSlotValue(slotName)

    companion object { private const val TAG = "StoredCreds" }
}
