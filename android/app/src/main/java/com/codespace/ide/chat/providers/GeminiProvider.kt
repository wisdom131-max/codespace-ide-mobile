package com.codespace.ide.chat.providers

import com.codespace.ide.chat.ChatProvider
import com.codespace.ide.chat.ChatRequest
import com.codespace.ide.data.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
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
    // FIX (404 regression, corrected 2026-09-06): gemini-1.5-flash shut down, and
    // gemini-2.5-flash is RETIRED TOO - proven by a LIVE on-device API error
    // ("models/gemini-2.5-flash is no longer available to new users"), not docs.
    // The live OpenRouter catalog (checked today, no auth needed) lists
    // google/gemini-3.8-flash as the current flash and contains NO 2.5 entries.
    // gemini-3.8-flash is the corrected default; on-device send is the final live check.
    override val defaultModel = "gemini-3.8-flash"
    override val isLocal = false
    override val supportsAudio = true
    override val requiresApiKey = true

    private val http = OkHttpClient()

    override fun isAvailable(tokenStore: SecureTokenStore?): Boolean =
        com.codespace.ide.chat.ChatKeyPool.hasAnyKey(tokenStore, id)

    override fun unavailableMessage(): String =
        "No $displayName API key found. Add it in Settings."

    /**
     * convMsgs → Gemini "contents" shape (assistant→model role mapping).
     * R8-VISION (2026-09-12, fixed after being missed in the original R8 push —
     * this file's images param was never wired despite being reported shipped):
     * images ride the LAST user message as extra {inline_data:{mime_type, data}}
     * parts (ai.google.dev vision docs shape).
     */
    private fun buildContents(
        convMsgs: JSONArray,
        images: List<com.codespace.ide.chat.ChatRequestImage> = emptyList(),
        audios: List<com.codespace.ide.chat.ChatRequestAudio> = emptyList(),
    ): JSONArray {
        val contents = JSONArray()
        val stripped = OpenAiCompatibleTransport.stripSystemMessage(convMsgs)
        for (i in 0 until stripped.length()) {
            val m = stripped.getJSONObject(i)
            val role = if (m.optString("role") == "assistant") "model" else "user"
            val parts = JSONArray().put(JSONObject().put("text", m.optString("content")))
            if ((images.isNotEmpty() || audios.isNotEmpty()) && i == stripped.length() - 1 && role == "user") {
                for (img in images) {
                    parts.put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject().put("mime_type", img.mimeType).put("data", img.base64),
                        )
                    )
                }
                // R9-AUDIO: Gemini accepts audio as inline_data with an audio mime_type
                for (aud in audios) {
                    parts.put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject().put("mime_type", aud.mimeType).put("data", aud.base64),
                        )
                    )
                }
            }
            contents.put(JSONObject().put("role", role).put("parts", parts))
        }
        return contents
    }

    override suspend fun complete(request: ChatRequest): String = withContext(Dispatchers.IO) {
        val apiKey = request.apiKey ?: throw Exception(unavailableMessage())
        val contents = buildContents(request.convMsgs, request.images, request.audios)
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
        if (!resp.isSuccessful) throw com.codespace.ide.chat.ChatHttpException(resp.code, OpenAiCompatibleTransport.transportErrorParts("Gemini API error", resp).first, OpenAiCompatibleTransport.retryAfterMs(resp))
        val json = JSONObject(resp.body?.string() ?: "")
        json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getString("text")
    }

    /**
     * STREAMING (2026-09-11): streamGenerateContent?alt=sse — each SSE data chunk is a
     * generateContent-shaped response; candidates[0].content.parts[].text are deltas.
     */
    override suspend fun completeStreaming(request: ChatRequest, onDelta: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val apiKey = request.apiKey ?: throw Exception(unavailableMessage())
            val body = JSONObject()
                .put("contents", buildContents(request.convMsgs, request.images, request.audios))
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.systemPrompt))))
                .toString()
            val streamClient = http.newBuilder()
                .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val url = "https://generativelanguage.googleapis.com/v1beta/models/" + request.model + ":streamGenerateContent?alt=sse&key=" + apiKey
            // R1-STOP: hold the Call for cancel-abort (see OpenAiCompatibleTransport)
            val call = streamClient.newCall(
                Request.Builder().url(url).header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
            )
            val resp = call.execute()
            if (!resp.isSuccessful) throw com.codespace.ide.chat.ChatHttpException(resp.code, OpenAiCompatibleTransport.transportErrorParts("Gemini API error", resp).first, OpenAiCompatibleTransport.retryAfterMs(resp))
            val sb = StringBuilder()
            val reader = resp.body?.byteStream()?.bufferedReader()
            try {
                while (true) {
                    // R1-STOP: cancel point — throws on the next line after Stop
                    currentCoroutineContext().ensureActive()
                    val line = reader?.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty()) continue
                    val obj = try { JSONObject(payload) } catch (_: Exception) { continue }
                    obj.optJSONObject("error")?.let { err ->
                        throw Exception("Gemini stream error: " + err.optString("message"))
                    }
                    val parts = obj.optJSONArray("candidates")?.let { c ->
                        if (c.length() == 0) null else c.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                    } ?: continue
                    for (i in 0 until parts.length()) {
                        val t = parts.getJSONObject(i).optString("text")
                        if (t.isNotEmpty()) { sb.append(t); onDelta(t) }
                    }
                }
            } finally {
                try { call.cancel() } catch (_: Exception) { }
                try { reader?.close() } catch (_: Exception) { }
            }
            sb.toString()
        }

    /**
     * TOKEN COUNT (2026-09-11): REAL count via Gemini's :countTokens endpoint —
     * exact for the exact request shape. null on any failure → heuristic fallback.
     */
    override suspend fun countTokens(request: ChatRequest): Int? = withContext(Dispatchers.IO) {
        val apiKey = request.apiKey ?: return@withContext null
        try {
            val body = JSONObject()
                .put("contents", buildContents(request.convMsgs))
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.systemPrompt))))
                .toString()
            val url = "https://generativelanguage.googleapis.com/v1beta/models/" + request.model + ":countTokens?key=" + apiKey
            val resp = http.newCall(
                Request.Builder().url(url).header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
            ).execute()
            if (!resp.isSuccessful) return@withContext null
            JSONObject(resp.body?.string() ?: "").optInt("totalTokens", 0).takeIf { it > 0 }
        } catch (_: Exception) { null }
    }

    /**
     * RICH METADATA (2026-09-11): Gemini's models list carries the REAL inputTokenLimit
     * per model — a live, exact context window for the gauge.
     */
    override suspend fun fetchModelInfos(apiKey: String?): List<com.codespace.ide.chat.ChatModelInfo> =
        withContext(Dispatchers.IO) {
            if (apiKey.isNullOrBlank()) return@withContext emptyList()
            try {
                val resp = http.newCall(
                    Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=50&key=" + apiKey)
                        .get().build()
                ).execute()
                if (!resp.isSuccessful) return@withContext emptyList()
                val arr = JSONObject(resp.body?.string() ?: "").getJSONArray("models")
                val out = ArrayList<com.codespace.ide.chat.ChatModelInfo>(arr.length())
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                    var supportsChat = false
                    for (j in 0 until methods.length()) {
                        if (methods.optString(j) == "generateContent") supportsChat = true
                    }
                    if (!supportsChat) continue
                    val name = m.optString("name").removePrefix("models/")
                    if (name.isEmpty()) continue
                    val limit = m.optInt("inputTokenLimit", 0)
                    out.add(
                        com.codespace.ide.chat.ChatModelInfo(
                            id = name,
                            maxInputTokens = if (limit > 0) limit else null,
                        )
                    )
                }
                out
            } catch (_: Exception) { emptyList() }
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
