package com.codespace.ide.chat.providers

import com.codespace.ide.chat.ChatProvider
import com.codespace.ide.chat.ChatRequest
import com.codespace.ide.data.SecureTokenStore

/** DeepSeek - OpenAI-compatible /v1/chat/completions shape. */
class DeepSeekProvider : ChatProvider {
    // FIX (404 regression): deepseek-chat / deepseek-reasoner were retired
    // 2026-07-24. Current models per api-docs.deepseek.com: deepseek-v4-flash,
    // deepseek-v4-pro (alias IDs auto-track the latest point release).
    override val id = "deepseek"
    override val displayName = "DeepSeek"
    override val defaultModel = "deepseek-v4-flash"
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

    /** Live model list from GET /models (small, curated list). */
    override suspend fun fetchModels(apiKey: String?): List<String> {
        if (apiKey.isNullOrBlank()) return emptyList()
        return OpenAiCompatibleTransport.fetchModelList("https://api.deepseek.com/models", apiKey)
    }
}
