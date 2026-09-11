package com.codespace.ide.data

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin client for the real backend OAuth Connectors API (backend/src/connectors/ (TypeScript files) on
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

    const val API_BASE = "https://codespace-ide-backend.onrender.com/api/v1"

    data class ConnectorStatus(
        val id: String,
        val name: String,
        val connected: Boolean,
        val configured: Boolean,
        val scope: String?,
        /** Phase 1 (Item 4): 'oauth' = browser consent flow, 'pat' = paste an API token. */
        val authType: String = "oauth",
        val tokenHelpUrl: String? = null,
        val tokenHint: String? = null,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * FIX (Batch H 401): this client bypasses AppModule's auth interceptor, so an
     * expired access token surfaced as a hard 401 (Connectors Hub showed the red
     * UNAUTHORIZED error + fallback rows even while the user looked signed in —
     * the rest of the app silently refreshes, only this client didn't).
     * On HTTP 401, attempt ONE /auth/refresh with the stored refresh token, persist
     * the new pair to SecureTokenStore, and retry the original request.
     * context == null skips refresh (callers without a Context keep old behavior).
     */
    private fun executeWithRefresh(context: Context?, accessToken: String, request: Request): okhttp3.Response {
        val first = client.newCall(request).execute()
        if (first.code != 401 || context == null) return first
        first.close()
        var newAccess: String? = null
        try {
            val store = SecureTokenStore(context)
            val refreshToken = store.refreshToken?.takeIf { it.isNotBlank() } ?: return client.newCall(request).execute()
            val refreshReq = Request.Builder()
                .url("$API_BASE/auth/refresh")
                .post("{\"refreshToken\":\"$refreshToken\"}".toRequestBody(JSON))
                .build()
            client.newCall(refreshReq).execute().use { rr ->
                if (!rr.isSuccessful) return client.newCall(request).execute()
                val o = JSONObject(rr.body?.string().orEmpty())
                val access = o.optString("accessToken").takeIf { it.isNotBlank() }
                val newRefresh = o.optString("refreshToken").takeIf { it.isNotBlank() }
                if (access != null) {
                    store.lastAccessToken = access
                    if (newRefresh != null) store.refreshToken = newRefresh
                    newAccess = access
                }
            }
        } catch (_: Exception) {
            // Refresh failed — fall through and replay with the original (stale) token
        }
        val token = newAccess ?: accessToken
        val retry = request.newBuilder().header("Authorization", "Bearer $token").build()
        return client.newCall(retry).execute()
    }

    /** GET /connectors — status per service (connected/configured/scope). */
    fun fetchStatus(accessToken: String, context: Context? = null): Result<List<ConnectorStatus>> = runCatching {
        val req = Request.Builder()
            .url("$API_BASE/connectors")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        executeWithRefresh(context, accessToken, req).use { resp ->
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
                    scope = if (o.isNull("scope")) null else o.optString("scope"),
                    authType = o.optString("authType", "oauth"),
                    tokenHelpUrl = if (o.isNull("tokenHelpUrl")) null else o.optString("tokenHelpUrl"),
                    tokenHint = if (o.isNull("tokenHint")) null else o.optString("tokenHint"),
                )
            }
        }
    }

    /**
     * GET the absolute OAuth callback URL (code + state) — OAUTH-CALLBACK-FIX
     * (2026-09-10): the in-app WebView used to intercept the callback with
     * shouldOverrideUrlLoading() returning true, which CANCELS the navigation —
     * the backend never received the authorization code, the token exchange never
     * ran, and the Hub row never flipped to Connected even though the consent
     * screen completed. No auth header: the callback is a public endpoint; the
     * signed `state` param identifies the user (minted by the backend in auth-url).
     * Returns the backend's {ok, message} so the Hub can toast real failures
     * instead of silently showing a still-disconnected row.
     */
    fun completeOAuthCallback(callbackUrl: String): Result<String> = runCatching {
        val req = Request.Builder().url(callbackUrl).get().build()
        client.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val ok = runCatching { JSONObject(bodyStr).optBoolean("ok", false) }.getOrNull() ?: resp.isSuccessful
            val msg = runCatching { JSONObject(bodyStr).optString("message") }.getOrNull()
            if (!ok) error(msg?.takeIf { it.isNotBlank() } ?: "OAuth failed (HTTP ${resp.code}): ${bodyStr.take(300)}")
            msg ?: "Connected"
        }
    }

    /** GET /connectors/{service}/auth-url — mint the provider's OAuth consent URL. */
    fun fetchAuthUrl(accessToken: String, service: String, context: Context? = null): Result<String> = runCatching {
        val req = Request.Builder()
            .url("$API_BASE/connectors/$service/auth-url")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        executeWithRefresh(context, accessToken, req).use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(bodyStr).optString("message") }.getOrNull()
                error(msg?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}: ${bodyStr.take(300)}")
            }
            JSONObject(bodyStr).getString("authUrl")
        }
    }

    /** POST /connectors/{service}/token — store a PAT-type connector's API token (encrypted server-side). */
    fun savePat(accessToken: String, service: String, pat: String, context: Context? = null): Result<Unit> = runCatching {
        val payload = JSONObject().put("pat", pat)
        val req = Request.Builder()
            .url("$API_BASE/connectors/$service/token")
            .header("Authorization", "Bearer $accessToken")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        executeWithRefresh(context, accessToken, req).use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(bodyStr).optString("message") }.getOrNull()
                error(msg?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}: ${bodyStr.take(300)}")
            }
        }
    }

    /** DELETE /connectors/{service} — disconnect + best-effort revoke. */
    fun disconnect(accessToken: String, service: String, context: Context? = null): Result<Unit> = runCatching {
        val req = Request.Builder()
            .url("$API_BASE/connectors/$service")
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        executeWithRefresh(context, accessToken, req).use { resp ->
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
        context: Context? = null,
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
        executeWithRefresh(context, accessToken, req).use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${bodyStr.take(2000)}")
            bodyStr
        }
    }
}
