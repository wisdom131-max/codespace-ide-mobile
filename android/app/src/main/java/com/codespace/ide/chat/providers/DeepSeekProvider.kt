package com.codespace.ide.chat.providers

import com.codespace.ide.chat.ChatProvider
import com.codespace.ide.chat.ChatRequest
import com.codespace.ide.data.SecureTokenStore

/** DeepSeek — OpenAI-compatible /v1/chat/completions shape. */
class DeepSeekProvider : ChatProvider {
    override val id = "deepseek"
    override val displayName = "DeepSeek"
    override val defaultModel = "deepseek-chat"
    override val isLocal = false
    override val requiresApiKey = true

    override fun isAvailable(tokenStore: SecureTokenStore?): Boolean =
        !tokenStore?.aiKey(id.uppercase()).isNullOrBlank()

    override fun unavailableMessage(): String =
        "No $displayName API key found. Add it in Settings."

    override suspend fun complete(request: ChatRequest): String =
        OpenAiCompatibleTransport.call(
            "https://api.deepseek.com/v1/chat/completions",
            request.apiKey ?: "", request.model, request.convMsgs,
        )
}
