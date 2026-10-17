package com.codespace.ide.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * BATCH5-P1 (advisor-approved 2026-10-17): "key accepted" only when an
 * AUTHENTICATED call proves the key. Root cause of the false positive: the
 * probe's ONLY call was GET <baseUrl>/models, and the HF router serves that
 * list PUBLICLY — a garbage key got HTTP 200 + ~80 models, the UI said "key
 * accepted", and the real chat call then failed with 401. The fix:
 *
 *  - HF hosts (huggingface.co / hf.co): whoami-v2 is the proof. 200+name ->
 *    KEY ACCEPTED (account name shown, HF only). 401/403 -> KEY REJECTED.
 *    Other whoami failures -> KEY NOT VERIFIED (honest fallback).
 *  - Non-HF custom endpoints: an ANONYMOUS control call to the same
 *    /models URL (no Authorization header). If the control gets 401/403 the
 *    endpoint enforces auth, so the keyed 200 DID prove the key ->
 *    KEY ACCEPTED. If the control also succeeds, the model list is public
 *    -> "endpoint reachable — KEY NOT VERIFIED". Anything else -> the
 *    conservative NOT VERIFIED wording.
 *  - Keyed 401/403 -> KEY REJECTED (shared classify verdict for detail).
 *
 * The three verdicts are visibly distinct: "KEY ACCEPTED", "KEY REJECTED",
 * "KEY NOT VERIFIED". SECURITY: the key value is used ONLY as a request
 * header, never logged, never in [Result]; the account name is returned only
 * for HF-family hosts; output goes through AttachmentSecrets redaction.
 */
object KeyProbe {

    data class Result(
        /** HTTP status of the keyed probe request; null when the host was unreachable. */
        val httpStatus: Int?,
        /** Redacted, display-ready verdict line. */
        val message: String,
        /** HF-family accounts only: the account name whoami-v2 reports. */
        val accountName: String?,
    )

    /**
     * Test seam: one GET per call. [verdict] carries the SHARED
     * classifyHttpError message for non-2xx responses in the real transport
     * (so classification logic is not duplicated here); tests supply their
     * own. [body] is read only where a name must be parsed (HF whoami).
     */
    internal data class HttpGetOutcome(
        /** null = transport exception / unreachable. */
        val code: Int?,
        val body: String? = null,
        /** Non-2xx classified verdict from the real transport; null on 2xx. */
        val verdict: String? = null,
    ) {
        val ok: Boolean get() = code != null && code in 200..299
    }

    internal fun interface HttpGet {
        /** bearer == null means an ANONYMOUS call (no Authorization header). */
        suspend fun get(url: String, bearer: String?): HttpGetOutcome
    }

    private val http = OkHttpClient()

    /** The real transport: OkHttp GET; classification via the shared classifier. */
    internal val realHttpGet: HttpGet = HttpGet { url, bearer ->
        withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(url).get()
                if (bearer != null) builder.header("Authorization", "Bearer $bearer")
                http.newCall(builder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        HttpGetOutcome(resp.code)
                    } else {
                        // Single request: classifyHttpError reads the error body
                        // through the transport's own redaction, once.
                        val verdict = try {
                            com.codespace.ide.chat.providers.OpenAiCompatibleTransport
                                .classifyHttpError(resp, "Key test failed").message
                        } catch (_: Exception) {
                            "Key test failed (HTTP " + resp.code + ")"
                        }
                        HttpGetOutcome(resp.code, verdict = verdict)
                    }
                }
            } catch (_: Exception) {
                HttpGetOutcome(null)
            }
        }
    }

    fun isHuggingFaceHost(baseUrl: String): Boolean {
        // String check — no URL-parse API risk; endpoint URLs are user-entered
        // and both HF router shapes contain the host in the raw string.
        val u = baseUrl.lowercase()
        return u.contains("huggingface.co") || u.contains("hf.co")
    }

    /**
     * Proves (or fails to prove) the key. [httpGet] is the test seam; production
     * callers use the default real transport.
     */
    suspend fun probe(baseUrl: String, apiKey: String, httpGet: HttpGet = realHttpGet): Result = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty() || !(base.startsWith("http://") || base.startsWith("https://"))) {
            return@withContext Result(null, "invalid endpoint URL", null)
        }
        val modelsUrl = base + "/models"
        val keyed = httpGet.get(modelsUrl, apiKey)
        if (keyed.code == null) {
            return@withContext Result(null, "unreachable — no response from " + base, null)
        }
        if (!keyed.ok) {
            val rejected = keyed.code == 401 || keyed.code == 403
            val verdict = if (rejected) {
                "KEY REJECTED — " + (keyed.verdict ?: "HTTP " + keyed.code)
            } else {
                keyed.verdict ?: ("key test failed (HTTP " + keyed.code + ")")
            }
            val redacted = AttachmentSecrets.redact(verdict, AttachmentSecrets.TIER_ATTACHMENT).first
            return@withContext Result(keyed.code, redacted, null)
        }
        // Keyed call succeeded — but a public model list proves nothing.
        if (isHuggingFaceHost(baseUrl)) {
            val who = httpGet.get("https://huggingface.co/api/whoami-v2", apiKey)
            val outcome = when {
                who.ok -> {
                    val name = who.body?.let { b ->
                        try { org.json.JSONObject(b).optString("name") } catch (_: Exception) { "" }
                    } ?: ""
                    val redacted = AttachmentSecrets.redact(
                        "KEY ACCEPTED — HF account confirmed", AttachmentSecrets.TIER_ATTACHMENT).first
                    Result(keyed.code, redacted, name.ifBlank { null })
                }
                who.code == 401 || who.code == 403 -> Result(
                    keyed.code,
                    AttachmentSecrets.redact(
                        "KEY REJECTED — HF whoami returned " + who.code,
                        AttachmentSecrets.TIER_ATTACHMENT).first,
                    null)
                else -> Result(
                    keyed.code,
                    AttachmentSecrets.redact(
                        "endpoint reachable — KEY NOT VERIFIED (whoami returned " + who.code + ")",
                        AttachmentSecrets.TIER_ATTACHMENT).first,
                    null)
            }
            return@withContext outcome
        }
        // Non-HF: anonymous control call to the SAME URL.
        val anon = httpGet.get(modelsUrl, null)
        val verdict = when {
            anon.code == 401 || anon.code == 403 ->
                "KEY ACCEPTED — endpoint requires auth and the key passed"
            anon.ok ->
                "endpoint reachable — KEY NOT VERIFIED (model list is public)"
            else ->
                "endpoint reachable — KEY NOT VERIFIED (control call returned " + anon.code + ")"
        }
        val redacted = AttachmentSecrets.redact(verdict, AttachmentSecrets.TIER_ATTACHMENT).first
        Result(keyed.code, redacted, null)
    }
}
