package com.codespace.ide.chat.providers

import com.codespace.ide.chat.ChatProvider
import com.codespace.ide.chat.ChatRequest
import com.codespace.ide.chat.TokenCounter
import com.codespace.ide.chat.CustomEndpointStore
import com.codespace.ide.data.SecureTokenStore

/**
 * CUSTOM ENDPOINT — generic OpenAI-compatible provider (7th built-in, 2026-09-11).
 * Lets a user point the existing OpenAiCompatibleTransport at ANY server speaking
 * the /chat/completions + /models contract (Mistral, Groq, Together, Fireworks,
 * vLLM behind auth, ...), covering providers we don't name — VS Code parity item.
 *
 * CONFIG CONTRACT:
 *   - Endpoint base URL: CustomEndpointStore.baseUrl (plain prefs — config, not
 *     a credential; VS Code keeps endpoint config in settings too).
 *   - API key: ai_CUSTOM in SecureTokenStore like every other provider.
 *   - isAvailable = URL AND key both set, so the provider only enters the chat
 *     model picker / available() lists once fully configured.
 *
 * URL NORMALIZATION (saved once, applied everywhere): a base like
 * "https://api.mistral.ai/v1" gets /chat/completions appended; a full
 * "https://host/v1/chat/completions" is used as-is. Models list uses the same
 * base + /models.
 */
class CustomOpenAiProvider : ChatProvider {
    override val id = "custom"
    override val displayName = "Custom Endpoint"
    // Placeholder until the user picks a real model from their server's live
    // /models list (the picker merges live models automatically). Clearly
    // non-real so a misconfigured send fails with the SERVER's error text,
    // which names the real problem.
    override val defaultModel = "custom-model"
    override val isLocal = false
    override val requiresApiKey = true

    override fun isAvailable(tokenStore: SecureTokenStore?): Boolean =
        !tokenStore?.aiKey(id.uppercase()).isNullOrBlank() &&
        CustomEndpointStore.baseUrl != null

    override fun unavailableMessage(): String =
        "Set an endpoint URL and API key for your custom provider in Settings."

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
        val base = CustomEndpointStore.baseUrl
            ?: throw Exception("No custom endpoint URL set. Add one in Settings → AI Providers → Custom Endpoint.")
        return OpenAiCompatibleTransport.call(
            chatUrl(base),
            request.apiKey ?: "", request.model, request.convMsgs,
        )
    }

    /** Live model list from the user's own server: GET {base}/models. */
    override suspend fun fetchModels(apiKey: String?): List<String> {
        val base = CustomEndpointStore.baseUrl ?: return emptyList()
        if (apiKey.isNullOrBlank()) return emptyList()
        return OpenAiCompatibleTransport.fetchModelList(modelsUrl(base), apiKey)
            .take(60)
    }

    /** STREAMING: OpenAI-compatible SSE against the user's configured server. */
    override suspend fun completeStreaming(request: ChatRequest, onDelta: (String) -> Unit): String {
        val base = CustomEndpointStore.baseUrl
            ?: throw Exception("No custom endpoint URL set. Add one in Settings → AI Providers → Custom Endpoint.")
        return OpenAiCompatibleTransport.callStreaming(
            chatUrl(base), request.apiKey ?: "", request.model, request.convMsgs, onDelta,
        )
    }

    /** TOKEN COUNT: jtokkit BPE (exact for OpenAI models, close proxy for the family). */
    override suspend fun countTokens(request: ChatRequest): Int? =
        TokenCounter.countOpenAiCompatible(request.systemPrompt, request.convMsgs, request.model)

}
