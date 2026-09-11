package com.codespace.ide.chat.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared OpenAI-compatible transport — one HTTP shape covers OpenAI, DeepSeek, and
 * OpenRouter (and later any OpenAI-speaking local server, e.g. LM Studio/llama.cpp).
 * Taken verbatim from the old panel-level callOpenAiCompatible(); per-provider
 * differences are only the URL and the key.
 */
internal object OpenAiCompatibleTransport {
    private val http = OkHttpClient()
    private val jsonMedia = "application/json".toMediaType()

    /** convMsgs minus the leading system entry — for APIs that take the system prompt separately. */
    internal fun stripSystemMessage(convMsgs: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until convMsgs.length()) {
            val m = convMsgs.getJSONObject(i)
            if (m.optString("role") != "system") out.put(m)
        }
        return out
    }

    internal suspend fun call(url: String, apiKey: String, model: String, convMsgs: JSONArray): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("model", model).put("messages", convMsgs).toString()
            val resp = http.newCall(
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(jsonMedia))
                    .build()
            ).execute()
            if (!resp.isSuccessful) throw Exception(transportError("API error", resp))
            val json = JSONObject(resp.body?.string() ?: "")
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }

    /**
     * RICH METADATA (2026-09-11): GET /models parsed into ChatModelInfo. Carries the
     * REAL context window where the vendor reports it (OpenRouter "context_length");
     * other vendors fall back to id-only entries (context null).
     */
    internal suspend fun fetchModelInfos(url: String, apiKey: String = "", bearer: Boolean = true): List<com.codespace.ide.chat.ChatModelInfo> =
        withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(url)
                if (bearer) builder.header("Authorization", "Bearer $apiKey")
                val resp = http.newCall(builder.get().build()).execute()
                if (!resp.isSuccessful) return@withContext emptyList()
                val arr = JSONObject(resp.body?.string() ?: "").getJSONArray("data")
                val out = ArrayList<com.codespace.ide.chat.ChatModelInfo>(arr.length())
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val id = m.optString("id")
                    if (id.isEmpty()) continue
                    val ctx = m.optInt("context_length", 0)
                    out.add(
                        com.codespace.ide.chat.ChatModelInfo(
                            id = id,
                            displayName = m.optString("name").ifEmpty { id },
                            maxInputTokens = if (ctx > 0) ctx else null,
                        )
                    )
                }
                out
            } catch (_: Exception) { emptyList() }
        }

    /**
     * STREAMING call (2026-09-11): SSE chat/completions with "stream": true.
     * Same contract as call() — returns the FULL assembled text — but forwards
     * each content delta to onDelta as it arrives. Covers OpenAI, DeepSeek,
     * OpenRouter, xAI, and the Custom Endpoint in one implementation.
     * Long read timeout: deltas can pause >10s between chunks on long generations.
     */
    internal suspend fun callStreaming(
        url: String,
        apiKey: String,
        model: String,
        convMsgs: JSONArray,
        onDelta: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("model", model).put("messages", convMsgs).put("stream", true).toString()
        val streamClient = http.newBuilder()
            .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val resp = streamClient.newCall(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(jsonMedia))
                .build()
        ).execute()
        if (!resp.isSuccessful) throw Exception(transportError("API error", resp))
        val sb = StringBuilder()
        val reader = resp.body?.byteStream()?.bufferedReader()
        try {
            while (true) {
                val line = reader?.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val obj = try { JSONObject(payload) } catch (_: Exception) { continue }
                obj.optJSONObject("error")?.let { err ->
                    throw Exception("API stream error: " + err.optString("message"))
                }
                val choices = obj.optJSONArray("choices") ?: continue
                if (choices.length() == 0) continue  // trailing usage chunks
                val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                val text = delta.optString("content")
                if (text.isNotEmpty()) { sb.append(text); onDelta(text) }
            }
        } finally {
            try { reader?.close() } catch (_: Exception) { }
        }
        sb.toString()
    }

    /**
     * FIX (404 regression): the old message said "Check your key" for EVERY error code,
     * which sent the user down the wrong path - the actual on-device failures were 404
     * model-not-found (retired default model IDs), not auth. The vendor's error body is
     * now included so the panel shows the real reason.
     */
    internal fun transportError(prefix: String, resp: okhttp3.Response): String {
        val body = try { resp.body?.string() } catch (_: Exception) { null }
        val snippet = if (body.isNullOrBlank()) "" else " " + body.take(160).replace("\n", " ")
        return prefix + " (" + resp.code + ")." + snippet
    }

    /** Shared OpenAI-compatible GET /models lister (OpenAI, DeepSeek, OpenRouter). */
    internal suspend fun fetchModelList(url: String, apiKey: String, bearer: Boolean = true): List<String> =
        withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(url)
                if (bearer) builder.header("Authorization", "Bearer $apiKey")
                val resp = http.newCall(builder.get().build()).execute()
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
