package com.codespace.ide.chat.panel

import com.codespace.ide.chat.keys.SlotNaming
import com.codespace.ide.engine.ProviderPresets

/**
 * U1 (build 657 finding d): pure rules deciding WHAT the panel selects.
 *
 * Owner ruling: the default must be a provider the owner HAS A KEY FOR (migrated
 * slots first, last-used next); NEVER an unconfigured preset and NEVER a
 * mismatched provider/model pair ("ollama + gemini-3.8-flash" came from a preset
 * click inheriting the previous provider's model id, then being persisted). If
 * nothing is configured the panel shows a "Set up a provider" empty state, not a
 * send that is certain to fail.
 *
 * No Android imports: unit-tested on the JVM with real stored-slot shapes.
 */
sealed class ResolvedSelection {
    /** A configured, coherent selection the panel can send with. */
    data class Ready(val selection: PanelSelection) : ResolvedSelection()

    /**
     * B1 (U9 P1, 2026-10-30): the settings stores could not be read, so the selection cannot be
     * validated OR disproved. The pair is CARRIED unchanged: this is never persisted and never
     * clears the session's selection, which is what keeps "storage hiccup" from looking like
     * "you were never configured".
     */
    data class Unavailable(val selection: PanelSelection, val reason: String) : ResolvedSelection()
    /** Nothing usable is configured: show the "Set up a provider" empty state. */
    object NeedsSetup : ResolvedSelection()
}

/** What the owner has configured, derived from the real stores. */
data class ConfiguredProviders(
    /** Engine provider ids that have at least one usable key, in preference order. */
    val withKey: List<String>,
    /** Enabled custom endpoints (provider id, base URL); the key is optional for them. */
    val customEndpoints: List<Pair<String, String>>,
)

object SelectionResolver {

    const val GEMINI_BASE = "https://generativelanguage.googleapis.com"

    /** Base URL for an engine provider id; null if the id is unknown/unconfigurable. */
    fun baseUrlFor(providerId: String, customEndpoints: List<Pair<String, String>>): String? = when {
        providerId == "gemini" -> GEMINI_BASE
        providerId == "anthropic" -> PanelSelection.ANTHROPIC_BASE
        ProviderPresets.byId(providerId) != null -> ProviderPresets.byId(providerId)!!.baseUrl
        else -> customEndpoints.firstOrNull { it.first == providerId }?.second
    }

    /** Model that is safe to pair with a provider on first use; blank = owner must type/pick one. */
    fun defaultModelFor(providerId: String): String = when (providerId) {
        "gemini" -> "gemini-3.8-flash"
        "anthropic" -> PanelSelection.ANTHROPIC_DEFAULT_MODEL
        else -> ProviderPresets.byId(providerId)?.defaultModel ?: ""
    }

    /**
     * True when the model id plainly belongs to a DIFFERENT native family than the
     * provider ("gemini-*" under ollama, "claude-*" under openai). Only the two
     * native families with unambiguous id prefixes are checked: gateways such as
     * OpenRouter / custom endpoints / Ollama legitimately serve any family, so for
     * them only the native-looking id under the WRONG NATIVE-KEYED provider is
     * rejected. Keyless Ollama serving "gemini-..." is the exact build-657 pair.
     */
    fun modelBelongsToAnotherFamily(providerId: String, modelId: String): Boolean {
        val m = modelId.lowercase()
        val isGeminiId = m.startsWith("gemini")
        val isClaudeId = m.startsWith("claude")
        return when (providerId) {
            "gemini" -> isClaudeId
            "anthropic" -> isGeminiId
            "ollama" -> isGeminiId || isClaudeId   // local models are never hosted gemini/claude ids
            "openai", "groq", "mistral", "deepseek", "xai" -> isGeminiId || isClaudeId
            else -> false // openrouter + custom endpoints may serve any family
        }
    }

    /**
     * A selection is COHERENT when its provider is known, its base URL matches that
     * provider, a model is set, and (unless the provider is keyless) a stored key
     * slot is named. A persisted pair failing this is discarded, never restored.
     */
    fun isCoherent(sel: PanelSelection, cfg: ConfiguredProviders): Boolean {
        if (sel.providerId.isBlank() || sel.modelId.isBlank()) return false
        // The provider must still exist, but its URL is NOT part of coherence any more: the
        // stored copy is a cache, and `currentBaseUrl` refreshes it. Model/provider identity is
        // what must hold, and the family check below is what stops a model crossing providers.
        baseUrlFor(sel.providerId, cfg.customEndpoints) ?: return false
        if (modelBelongsToAnotherFamily(sel.providerId, sel.modelId)) return false
        val preset = ProviderPresets.byId(sel.providerId)
        val keyless = preset != null && !preset.requiresKey
        if (keyless) return true
        // Keyed providers and custom endpoints: must be among the configured ones.
        val configured = sel.providerId in cfg.withKey ||
            cfg.customEndpoints.any { it.first == sel.providerId }
        return configured
    }

    /**
     * Pick the selection to show at panel open.
     *  1. the persisted last-used selection, but ONLY if coherent and still configured
     *  2. else the first provider with a stored key (migrated slots, preference order)
     *  3. else NeedsSetup
     * Keyless presets (Ollama) are never auto-picked as a default.
     */
    fun resolve(
        persisted: PanelSelection?,
        cfg: ConfiguredProviders,
        keySlotFor: (providerId: String) -> String,
        /** B1: false when the settings stores refused the read; see [Unavailable]. */
        settingsReadable: Boolean = true,
    ): ResolvedSelection {
        // A read failure cannot prove the pair invalid, so it must not be downgraded to
        // NeedsSetup. Identity is preserved verbatim; only the family rule still applies,
        // because that check needs no store.
        if (persisted != null && !settingsReadable) {
            val identityHolds = persisted.providerId.isNotBlank() &&
                persisted.modelId.isNotBlank() &&
                !modelBelongsToAnotherFamily(persisted.providerId, persisted.modelId)
            if (identityHolds) {
                return ResolvedSelection.Unavailable(
                    persisted,
                    "Your provider settings could not be read right now.",
                )
            }
        }
        // AUDIT DEFECT D FIX: a persisted selection carries a COPY of the endpoint URL, so an
        // endpoint edit used to make the pair "incoherent" and throw the chosen model away. The
        // base is re-read from the stores here, which makes the edit a no-op for the selection.
        if (persisted != null && isCoherent(persisted, cfg)) {
            return ResolvedSelection.Ready(persisted.copy(baseUrl = currentBaseUrl(persisted, cfg)))
        }
        for (pid in cfg.withKey) {
            val model = defaultModelFor(pid)
            val base = baseUrlFor(pid, cfg.customEndpoints) ?: continue
            if (model.isBlank()) continue // keyed preset with no default model: owner picks one
            return ResolvedSelection.Ready(PanelSelection(pid, keySlotFor(pid), model, base))
        }
        return ResolvedSelection.NeedsSetup
    }

    /**
     * The base a request must use RIGHT NOW: the endpoint's current URL when there is one,
     * otherwise the selection's own value (presets never change). Never a mutation.
     */
    fun currentBaseUrl(sel: PanelSelection, cfg: ConfiguredProviders): String =
        currentBaseUrl(sel.providerId, sel.baseUrl, cfg)

    /** Provider-id form, for callers that resolve at send time without a PanelSelection. */
    fun currentBaseUrl(providerId: String, fallback: String, cfg: ConfiguredProviders): String =
        baseUrlFor(providerId, cfg.customEndpoints) ?: fallback

    /**
     * Applying a provider the owner clicked. The model id of the PREVIOUS provider
     * is NEVER carried over (that was the bug): the new pair uses the provider's
     * own default, or the model the owner typed for THIS provider.
     */
    fun applyProvider(
        providerId: String,
        previous: PanelSelection,
        typedModel: String?,
        cfg: ConfiguredProviders,
        keySlotFor: (providerId: String) -> String,
    ): PanelSelection? {
        val base = baseUrlFor(providerId, cfg.customEndpoints) ?: return null
        val sameProvider = providerId == previous.providerId
        val model = typedModel?.trim()?.takeIf { it.isNotEmpty() }
            ?: (if (sameProvider) previous.modelId else defaultModelFor(providerId))
        val preset = ProviderPresets.byId(providerId)
        val slot = if (preset != null && !preset.requiresKey) "" else keySlotFor(providerId)
        return PanelSelection(providerId, slot, model, base)
    }
}
