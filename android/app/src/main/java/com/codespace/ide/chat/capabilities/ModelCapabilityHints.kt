package com.codespace.ide.chat.capabilities

import org.json.JSONArray
import org.json.JSONObject

/**
 * G-E-a LIVE CAPABILITY HINTS — bounded parser + in-memory store for
 * provider /models metadata (source 2 of the resolution chain).
 *
 * OPENROUTER-STYLE payloads only (the only OpenAI-compatible shape that
 * carries capability data — verified live 2026-10-20: architecture.
 * input_modalities, context_length, supported_parameters). Plain OpenAI
 * /models responses have NOTHING to parse; the G-C refetch leaves those
 * endpoints alone (advisor condition d: nothing is ever claimed for them).
 *
 * UNTRUSTED INPUT (advisor condition d): bounded parsing, string caps, row
 * caps, unknown fields IGNORED, and NO code path anywhere is driven by these
 * flags beyond the capability booleans — a model claiming tool support can
 * NEVER enable tool execution.
 *
 * NOT WIRED in G-E-a: nothing populates the store from the refetch yet —
 * that is G-E-b. The parser + store land now so the wiring is one call.
 */
object ModelCapabilityHints {

    internal const val MAX_MODELS = 500
    internal const val MAX_ID_LEN = 128
    internal const val MAX_PARAM_LEN = 64
    internal const val MAX_CONTEXT = 100_000_000L

    @Volatile private var store: Map<String, Map<String, ModelCapabilityFlags>> = emptyMap()

    /** Replace the hints for one endpoint (called by the G-C refetch in G-E-b). */
    fun replaceForEndpoint(providerId: String, modelIdToFlags: Map<String, ModelCapabilityFlags>) {
        if (providerId.isBlank()) return
        store = store + (providerId.take(MAX_ID_LEN) to
            modelIdToFlags.entries.take(MAX_MODELS).associate { it.key to it.value })
    }

    fun get(providerId: String?, modelId: String?): ModelCapabilityFlags? {
        if (providerId == null || modelId == null) return null
        return store[providerId]?.get(modelId)
    }

    fun clearForTest() { store = emptyMap() }

    /**
     * Bounded parse of an OpenRouter-style /models payload. Returns null for
     * any endpoint whose payload is not that shape (nothing claimed).
     * Poisioned input -> null, never an exception, never a guess.
     */
    fun parseOpenRouterStyle(payload: String): Map<String, ModelCapabilityFlags>? {
        return try {
        val root = JSONObject(payload)
        val arr = root.optJSONArray("data") ?: return null
        val out = LinkedHashMap<String, ModelCapabilityFlags>()
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_MODELS) break
            val m = arr.optJSONObject(i) ?: continue
            val id = m.optString("id", "").take(MAX_ID_LEN)
            if (id.isBlank()) continue
            val arch = m.optJSONObject("architecture")
            val inputModalities = arch?.optJSONArray("input_modalities")
            var images: Boolean? = null
            var pdf: Boolean? = null
            if (inputModalities != null) {
                for (j in 0 until inputModalities.length()) {
                    val mod = inputModalities.optString(j, "").take(MAX_PARAM_LEN)
                    if (mod == "image") images = true
                    if (mod == "file") pdf = true
                }
                // A modality list WITHOUT image is an explicit claim of NO
                // image input — that is real metadata, not UNKNOWN.
                if (images == null && inputModalities.length() > 0) images = false
            }
            var tools: Boolean? = null
            var streaming: Boolean? = null
            m.optJSONArray("supported_parameters")?.let { params ->
                for (j in 0 until params.length()) {
                    val p = params.optString(j, "").take(MAX_PARAM_LEN)
                    if (p == "tools" || p == "tool_choice") tools = true
                    if (p == "stream") streaming = true
                }
            }
            val ctx = try {
                m.getLong("context_length").coerceIn(1L, MAX_CONTEXT)
            } catch (_: Exception) { null }
            out[id] = ModelCapabilityFlags(
                acceptsImages = images,
                acceptsPdf = pdf,
                supportsTools = tools,
                supportsStreaming = streaming,
                maxContextTokens = ctx,
            )
        }
        out
        } catch (_: Exception) {
            null
        }
    }
