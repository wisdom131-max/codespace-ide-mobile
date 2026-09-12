package com.codespace.ide.chat.providers

import com.codespace.ide.chat.ChatProvider
import com.codespace.ide.chat.ChatRequest
import com.codespace.ide.chat.TokenCounter
import com.codespace.ide.data.SecureTokenStore

/** OpenRouter - OpenAI-compatible /api/v1/chat/completions shape, "vendor/model" names. */
class OpenRouterProvider : ChatProvider {
    // FIX (404 regression): anthropic/claude-3.5-sonnet is retired on OpenRouter
    // ("No endpoints found" = HTTP 404). anthropic/claude-sonnet-5 is current.
    override val id = "openrouter"
    override val displayName = "OpenRouter"
    override val defaultModel = "anthropic/claude-sonnet-5"
    override val isLocal = false
    override val requiresApiKey = true
    override val supportsAudio = true

    override fun isAvailable(tokenStore: SecureTokenStore?): Boolean =
        !tokenStore?.aiKey(id.uppercase()).isNullOrBlank()

    override fun unavailableMessage(): String =
        "No $displayName API key found. Add it in Settings."

    override suspend fun complete(request: ChatRequest): String =
        OpenAiCompatibleTransport.call(
            "https://openrouter.ai/api/v1/chat/completions",
            request.apiKey ?: "", request.model, request.convMsgs, request.images, request.audios,
        )

    /**
     * RICH METADATA (2026-09-11): OpenRouter's public /models returns the REAL
     * context_length per model — the context gauge's most accurate live source.
     * Falls back to id-only ChatModelInfo (context null) on any parse/HTTP problem.
     */
    override suspend fun fetchModelInfos(apiKey: String?): List<com.codespace.ide.chat.ChatModelInfo> {
        val vendors = setOf("anthropic/", "openai/", "google/", "deepseek/", "meta-llama/", "qwen/", "mistralai/")
        return try {
            OpenAiCompatibleTransport.fetchModelInfos("https://openrouter.ai/api/v1/models", bearer = false)
                .filter { m -> vendors.any { m.id.startsWith(it) } }
                .take(60)
        } catch (_: Exception) {
            super.fetchModelInfos(apiKey)
        }
    }

    /** Live model list from GET /api/v1/models - major vendors only, capped. */
    override suspend fun fetchModels(apiKey: String?): List<String> {
        val vendors = setOf("anthropic/", "openai/", "google/", "deepseek/", "meta-llama/", "qwen/", "mistralai/")
        // /models is a public endpoint on OpenRouter - no auth header at all
        return OpenAiCompatibleTransport.fetchModelList("https://openrouter.ai/api/v1/models", "", bearer = false)
            .filter { m -> vendors.any { m.startsWith(it) } }
            .take(60)
    }
    /** STREAMING: OpenAI-compatible SSE — one transport implementation covers the family. */
    override suspend fun completeStreaming(request: ChatRequest, onDelta: (String) -> Unit): String =
        OpenAiCompatibleTransport.callStreaming(
            "https://openrouter.ai/api/v1/chat/completions", request.apiKey ?: "", request.model, request.convMsgs, onDelta, request.images, request.audios,
        )

    /** TOKEN COUNT: jtokkit BPE (exact for OpenAI models, close proxy for the family). */
    override suspend fun countTokens(request: ChatRequest): Int? =
        TokenCounter.countOpenAiCompatible(request.systemPrompt, request.convMsgs, request.model)

}
