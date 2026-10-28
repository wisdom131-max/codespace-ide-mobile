package com.codespace.ide.chat.keys

/**
 * U1 (build 657 device findings a/c/d): the ONE place that maps a provider id to
 * its SecureTokenStore slot, in the REAL writer's shape.
 *
 * The real shape (archive tag chat-archive-2026-10-27, ChatProvider.kt /
 * ChatKeyPool.slotSuffix / AiKeysSection): slot = "ai_" + providerId.UPPERCASE,
 * pool keys append "_2", "_3". Legacy provider ids: gemini, claude (Anthropic),
 * openai, openrouter, deepseek, xai, plus "custom" / "custom_<endpointId>".
 *
 * WP1 invented lowercase fixtures ("ai_google") instead of building them from the
 * real writer, so every lookup missed on the owner's device: keys were found but
 * unusable. This object is pure (no Android imports) and unit-tested against the
 * real shapes. Reads are case-insensitive so a lowercase slot written by build 657
 * (ai_openai) is still found and never orphaned.
 */
object SlotNaming {

    /** Legacy provider id -> new engine provider id (adapter family). */
    private val LEGACY_TO_ENGINE = mapOf("claude" to "anthropic")
    private val ENGINE_TO_LEGACY = mapOf("anthropic" to "claude")

    /** Engine provider id for a stored/legacy id, lowercase ("CLAUDE" -> "anthropic"). */
    fun engineProviderId(storedProviderId: String): String {
        val low = storedProviderId.lowercase()
        return LEGACY_TO_ENGINE[low] ?: low
    }

    /** Legacy id for an engine id ("anthropic" -> "claude"); identity for the rest. */
    fun legacyProviderId(engineProviderId: String): String {
        val low = engineProviderId.lowercase()
        return ENGINE_TO_LEGACY[low] ?: low
    }

    /** Primary slot name the REAL writer uses: ai_GEMINI, ai_CLAUDE, ai_CUSTOM_ABC. */
    fun primarySlot(engineProviderId: String): String =
        "ai_" + legacyProviderId(engineProviderId).uppercase()

    /** Pool slot (n >= 2): ai_GEMINI_2. n <= 1 is the primary. */
    fun poolSlot(engineProviderId: String, n: Int): String =
        if (n <= 1) primarySlot(engineProviderId) else primarySlot(engineProviderId) + "_" + n

    /** SecureTokenStore.setAiKey/aiKey take the part AFTER "ai_". */
    fun storeKeyFor(engineProviderId: String, n: Int = 1): String =
        poolSlot(engineProviderId, n).removePrefix("ai_")

    /**
     * Custom endpoint provider id -> store key part. "custom" (legacy default
     * endpoint) -> "CUSTOM"; "custom_hf" -> "CUSTOM_HF".
     */
    fun customStoreKey(customProviderId: String): String = customProviderId.uppercase()

    /**
     * Resolve which stored slot (if any) holds a key for an engine provider,
     * tolerant of case: prefers the real uppercase shape, falls back to a lowercase
     * slot written by build 657. [present] answers "does this exact slot name hold
     * a non-blank key". Primary first, then _2.._N in order.
     */
    fun resolveSlots(engineProviderId: String, allSlotNames: Collection<String>,
                     present: (String) -> Boolean): List<String> {
        val wantBase = primarySlot(engineProviderId).lowercase()
        val matches = allSlotNames.filter { name ->
            val low = name.lowercase()
            low == wantBase || (low.startsWith(wantBase + "_") &&
                low.removePrefix(wantBase + "_").toIntOrNull()?.let { it >= 2 } == true)
        }.filter(present)
        return matches.sortedWith(compareBy({ poolIndexOf(it, wantBase) }, { it }))
    }

    private fun poolIndexOf(slotName: String, wantBaseLower: String): Int {
        val low = slotName.lowercase()
        return if (low == wantBaseLower) 1 else low.removePrefix(wantBaseLower + "_").toIntOrNull() ?: 99
    }
}
