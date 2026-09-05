package com.codespace.ide.chat.providers

import com.codespace.ide.chat.ChatProvider
import com.codespace.ide.chat.ChatRequest
import com.codespace.ide.data.SecureTokenStore

/** OpenAI — https://api.openai.com/v1/chat/completions, Bearer auth, OpenAI-compatible shape. */
class OpenAiProvider : ChatProvider {
    override val id = "openai"
    override val displayName = "OpenAI"
    override val defaultModel = "gpt-4o"
    override val isLocal = false
    override val requiresApiKey = true

    override fun isAvailable(tokenStore: SecureTokenStore?): Boolean =
        !tokenStore?.aiKey(id.uppercase()).isNullOrBlank()

    override fun unavailableMessage(): String =
        "No $displayName API key found. Add it in Settings."

    override suspend fun complete(request: ChatRequest): String =
        OpenAiCompatibleTransport.call(
            "https://api.openai.com/v1/chat/completions",
            request.apiKey ?: "", request.model, request.convMsgs,
        )
}
