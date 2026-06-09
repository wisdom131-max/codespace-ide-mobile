package com.codespace.ide.ai

import com.codespace.ide.domain.AiProviderId
import com.codespace.ide.domain.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class ProviderConfig(val apiKey: String, val baseUrl: String = "")

private fun openAiStyleChat(
    messages: List<ChatMessage>,
    baseUrl: String,
    model: String,
    apiKey: String,
    client: OkHttpClient,
    extraHeaders: Map<String, String> = emptyMap(),
): Flow<AiChunk> = flow {
    val messagesJson = JSONArray()
    for (m in messages) {
        messagesJson.put(JSONObject().put("role", m.role).put("content", m.content))
    }
    val body = JSONObject()
        .put("model", model)
        .put("messages", messagesJson)
        .toString()
    val reqBuilder = Request.Builder()
        .url(baseUrl)
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
    for ((k, v) in extraHeaders) reqBuilder.header(k, v)
    reqBuilder.post(body.toRequestBody("application/json".toMediaType()))
    try {
        val response = client.newCall(reqBuilder.build()).execute()
        if (!response.isSuccessful) {
            emit(AiChunk.Error("Request failed (${response.code}). Please check your connection or API key."))
            return@flow
        }
        val json = JSONObject(response.body?.string() ?: "")
        val content = json.getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content")
        emit(AiChunk.Token(content))
        emit(AiChunk.Done(0, 0))
    } catch (e: Exception) {
        emit(AiChunk.Error("Connection error: ${e.message}"))
    }
}

// GitHub Copilot — uses GitHub token
class GitHubCopilotProvider(private val config: ProviderConfig, private val client: OkHttpClient) : AiProvider {
    override val id = AiProviderId.OPENAI
    override val models = listOf("gpt-4o", "gpt-4o-mini", "claude-3.5-sonnet")
    override fun chat(model: String, messages: List<ChatMessage>, context: AiContext): Flow<AiChunk> =
        openAiStyleChat(
            messages,
            "https://api.githubcopilot.com/chat/completions",
            model,
            config.apiKey,
            client,
            mapOf("Editor-Version" to "vscode/1.85.0", "Copilot-Integration-Id" to "vscode-chat"),
        )
}

// Nemotron 3B — runs on GitHub Codespace
class NemotronProvider(private val config: ProviderConfig, private val client: OkHttpClient) : AiProvider {
    override val id = AiProviderId.OLLAMA
    override val models = listOf("nemotron-mini-4b-instruct")
    override fun chat(model: String, messages: List<ChatMessage>, context: AiContext): Flow<AiChunk> = flow {
        if (config.baseUrl.isBlank()) {
            emit(AiChunk.Error("Please set your GitHub Codespace URL in Settings."))
            return@flow
        }
        val url = "${config.baseUrl.trimEnd('/')}/v1/chat/completions"
        openAiStyleChat(messages, url, model, config.apiKey, client).collect { emit(it) }
    }
}

// Qwen Small — runs on GitHub Codespace
class QwenProvider(private val config: ProviderConfig, private val client: OkHttpClient) : AiProvider {
    override val id = AiProviderId.OPENROUTER
    override val models = listOf("qwen2.5-coder-1.5b-instruct")
    override fun chat(model: String, messages: List<ChatMessage>, context: AiContext): Flow<AiChunk> = flow {
        if (config.baseUrl.isBlank()) {
            emit(AiChunk.Error("Please set your GitHub Codespace URL in Settings."))
            return@flow
        }
        val url = "${config.baseUrl.trimEnd('/')}/v1/chat/completions"
        openAiStyleChat(messages, url, model, config.apiKey, client).collect { emit(it) }
    }
}
