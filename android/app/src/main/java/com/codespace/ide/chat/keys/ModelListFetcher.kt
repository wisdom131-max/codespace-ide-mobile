package com.codespace.ide.chat.keys

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * WP4: GET {baseUrl}/models for the Test Connection action. A test ONLY lists
 * models - it never sends a completion request, so a test can never burn
 * tokens. OpenAI-compatible {"data":[{"id":"model-id"},...]} shape, verified
 * live 2026-10-28 (OpenRouter GET /api/v1/models -> 200 {"data":[{"id":...}]}).
 * Custom headers stay PARKED (security review pending); only Authorization.
 */
object ModelListFetcher {

    sealed class FetchResult {
        data class Models(val ids: List<String>) : FetchResult()
        data class Failed(val httpStatus: Int?, val detail: String) : FetchResult()
    }

    /** Pure parse of an OpenAI-compatible /models body. Test-covered; never throws. */
    fun parseModelsJson(body: String): List<String> {
        return try {
            val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id") ?: continue
                if (id.isNotBlank()) out.add(id)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Plain-words verdict for the UI (no provider jargon, no key material). */
    fun verdictOf(r: FetchResult): String = when (r) {
        is FetchResult.Models ->
            "Connection OK - " + r.ids.size + " models found."
        is FetchResult.Failed ->
            if (r.httpStatus == 401 || r.httpStatus == 403)
                "Reached the endpoint, but the key was rejected (" + r.httpStatus + ")."
            else if (r.httpStatus == 404)
                "Reached the server, but /models was not found - check the base URL."
            else if (r.httpStatus != null)
                "Reached the server, but it answered " + r.httpStatus + "."
            else
                "Could not reach the endpoint: " + (r.detail.take(120))
    }

    suspend fun fetch(baseUrl: String, apiKey: String): FetchResult = withContext(Dispatchers.IO) {
        // Same normalizer the completion request uses, so a pasted full
        // ".../chat/completions" URL still discovers instead of 404ing on
        // ".../chat/completions/models".
        val url = com.codespace.ide.engine.EndpointUrls.models(baseUrl)
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            try {
                if (apiKey.isNotBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey)
                }
                val status = conn.responseCode
                val body = if (status in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
                }
                if (status !in 200..299) {
                    return@withContext FetchResult.Failed(status, body.take(300))
                }
                FetchResult.Models(parseModelsJson(body))
            } finally {
                conn.disconnect()
            }
        } catch (t: Exception) {
            FetchResult.Failed(null, t.message ?: "connection failed")
        }
    }
}
