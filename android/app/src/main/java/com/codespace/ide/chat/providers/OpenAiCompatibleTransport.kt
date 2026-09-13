package com.codespace.ide.chat.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    internal suspend fun call(
        url: String, apiKey: String, model: String, convMsgs: JSONArray,
        images: List<com.codespace.ide.chat.ChatRequestImage> = emptyList(),
        audios: List<com.codespace.ide.chat.ChatRequestAudio> = emptyList(),
    ): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("model", model).put("messages", withImages(convMsgs, images, audios)).toString()
            val resp = http.newCall(
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(jsonMedia))
                    .build()
            ).execute()
            if (!resp.isSuccessful) {
                throw com.codespace.ide.chat.ChatHttpException(resp.code, transportErrorParts("API error", resp).first, retryAfterMs(resp))
            }
            val json = JSONObject(resp.body?.string() ?: "")
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }

    /**
     * R8-VISION (OpenAI-family multimodal, one shape for the whole family):
     * when images are present, the LAST user message's content becomes an array
     * of {type:"text"} + {type:"image_url"} blocks with data: URLs. Vendor docs
     * all specify this exact part shape: OpenAI (platform.openai.com — Chat
     * Completions vision, data URLs), xAI (docs.x.ai — same chat/completions
     * contract), DeepSeek (api-docs.deepseek.com/guides/vision — "standard
     * OpenAI-compatible Chat Completions format"), OpenRouter (openrouter.ai
     * docs — image_url parts). Images in user messages only (vendor rule).
     */
    internal fun withImages(
        convMsgs: JSONArray,
        images: List<com.codespace.ide.chat.ChatRequestImage>,
        audios: List<com.codespace.ide.chat.ChatRequestAudio> = emptyList(),
    ): JSONArray {
        if (images.isEmpty() && audios.isEmpty()) return convMsgs
        val out = JSONArray()
        for (i in 0 until convMsgs.length()) out.put(convMsgs.get(i))
        for (i in (out.length() - 1) downTo 0) {
            val m = out.optJSONObject(i) ?: continue
            if (m.optString("role") != "user") continue
            val parts = JSONArray()
            parts.put(JSONObject().put("type", "text").put("text", m.optString("content")))
            for (img in images) {
                parts.put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:" + img.mimeType + ";base64," + img.base64),
                    )
                )
            }
            // R9-AUDIO: OpenAI input_audio part shape (platform.openai.com docs)
            for (aud in audios) {
                parts.put(
                    JSONObject().put("type", "input_audio").put(
                        "input_audio",
                        JSONObject().put("data", aud.base64).put("format", aud.format),
                    )
                )
            }
            m.put("content", parts)
            break
        }
        return out
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
        images: List<com.codespace.ide.chat.ChatRequestImage> = emptyList(),
        audios: List<com.codespace.ide.chat.ChatRequestAudio> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("model", model).put("messages", withImages(convMsgs, images, audios)).put("stream", true).toString()
        val streamClient = http.newBuilder()
            .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        // R1-STOP: hold the Call so a cancelled coroutine aborts the socket read
        // immediately instead of waiting out the 180s read timeout.
        val call = streamClient.newCall(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(jsonMedia))
                .build()
        )
        val resp = call.execute()
        if (!resp.isSuccessful) {
            throw com.codespace.ide.chat.ChatHttpException(resp.code, transportErrorParts("API error", resp).first, retryAfterMs(resp))
        }
        val sb = StringBuilder()
        val reader = resp.body?.byteStream()?.bufferedReader()
        try {
            while (true) {
                // R1-STOP: cancel point — Stop button cancels the chat job, this
                // throws on the very next line read, finally cancels the call.
                currentCoroutineContext().ensureActive()
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
            // R1-STOP: no-op on normal completion; aborts a stalled read on cancel
            try { call.cancel() } catch (_: Exception) { }
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
    /**
     * CUSTOM-ENDPOINT-FIX (c): parse the vendor error body into a CLEAN one-line
     * message (error.message / message fields — the OpenAI-family error shape),
     * falling back to the raw snippet. Returns (clean, raw) — the raw body rides
     * behind a RAW_RESPONSE marker so error UIs can tuck it behind an expand.
     */
    internal fun transportErrorParts(prefix: String, resp: okhttp3.Response): Pair<String, String> {
        val body = try { resp.body?.string() } catch (_: Exception) { null }
        val clean = try {
            val obj = JSONObject(body ?: "")
            val msg = obj.optJSONObject("error")?.optString("message")
                ?: obj.optString("message")
            if (msg.isNotBlank()) prefix + " (" + resp.code + "): " + msg else ""
        } catch (_: Exception) { "" }
        val fallback = prefix + " (" + resp.code + ")." +
            (if (body.isNullOrBlank()) "" else " " + body.take(160).replace("\n", " "))
        return (if (clean.isNotEmpty()) clean else fallback) to (body?.take(400) ?: "")
    }

    /** Clean vendor error + raw body behind a RAW_RESPONSE block (ChatErrorBubble expands it). */
    internal fun transportError(prefix: String, resp: okhttp3.Response): String {
        val (clean, raw) = transportErrorParts(prefix, resp)
        if (raw.isBlank()) return clean
        return clean + "\n\nRAW_RESPONSE_BEGIN\n" + raw + "\nRAW_RESPONSE_END"
    }

    /**
     * MULTI-KEY: Retry-After header in ms (429 responses), capped at 30s so a
     * hostile/buggy server header cannot hang the failover engine. null = none.
     */
    internal fun retryAfterMs(resp: okhttp3.Response): Long? {
        val raw = try { resp.header("Retry-After") } catch (_: Exception) { null } ?: return null
        val seconds = raw.trim().toLongOrNull() ?: return null
        return (seconds * 1000L).coerceIn(0L, 30_000L)
    }

    /** Strips the RAW_RESPONSE block for single-line error surfaces (input error Text). */
    internal fun stripRawError(message: String): String =
        message.substringBefore("\nRAW_RESPONSE_BEGIN").trim()

    /**
     * Shared OpenAI-compatible GET /models lister (OpenAI, DeepSeek, OpenRouter, xAI, Custom).
     * CUSTOM-ENDPOINT-FIX (b): FAILS LOUD — an HTTP error or a malformed body now
     * THROWS with the parsed vendor message instead of silently returning an
     * empty list (which made the placeholder "custom-model" look like the only
     * real model). Callers that want soft-failure catch it themselves.
     */
    internal suspend fun fetchModelList(url: String, apiKey: String, bearer: Boolean = true): List<String> =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
            if (bearer) builder.header("Authorization", "Bearer $apiKey")
            val resp = http.newCall(builder.get().build()).execute()
            if (!resp.isSuccessful) {
                throw com.codespace.ide.chat.ChatHttpException(resp.code, transportErrorParts("Model list fetch failed", resp).first, retryAfterMs(resp))
            }
            val bodyText = resp.body?.string() ?: throw Exception("Model list fetch failed: empty response body.")
            val arr = try { JSONObject(bodyText).getJSONArray("data") } catch (_: Exception) {
                throw Exception("Model list fetch failed: response was not an OpenAI /models JSON payload.")
            }
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val id = arr.getJSONObject(i).optString("id")
                if (id.isNotEmpty()) out.add(id)
            }
            out
        }
}
