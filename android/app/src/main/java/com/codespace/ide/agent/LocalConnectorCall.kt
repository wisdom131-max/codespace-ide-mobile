package com.codespace.ide.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * One HTTP call against Firebase, Supabase or n8n, built from credentials stored on the device.
 *
 * The backend proxy cannot serve these three: it holds one fixed `apiBase` per connector, and
 * these addresses are the user's own (a Supabase project URL, a self-hosted n8n instance, a
 * Firebase project id). So they are called directly from the app, with the same shape the proxy
 * gives the other connectors: a method, a path relative to that base, and a JSON body.
 *
 * The request build is a pure function so it can be asserted without a network: each of the three
 * puts its credential somewhere different, and getting it wrong fails silently as a 401.
 */
object LocalConnectorCall {

    data class Spec(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * @param base the service base URL, already resolved by LocalConnectorStore
     * @param key the credential for that service
     * @param path relative to base, e.g. "/rest/v1/todos", or absolute when it starts with http
     */
    fun build(service: String, base: String, key: String, method: String, path: String, body: String?): Spec {
        val trimmedBase = base.trim().trimEnd('/')
        val url = if (path.startsWith("http")) path else "$trimmedBase${if (path.startsWith("/")) path else "/$path"}"
        val headers = mutableMapOf("Accept" to "application/json")
        if (!body.isNullOrBlank()) headers["Content-Type"] = "application/json"
        when (service.lowercase()) {
            // Supabase wants the key twice: apikey for the gateway, Bearer for row-level security.
            "supabase" -> {
                headers["apikey"] = key
                headers["Authorization"] = "Bearer $key"
            }
            // n8n uses its own header rather than a bearer token.
            "n8n" -> headers["X-N8N-API-KEY"] = key
            // Firebase v1 REST accepts the web API key as a header.
            "firebase" -> headers["X-Goog-Api-Key"] = key
            else -> headers["Authorization"] = "Bearer $key"
        }
        return Spec(method.uppercase(), url, headers, body?.takeIf { it.isNotBlank() })
    }

    /** Runs the call, returning the body on success and the status line plus body on failure. */
    fun execute(spec: Spec): String = try {
        val builder = Request.Builder().url(spec.url)
        spec.headers.forEach { (name, value) -> builder.header(name, value) }
        if (spec.method == "GET" || spec.method == "HEAD") {
            builder.method(spec.method, null)
        } else {
            builder.method(spec.method, (spec.body ?: "{}").toRequestBody("application/json".toMediaType()))
        }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val shown = if (text.length > 6000) text.take(6000) + "..." else text
            if (resp.isSuccessful) {
                shown
            } else {
                "HTTP " + resp.code + " for " + spec.method + " " + spec.url + ": " +
                    shown.ifBlank { "(empty body)" }
            }
        }
    } catch (e: Exception) {
        "Local connector call failed: ${e.message}"
    }
}
