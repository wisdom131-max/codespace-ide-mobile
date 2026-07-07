package com.codespace.ide.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin client for the real backend OAuth Connectors API (backend/src/connectors/*.ts on
 * Railway). Replaces the old AgentConnectorManager OOB-flow logic, which never worked
 * (Google killed the OOB flow in 2022 and there was never a real code->token exchange).
 *
 * The backend holds client secrets and does the token exchange server-side — this client
 * only ever sends/receives the app's own JWT (from SecureTokenStore.lastAccessToken) and
 * short-lived provider tokens never touch the Android app directly.
 *
 * Deliberately blocking/synchronous (uses OkHttp's execute(), not enqueue()) so it can be
 * called both from suspend Compose code (wrap in withContext(Dispatchers.IO)) and from
 * AgentTools.executeTool(), which is a plain synchronous function already expected to run
 * off the main thread.
 */
object ConnectorsApiClient {

    const val API_BASE = "https://codespace-ide-mobile-production.up.railway.app/api/v1"

    data class ConnectorStatus(
        val id: String,
        val name: String,
        val connected: Boolean,
        val configured: Boolean,
        val scope: String?,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** GET /connectors — status per service (connected/configured/scope). */
    fun fetchStatus(accessToken: String): Result<List<ConnectorStatus>> = runCatching {
        val req = Request.Builder()
            .url("$API_BASE/connectors")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${bodyStr.take(300)}")
            val arr = JSONArray(bodyStr)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ConnectorStatus(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    connected = o.optBoolean("connected", false),
                    configured = o.optBoolean("configured", false),
                    scope = if (o.isNull("scope")) null else o.optString("scope", null),
                )
            }
        }
    }

    /** GET /connectors/{service}/auth-url — mint the provider's OAuth consent URL. */
    fun fetchAuthUrl(accessToken: String, service: String): Result<String> = runCatching {
        val req = Request.Builder()
            .url("$API_BASE/connectors/$service/auth-url")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(bodyStr).optString("message") }.getOrNull()
                error(msg?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}: ${bodyStr.take(300)}")
            }
            JSONObject(bodyStr).getString("authUrl")
        }
    }

    /** DELETE /connectors/{service} — disconnect + best-effort revoke. */
    fun disconnect(accessToken: String, service: String): Result<Unit> = runCatching {
        val req = Request.Builder()
            .url("$API_BASE/connectors/$service")
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(300)}")
        }
    }

    /** POST /connectors/{service}/call — proxy an authenticated API call to the connected service. */
    fun proxyCall(
        accessToken: String,
        service: String,
        method: String,
        path: String,
        body: String?,
    ): Result<String> = runCatching {
        val payload = JSONObject().apply {
            put("method", method.uppercase())
            put("path", path)
            if (!body.isNullOrBlank()) {
                put("body", runCatching { JSONObject(body) }.getOrElse { JSONObject().put("raw", body) })
            }
        }
        val req = Request.Builder()
            .url("$API_BASE/connectors/$service/call")
            .header("Authorization", "Bearer $accessToken")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${bodyStr.take(2000)}")
            bodyStr
        }
    }
}
