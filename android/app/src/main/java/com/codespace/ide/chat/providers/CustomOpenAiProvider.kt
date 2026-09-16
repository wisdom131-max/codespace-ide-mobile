package com.codespace.ide.chat.providers

import com.codespace.ide.chat.ChatProvider
import com.codespace.ide.chat.ChatRequest
import com.codespace.ide.chat.CustomEndpointStore
import com.codespace.ide.data.SecureTokenStore

/**
 * CUSTOM ENDPOINT — generic OpenAI-compatible provider (7th built-in, 2026-09-11).
 * MK-RESTRUCTURE v2 (2026-09-16): one INSTANCE PER ENDPOINT — each entry in
 * CustomEndpointStore becomes its own provider (VS Code Language Models editor
 * pattern), so endpoints no longer share a single slot/key/model list.
 *
 * CONFIG CONTRACT:
 *   - Endpoint entry: CustomEndpointStore.list() {id, label, baseUrl}.
 *   - Provider id: "custom" for the legacy endpoint (existing keys/selections
 *     keep working), "custom_<endpointId>" for new ones (no colons — selections
 *     persist as "provider:model").
 *   - API key: per-endpoint via ChatKeyPool/SecureTokenStore keyed by provider id.
 *
 * URL NORMALIZATION (saved once, applied everywhere): a base like
 * "https://api.mistral.ai/v1" gets /chat/completions appended; a full
 * "https://host/v1/chat/completions" is used as-is. Models list uses the same
 * base + /models.
 *
 * MK-C REJECTION DIAGNOSTICS: every send/fetch failure is wrapped with the
 * endpoint label + the exact request URL so a "model rejected" is diagnosable —
 * 404 (model id not on this endpoint) vs 401/403 (key for the WRONG endpoint)
 * vs WAF block are no longer ambiguous.
 */
class CustomOpenAiProvider(
    private val endpointId: String = "default",
) : ChatProvider {
    /** Provider id: legacy endpoint keeps "custom"; others are "custom_<endpointId>". */
    override val id = CustomEndpointStore.providerIdFor(endpointId)

    override val displayName: String
        get() {
            val ep = CustomEndpointStore.byId(endpointId)
            return "Custom · " + (ep?.label ?: "Endpoint")
        }

    // Placeholder until the user picks a real model from their server's live
    // /models list (the picker merges live models automatically). Clearly
    // non-real so a misconfigured send fails with the SERVER's error text,
    // which names the real problem.
    override val defaultModel = "custom-model"
    override val supportsAudio = true
    override val defaultModelIsPlaceholder = true
    override val isLocal = false
    override val requiresApiKey = true

    private fun baseUrlOrNull(): String? = CustomEndpointStore.byId(endpointId)?.baseUrl

    override fun isAvailable(tokenStore: SecureTokenStore?): Boolean =
        com.codespace.ide.chat.ChatKeyPool.hasAnyKey(tokenStore, id) && baseUrlOrNull() != null

    override fun unavailableMessage(): String =
        "Set an endpoint URL and API key for '" + displayName + "' in Settings \u2192 AI Providers."

    /** Base without trailing slash; /chat/completions appended unless already a full endpoint. */
    internal fun chatUrl(base: String): String {
        val b = base.trim().trimEnd('/')
        return if (b.endsWith("/chat/completions")) b else b + "/chat/completions"
    }

    /** Models URL: full /models path used as-is, otherwise appended to the base. */
    internal fun modelsUrl(base: String): String {
        val b = base.trim().trimEnd('/')
        return if (b.endsWith("/models")) b else b + "/models"
    }

    override suspend fun complete(request: ChatRequest): String {
        val base = baseUrlOrNull()
            ?: throw Exception("[" + displayName + "] No endpoint URL set. Add or edit it in Settings \u2192 AI Providers.")
        val url = chatUrl(base)
        return try {
            OpenAiCompatibleTransport.call(
                url,
                request.apiKey ?: "", request.model, request.convMsgs, request.images, request.audios,
            )
        } catch (e: Exception) {
            // MK-C: prefix the endpoint context so the error names WHERE it broke.
            throw Exception("[" + displayName + "] " + url + " \u2014 " + (e.message ?: e.javaClass.simpleName), e)
        }
    }

    /**
     * Live model list from the user's own server: GET {base}/models, cached per
     * endpoint with a fetch timestamp. CE-ESCAPE (2026-09-14) semantics kept per
     * endpoint: manual model IDs are MERGED with the live list, and serve as the
     * FALLBACK when the live fetch fails for any reason (WAF block, unreachable,
     * wrong path). A broken /models endpoint can never make the provider unusable
     * — Cline's approach: the user always has the final say on model IDs.
     */
    override suspend fun fetchModels(apiKey: String?): List<String> {
        val manual = CustomEndpointStore.manualModels(endpointId)
        val base = baseUrlOrNull() ?: return manual
        if (apiKey.isNullOrBlank()) return manual
        return try {
            val live = OpenAiCompatibleTransport.fetchModelList(modelsUrl(base), apiKey).take(80)
            CustomEndpointStore.setLiveModels(endpointId, live)
            (manual + live).distinct()
        } catch (e: Exception) {
            if (manual.isEmpty()) throw e
            com.codespace.ide.diagnostics.AppOutputLog.log(
                "[" + id + "] live /models failed (" + (e.message?.take(80) ?: "no detail") + ") \u2014 falling back to manual model IDs", "chat")
            manual
        }
    }
}
