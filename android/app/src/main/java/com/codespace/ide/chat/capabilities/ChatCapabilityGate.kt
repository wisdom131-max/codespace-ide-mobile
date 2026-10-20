package com.codespace.ide.chat.capabilities

/**
 * G-E-a THE GATE — the ONE seam the chat send path will read (from G-E-b on).
 *
 * G-E-a wires NO call sites: CopilotChatPanelOverlay still reads
 * ChatProviderRegistry.byId(...)?.supportsImages directly, so the send path
 * is byte-identical to today by construction. The advisor's condition (a)
 * holds twice over: the switch is OFF by default, and nothing calls the gate.
 *
 * With the switch OFF this returns the provider constant EXACTLY — the
 * unit test pins that identity. With it ON (owner's word, after the device
 * pass): resolution = user override > live metadata > bundled table, and
 * UNKNOWN (all-null) falls back to the provider constant — today's result
 * (advisor condition b: UNKNOWN is never treated as supported unless today's
 * behavior already says so).
 *
 * SCOPE (condition e): only images and PDF page images. Audio/video flags
 * are declared in ModelCapabilityFlags but NO gate function reads them until
 * U5.
 */
object ChatCapabilityGate {

    /**
     * The images gate — the future replacement for the provider-level
     * `supportsImages` read at the existing call sites (G-E-b swaps them).
     *
     * @param providerId  app provider id ("openai", "claude", "custom_hf", ...)
     * @param modelId     vendor model id from the "provider:model" selection
     * @param providerFallback  TODAY's value: ChatProvider.supportsImages
     * @return the fallback UNCHANGED when the switch is off or the chain is
     *         UNKNOWN; the resolved override/live/bundled value otherwise.
     */
    fun acceptsImages(providerId: String?, modelId: String?, providerFallback: Boolean): Boolean {
        if (!ChatCapabilityFlags.capabilityTableEnabled) return providerFallback
        return resolve(providerId, modelId).acceptsImages ?: providerFallback
    }

    /** PDF page-image gate — same chain, same UNKNOWN rule (U4 scanned path). */
    fun acceptsPdfPageImages(providerId: String?, modelId: String?, providerFallback: Boolean): Boolean =
        acceptsImages(providerId, modelId, providerFallback)

    /**
     * Full per-field resolution: override > live > bundled. Nulls survive —
     * the caller applies the fallback, so UNKNOWN never becomes a claim.
     * Tool/streaming flags flow out for STORAGE/UI only (never wired to
     * execution — advisor condition d).
     */
    fun resolve(providerId: String?, modelId: String?): ModelCapabilityFlags {
        val override = ModelCapabilityOverrides.get(providerId, modelId)
        val live = ModelCapabilityHints.get(providerId, modelId)
        val bundled = ModelCapabilityTable.bundledFlagsFor(providerId, modelId)
        return ModelCapabilityFlags(
            acceptsImages = override?.acceptsImages
                ?: live?.acceptsImages ?: bundled?.acceptsImages,
            acceptsAudio = override?.acceptsAudio ?: live?.acceptsAudio,
            acceptsVideo = override?.acceptsVideo ?: live?.acceptsVideo,
            acceptsPdf = live?.acceptsPdf ?: bundled?.acceptsPdf,
            supportsTools = live?.supportsTools ?: bundled?.supportsTools,
            maxContextTokens = live?.maxContextTokens ?: bundled?.maxContextTokens,
            supportsStreaming = live?.supportsStreaming ?: bundled?.supportsStreaming,
        )
    }
}
