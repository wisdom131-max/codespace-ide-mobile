package com.codespace.ide.chat.providers

import com.codespace.ide.chat.ChatProvider
import com.codespace.ide.chat.ChatRequest
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

    override fun isAvailable(tokenStore: SecureTokenStore?): Boolean =
        !tokenStore?.aiKey(id.uppercase()).isNullOrBlank()

    override fun unavailableMessage(): String =
        "No $displayName API key found. Add it in Settings."

    override suspend fun complete(request: ChatRequest): String =
        OpenAiCompatibleTransport.call(
            "https://openrouter.ai/api/v1/chat/completions",
            request.apiKey ?: "", request.model, request.convMsgs,
        )

    /** Live model list from GET /api/v1/models - major vendors only, capped. */
    override suspend fun fetchModels(apiKey: String?): List<String> {
        val vendors = setOf("anthropic/", "openai/", "google/", "deepseek/", "meta-llama/", "qwen/", "mistralai/")
        // /models is a public endpoint on OpenRouter - no auth header at all
        return OpenAiCompatibleTransport.fetchModelList("https://openrouter.ai/api/v1/models", "", bearer = false)
            .filter { m -> vendors.any { m.startsWith(it) } }
            .take(60)
    }
}
