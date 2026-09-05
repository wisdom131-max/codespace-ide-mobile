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
 * Google Generative Language API - "contents"/"parts" shape, assistant role is "model"
 * not "assistant", system prompt goes in "systemInstruction".
 * Body taken verbatim from the old panel-level callGemini().
 */
class GeminiProvider : ChatProvider {
    override val id = "gemini"
    override val displayName = "Gemini"
    override val defaultModel = "gemini-1.5-flash"
    override val isLocal = false
    override val requiresApiKey = true

    private val http = OkHttpClient()

    override fun isAvailable(tokenStore: SecureTokenStore?): Boolean =
        !tokenStore?.aiKey(id.uppercase()).isNullOrBlank()

    override fun unavailableMessage(): String =
        "No $displayName API key found. Add it in Settings."

    override suspend fun complete(request: ChatRequest): String = withContext(Dispatchers.IO) {
        val apiKey = request.apiKey ?: throw Exception(unavailableMessage())
        val contents = JSONArray()
        val stripped = OpenAiCompatibleTransport.stripSystemMessage(request.convMsgs)
        for (i in 0 until stripped.length()) {
            val m = stripped.getJSONObject(i)
            val role = if (m.optString("role") == "assistant") "model" else "user"
            contents.put(
                JSONObject().put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", m.optString("content"))))
            )
        }
        val body = JSONObject()
            .put("contents", contents)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.systemPrompt))))
            .toString()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" + request.model + ":generateContent?key=" + apiKey
        val resp = http.newCall(
            Request.Builder().url(url).header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType())).build()
        ).execute()
        if (!resp.isSuccessful) throw Exception("Gemini API error (${resp.code}). Check your key in Settings.")
        val json = JSONObject(resp.body?.string() ?: "")
        json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getString("text")
    }
}
