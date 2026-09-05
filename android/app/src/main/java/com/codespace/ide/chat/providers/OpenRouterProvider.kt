package com.codespace.ide.chat.providers

import com.codespace.ide.chat.ChatProvider
import com.codespace.ide.chat.ChatRequest
import com.codespace.ide.data.SecureTokenStore

/** OpenRouter — OpenAI-compatible /api/v1/chat/completions shape, "vendor/model" names. */
class OpenRouterProvider : ChatProvider {
    override val id = "openrouter"
    override val displayName = "OpenRouter"
    override val defaultModel = "anthropic/claude-3.5-sonnet"
    override val isLocal = false
    override val requiresApiKey = true

    override fun isAvailable(tokenStore: SecureTokenStore?): Boolean =
        !tokenStore?.aiKey(id.uppercase()).isNullOrBlank()

    override fun unavailableMessage(): String =
        "No $displayName API key found. Add it in Settings."

    override suspend fun complete(request: ChatRequest): String =
        OpenAiCompatibleTransport.call(
            "https://openrouter.ai/api/v1/chat/completions",
            request.apiKey ?: "", request.model, request.convMsgs,
        )
}
