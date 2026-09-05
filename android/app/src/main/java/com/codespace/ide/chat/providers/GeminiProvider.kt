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
    // FIX (404 regression): gemini-1.5-flash is shut down (Google retired the 1.5
    // family) - every call returned 404 model-not-found. gemini-2.5-flash is still
    // served on v1beta generateContent; 3.x models appear via fetchModels() if the
    // v1beta endpoint keeps supporting them.
    override val defaultModel = "gemini-2.5-flash"
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
        // FIX (404 regression): include the vendor error body so 404 model-not-found
        // is distinguishable from auth errors (old message always blamed the key).
        if (!resp.isSuccessful) throw Exception(OpenAiCompatibleTransport.transportError("Gemini API error", resp))
        val json = JSONObject(resp.body?.string() ?: "")
        json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getString("text")
    }

    /** Live model list from GET /v1beta/models - generateContent-capable text models only. */
    override suspend fun fetchModels(apiKey: String?): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isNullOrBlank()) return@withContext emptyList()
        try {
            val resp = http.newCall(
                Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=50&key=" + apiKey)
                    .get()
                    .build()
            ).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val arr = JSONObject(resp.body?.string() ?: "").getJSONArray("models")
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                var supportsChat = false
                for (j in 0 until methods.length()) {
                    if (methods.optString(j) == "generateContent") supportsChat = true
                }
                if (!supportsChat) continue
                val name = m.optString("name").removePrefix("models/")
                if (name.isNotEmpty()) out.add(name)
            }
            out
        } catch (_: Exception) { emptyList() }
    }
}
