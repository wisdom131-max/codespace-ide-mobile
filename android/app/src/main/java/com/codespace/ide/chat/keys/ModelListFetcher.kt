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

    /**
     * PHASE 1 (2026-11-17): does this base URL point at a Cloudflare Workers AI inference path?
     *
     * Cloudflare is NOT OpenAI-shaped. `.../client/v4/accounts/<id>/ai/run/@cf/<model>` accepts POST
     * only, so prefixing "/models" and asking with GET earns a 405 - which is exactly the report
     * that started this: "Reached the server, but it answered 405". A reachable server that refuses
     * the method is not a working endpoint, and the old wording hid that.
     */
    fun isCloudflareAi(baseUrl: String): Boolean {
        val u = baseUrl.trim().lowercase()
        return u.contains("api.cloudflare.com/client/v4/accounts/") && u.contains("/ai/")
    }

    /**
     * The catalogue URL for a Cloudflare AI base, or null when the account id is not visible.
     * Workers AI publishes its models at `/ai/models/search` - a GET, so a test still cannot burn
     * tokens.
     */
    fun cloudflareModelsUrl(baseUrl: String): String? {
        val m = Regex("^(https?://api\\.cloudflare\\.com/client/v4/accounts/[^/]+/ai)(/.*)?$",
            RegexOption.IGNORE_CASE).find(baseUrl.trim()) ?: return null
        return m.groupValues[1] + "/models/search"
    }

    /** Pure parse of an OpenAI-compatible /models body. Test-covered; never throws. */
    fun parseModelsJson(body: String): List<String> {
        return try {
            val root = JSONObject(body)
            // Cloudflare Workers AI answers {"result":[{"name":"@cf/..."}],"success":true}.
            root.optJSONArray("result")?.let { arr ->
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("name").ifBlank { o.optString("id") }
                    if (id.isNotBlank()) out.add(id)
                }
                if (out.isNotEmpty()) return out
            }
            val arr = root.optJSONArray("data") ?: return emptyList()
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
            else if (r.httpStatus == 405)
                "Reached the server, but it refuses this method (405). " +
                    "A POST-only endpoint (such as Cloudflare AI /ai/run/<model>) cannot answer a model " +
                    "list. Use the account's /ai/models/search URL, or paste the Chat Completions URL."
            else if (r.httpStatus != null)
                "Reached the server, but it answered " + r.httpStatus + "."
            else
                "Could not reach the endpoint: " + (r.detail.take(120))
    }

    suspend fun fetch(baseUrl: String, apiKey: String): FetchResult = withContext(Dispatchers.IO) {
        // Same normalizer the completion request uses, so a pasted full
        // ".../chat/completions" URL still discovers instead of 404ing on
        // ".../chat/completions/models".
        // Cloudflare's inference path has no sibling /models; ask its own catalogue instead.
        val url = if (isCloudflareAi(baseUrl))
            cloudflareModelsUrl(baseUrl) ?: com.codespace.ide.engine.EndpointUrls.models(baseUrl)
        else com.codespace.ide.engine.EndpointUrls.models(baseUrl)
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
