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
