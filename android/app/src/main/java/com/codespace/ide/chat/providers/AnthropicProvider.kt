package com.codespace.ide.chat.providers

import com.codespace.ide.chat.ChatProvider
import com.codespace.ide.chat.ChatRequest
import com.codespace.ide.data.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Anthropic Messages API — x-api-key header, separate "system" field, response content
 * is a list of blocks rather than a single message string.
 * Body taken verbatim from the old panel-level callClaude().
 */
class AnthropicProvider : ChatProvider {
    override val id = "claude"
    override val displayName = "Claude"
    override val defaultModel = "claude-3-5-sonnet-20241022"
    override val isLocal = false
    override val requiresApiKey = true

    private val http = OkHttpClient()

    override fun isAvailable(tokenStore: SecureTokenStore?): Boolean =
        !tokenStore?.aiKey(id.uppercase()).isNullOrBlank()

    override fun unavailableMessage(): String =
        "No $displayName API key found. Add it in Settings."

    override suspend fun complete(request: ChatRequest): String = withContext(Dispatchers.IO) {
        val apiKey = request.apiKey ?: throw Exception(unavailableMessage())
        val body = JSONObject()
            .put("model", request.model)
            .put("max_tokens", 4096)
            .put("system", request.systemPrompt)
            .put("messages", OpenAiCompatibleTransport.stripSystemMessage(request.convMsgs))
            .toString()
        val resp = http.newCall(
            Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()
        if (!resp.isSuccessful) throw Exception("Claude API error (${resp.code}). Check your key in Settings.")
        val json = JSONObject(resp.body?.string() ?: "")
        json.getJSONArray("content").getJSONObject(0).getString("text")
    }
}
