package com.codespace.ide.chat.providers

import com.codespace.ide.chat.ChatProvider
import com.codespace.ide.chat.ChatRequest
import com.codespace.ide.chat.TokenCounter
import com.codespace.ide.data.SecureTokenStore

/**
 * xAI (Grok) - https://api.x.ai/v1/chat/completions, Bearer auth, OpenAI-compatible
 * shape (verified against xAI's docs: same chat/completions + /models contract).
 * Added as 6th built-in 2026-09-11 — closes the VS Code-parity gap report.
 */
class XaiProvider : ChatProvider {
    // Live model list is fetched from GET /v1/models; this default only answers
    // the very first send (or when the live fetch fails), same pattern as OpenAiProvider.
    override val id = "xai"
    override val displayName = "xAI"
    override val defaultModel = "grok-4"
    override val isLocal = false
    override val requiresApiKey = true

    override fun isAvailable(tokenStore: SecureTokenStore?): Boolean =
        !tokenStore?.aiKey(id.uppercase()).isNullOrBlank()

    override fun unavailableMessage(): String =
        "No $displayName API key found. Add it in Settings."

    override suspend fun complete(request: ChatRequest): String =
        OpenAiCompatibleTransport.call(
            "https://api.x.ai/v1/chat/completions",
            request.apiKey ?: "", request.model, request.convMsgs, request.images,
        )

    /** Live model list from GET /v1/models - grok-* chat IDs only. */
    override suspend fun fetchModels(apiKey: String?): List<String> {
        if (apiKey.isNullOrBlank()) return emptyList()
        return OpenAiCompatibleTransport.fetchModelList("https://api.x.ai/v1/models", apiKey)
            .filter { it.startsWith("grok") }
            .take(40)
    }
    /** STREAMING: OpenAI-compatible SSE — one transport implementation covers the family. */
    override suspend fun completeStreaming(request: ChatRequest, onDelta: (String) -> Unit): String =
        OpenAiCompatibleTransport.callStreaming(
            "https://api.x.ai/v1/chat/completions", request.apiKey ?: "", request.model, request.convMsgs, onDelta, request.images,
        )

    /** TOKEN COUNT: jtokkit BPE (exact for OpenAI models, close proxy for the family). */
    override suspend fun countTokens(request: ChatRequest): Int? =
        TokenCounter.countOpenAiCompatible(request.systemPrompt, request.convMsgs, request.model)

}
