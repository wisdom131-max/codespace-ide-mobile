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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic Messages API - x-api-key header, separate "system" field, response content
 * is a list of blocks rather than a single message string.
 * Body taken verbatim from the old panel-level callClaude().
 */
class AnthropicProvider : ChatProvider {
    // FIX (404 regression): claude-3-5-sonnet-20241022 is retired (Anthropic returns
    // 404 not_found_error). claude-sonnet-5 is the current model ID per
    // platform.claude.com/docs/en/models/overview.
    override val id = "claude"
    override val displayName = "Claude"
    override val defaultModel = "claude-sonnet-5"
    override val isLocal = false
    override val requiresApiKey = true

    private val http = OkHttpClient()

    override fun isAvailable(tokenStore: SecureTokenStore?): Boolean =
        !tokenStore?.aiKey(id.uppercase()).isNullOrBlank()

    override fun unavailableMessage(): String =
        "No $displayName API key found. Add it in Settings."

    /**
     * R8-VISION: Anthropic image blocks (docs.claude.com/en/docs/build-with-claude/vision):
     * the LAST user message becomes [{type:"text"}, {type:"image", source:{type:"base64",
     * media_type, data}}]. Images in user messages only (vendor rule).
     */
    private fun buildMessages(convMsgs: JSONArray, images: List<com.codespace.ide.chat.ChatRequestImage>): JSONArray {
        val msgs = OpenAiCompatibleTransport.stripSystemMessage(convMsgs)
        if (images.isEmpty()) return msgs
        for (i in (msgs.length() - 1) downTo 0) {
            val m = msgs.getJSONObject(i)
            if (m.optString("role") != "user") continue
            val parts = JSONArray()
            parts.put(JSONObject().put("type", "text").put("text", m.optString("content")))
            for (img in images) {
                parts.put(
                    JSONObject().put("type", "image").put(
                        "source",
                        JSONObject().put("type", "base64")
                            .put("media_type", img.mimeType)
                            .put("data", img.base64),
                    )
                )
            }
            m.put("content", parts)
            break
        }
        return msgs
    }

    override suspend fun complete(request: ChatRequest): String = withContext(Dispatchers.IO) {
        val apiKey = request.apiKey ?: throw Exception(unavailableMessage())
        val body = JSONObject()
            .put("model", request.model)
            .put("max_tokens", 4096)
            .put("system", request.systemPrompt)
            .put("messages", buildMessages(request.convMsgs, request.images))
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
        // FIX (404 regression): include the vendor error body so 404 model-not-found
        // is distinguishable from auth errors (old message always blamed the key).
        if (!resp.isSuccessful) throw Exception(OpenAiCompatibleTransport.transportError("Claude API error", resp))
        val json = JSONObject(resp.body?.string() ?: "")
        json.getJSONArray("content").getJSONObject(0).getString("text")
    }

    /**
     * STREAMING (2026-09-11): Anthropic SSE — content_block_delta events carry
     * delta.text. Assembles the same full text as complete() and forwards deltas.
     */
    override suspend fun completeStreaming(request: ChatRequest, onDelta: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val apiKey = request.apiKey ?: throw Exception(unavailableMessage())
            val body = JSONObject()
                .put("model", request.model)
                .put("max_tokens", 4096)
                .put("system", request.systemPrompt)
                .put("messages", buildMessages(request.convMsgs, request.images))
                .put("stream", true)
                .toString()
            val streamClient = http.newBuilder()
                .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val resp = streamClient.newCall(
                Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            if (!resp.isSuccessful) throw Exception(OpenAiCompatibleTransport.transportError("Claude API error", resp))
            val sb = StringBuilder()
            val reader = resp.body?.byteStream()?.bufferedReader()
            try {
                while (true) {
                    val line = reader?.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty()) continue
                    val obj = try { JSONObject(payload) } catch (_: Exception) { continue }
                    when (obj.optString("type")) {
                        "content_block_delta" -> {
                            val text = obj.optJSONObject("delta")?.optString("text") ?: ""
                            if (text.isNotEmpty()) { sb.append(text); onDelta(text) }
                        }
                        "error" -> throw Exception("Claude stream error: " + obj.optJSONObject("error")?.optString("message"))
                        "message_stop" -> return@withContext sb.toString()
                    }
                }
            } finally {
                try { reader?.close() } catch (_: Exception) { }
            }
            sb.toString()
        }

    /**
     * TOKEN COUNT (2026-09-11): REAL count via Anthropic's /v1/messages/count_tokens
     * endpoint — exact for the exact request shape. null on any failure → caller
     * falls back to the heuristic estimate.
     */
    override suspend fun countTokens(request: ChatRequest): Int? = withContext(Dispatchers.IO) {
        val apiKey = request.apiKey ?: return@withContext null
        try {
            val body = JSONObject()
                .put("model", request.model)
                .put("system", request.systemPrompt)
                .put("messages", OpenAiCompatibleTransport.stripSystemMessage(request.convMsgs))
                .toString()
            val resp = http.newCall(
                Request.Builder()
                    .url("https://api.anthropic.com/v1/messages/count_tokens")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            if (!resp.isSuccessful) return@withContext null
            JSONObject(resp.body?.string() ?: "").optInt("input_tokens", 0).takeIf { it > 0 }
        } catch (_: Exception) { null }
    }

    /** Live model list from GET /v1/models (Models API). */
    override suspend fun fetchModels(apiKey: String?): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isNullOrBlank()) return@withContext emptyList()
        try {
            val resp = http.newCall(
                Request.Builder()
                    .url("https://api.anthropic.com/v1/models?limit=50")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .get()
                    .build()
            ).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val arr = JSONObject(resp.body?.string() ?: "").getJSONArray("data")
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val id = arr.getJSONObject(i).optString("id")
                if (id.isNotEmpty()) out.add(id)
            }
            out
        } catch (_: Exception) { emptyList() }
    }
}
