package com.codespace.ide.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Advisor item 4 (2026-10-10, "Hugging Face key check"): an in-app "Test this
 * key" action for custom endpoints. The owner created several NEW keys and still
 * got 402s — because HF credits belong to the ACCOUNT, not the key. This probe
 * makes that visible IN the app, next to each key slot:
 *
 *  - a MINIMAL request (GET <baseUrl>/models — the lightest standard probe) with
 *    THAT slot's key, so the owner tests the exact key, not the active one;
 *  - the HTTP status code + the vendor's own message (redacted through the same
 *    C12 attachment-tier patterns before display);
 *  - for huggingface hosts (huggingface.co / hf.co), the ACCOUNT NAME via HF's
 *    whoami-v2 — the single fact that explains "new key, same 402": same account.
 *
 * SECURITY: the key is used ONLY as a request header here. It is never stored
 * outside SecureTokenStore, never logged, and never appears in [Result] — the
 * result carries only the status, the redacted vendor message, and the account
 * name. whoami-v2 returns no secrets.
 */
object KeyProbe {

    data class Result(
        /** HTTP status of the probe request; null when the host was unreachable. */
        val httpStatus: Int?,
        /** Redacted, display-ready verdict line. */
        val message: String,
        /** HF-family accounts only: the account name whoami-v2 reports. */
        val accountName: String?,
    )

    private val http = OkHttpClient()

    fun isHuggingFaceHost(baseUrl: String): Boolean {
        // String check — no URL-parse API risk; endpoint URLs are user-entered
        // and both HF router shapes contain the host in the raw string.
        val u = baseUrl.lowercase()
        return u.contains("huggingface.co") || u.contains("hf.co")
    }

    suspend fun probe(baseUrl: String, apiKey: String): Result = withContext(Dispatchers.IO) {
        val url = baseUrl.trim().trimEnd('/') + "/models"
        val req = try {
            Request.Builder().url(url).header("Authorization", "Bearer $apiKey").get().build()
        } catch (_: Exception) {
            return@withContext Result(null, "invalid endpoint URL", null)
        }
        try {
            http.newCall(req).execute().use { resp ->
                val code = resp.code
                val verdict = if (resp.isSuccessful) {
                    "HTTP $code — key accepted, endpoint reachable"
                } else {
                    try {
                        val he = com.codespace.ide.chat.providers.OpenAiCompatibleTransport
                            .classifyHttpError(resp, "Key test failed")
                        "HTTP $code — " + he.message
                    } catch (_: Exception) {
                        "HTTP $code"
                    }
                }
                val account = if (isHuggingFaceHost(baseUrl)) whoami(apiKey) else null
                val redacted = AttachmentSecrets.redact(verdict, AttachmentSecrets.TIER_ATTACHMENT).first
                Result(code, redacted, account)
            }
        } catch (e: Exception) {
            Result(null, "unreachable — " + (e.message ?: e.javaClass.simpleName), null)
        }
    }

    /** HF account name via whoami-v2; null on any failure (never a hard error). */
    private suspend fun whoami(apiKey: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://huggingface.co/api/whoami-v2")
                .header("Authorization", "Bearer $apiKey")
                .get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val name = org.json.JSONObject(resp.body?.string() ?: "").optString("name")
                name.ifBlank { null }
            }
        } catch (_: Exception) { null }
    }
}
