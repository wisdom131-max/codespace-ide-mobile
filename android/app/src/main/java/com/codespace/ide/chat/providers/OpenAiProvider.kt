package com.codespace.ide.chat.providers

import com.codespace.ide.chat.ChatProvider
import com.codespace.ide.chat.ChatRequest
import com.codespace.ide.chat.TokenCounter
import com.codespace.ide.data.SecureTokenStore

/** OpenAI - https://api.openai.com/v1/chat/completions, Bearer auth, OpenAI-compatible shape. */
class OpenAiProvider : ChatProvider {
    // FIX (404 regression): gpt-4o is retired - 404 model-not-found on every call.
    // gpt-5.5 is the current flagship chat model (GPT-5 series, April 2026).
    override val id = "openai"
    override val displayName = "OpenAI"
    override val defaultModel = "gpt-5.5"
    override val isLocal = false
    override val requiresApiKey = true
    override val supportsAudio = true

    override fun isAvailable(tokenStore: SecureTokenStore?): Boolean =
        com.codespace.ide.chat.ChatKeyPool.hasAnyKey(tokenStore, id)

    override fun unavailableMessage(): String =
        "No $displayName API key found. Add it in Settings."

    override suspend fun complete(request: ChatRequest): String =
        OpenAiCompatibleTransport.call(
            "https://api.openai.com/v1/chat/completions",
            request.apiKey ?: "", request.model, request.convMsgs, request.images, request.audios,
        )

    /** Live model list from GET /v1/models - chat-capable gpt-* IDs only, newest defaults first. */
    override suspend fun fetchModels(apiKey: String?): List<String> {
        if (apiKey.isNullOrBlank()) return emptyList()
        return OpenAiCompatibleTransport.fetchModelList("https://api.openai.com/v1/models", apiKey)
            .filter { it.startsWith("gpt-") }
            .take(40)
    }
    /** STREAMING: OpenAI-compatible SSE — one transport implementation covers the family. */
    override suspend fun completeStreaming(request: ChatRequest, onDelta: (String) -> Unit): String =
        OpenAiCompatibleTransport.callStreaming(
            "https://api.openai.com/v1/chat/completions", request.apiKey ?: "", request.model, request.convMsgs, onDelta, request.images, request.audios,
        )

    /** TOKEN COUNT: jtokkit BPE (exact for OpenAI models, close proxy for the family). */
    override suspend fun countTokens(request: ChatRequest): Int? =
        TokenCounter.countOpenAiCompatible(request.systemPrompt, request.convMsgs, request.model)

}
