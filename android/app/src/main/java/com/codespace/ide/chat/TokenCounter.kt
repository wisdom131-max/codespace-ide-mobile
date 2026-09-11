package com.codespace.ide.chat

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingRegistry
import com.knuddels.jtokkit.api.EncodingType
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * TOKEN COUNTER (2026-09-11, VS Code lm-parity round) — the shared token math
 * behind the chat panels' running context gauge.
 *
 * REAL tokenizer where one exists:
 *   - OpenAI-family (openai/deepseek/xai/custom/openrouter): jtokkit BPE —
 *     exact for OpenAI's o200k/cl100k models, close proxy for other vendors'
 *     OpenAI-compatible tokenizers (DeepSeek/xAI are BPE-derived and within a
 *     few percent of cl100k/o200k on natural text).
 *   - Gemini + Anthropic implement ChatProvider.countTokens against their own
 *     real count endpoints — this object is their FALLBACK only.
 * Heuristic fallback (no tokenizer available / jtokkit init failed): chars/4 —
 * the standard rough estimate, clearly an approximation and labeled as such.
 *
 * Also owns the model context-limit cache: real vendor values via
 * ChatProvider.fetchModelInfos (OpenRouter context_length, Gemini inputTokenLimit),
 * static documented table where the vendor's /models doesn't report it.
 */
object TokenCounter {

    @Volatile private var registry: EncodingRegistry? = null
    @Volatile private var jtokkitFailed = false

    /** BPE encoding for the model family; null = jtokkit unavailable → heuristic. */
    private fun encodingFor(model: String): Encoding? {
        if (jtokkitFailed) return null
        if (registry == null) {
            registry = try { Encodings.newDefaultEncodingRegistry() } catch (_: Throwable) {
                jtokkitFailed = true; null
            }
        }
        val reg = registry ?: return null
        // o200k_base covers GPT-4o and everything newer (GPT-5 series, o-series);
        // grok is BPE-derived and closest to o200k. Older/unknown → cl100k.
        val type = if (
            model.contains("gpt-4o") || model.contains("gpt-4.1") || model.startsWith("gpt-5") ||
            model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4") ||
            model.startsWith("chatgpt") || model.contains("grok")
        ) EncodingType.O200K_BASE else EncodingType.CL100K_BASE
        return try { reg.getEncoding(type) } catch (_: Throwable) { null }
    }

    /** Real BPE count of one text blob for the model; heuristic fallback. */
    fun count(text: String, model: String): Int {
        val enc = encodingFor(model) ?: return heuristic(text)
        return try { enc.encode(text).size } catch (_: Throwable) { heuristic(text) }
    }

    /** Rough estimate when no real tokenizer exists: ~4 chars per token. */
    fun heuristic(text: String): Int = (text.length / 4).coerceAtLeast(1)

    /**
     * Approximate count of a full chat request (system + all messages) for
     * OpenAI-family providers. Role markers add a few tokens per message that BPE
     * on the joined text doesn't see — documented approximation (~2% on chat-sized
     * conversations). Gemini/Anthropic use their exact count endpoints instead.
     */
    fun countOpenAiCompatible(systemPrompt: String, convMsgs: JSONArray, model: String): Int? {
        val sb = StringBuilder(systemPrompt)
        for (i in 0 until convMsgs.length()) {
            val m = convMsgs.getJSONObject(i)
            sb.append('\n').append(m.optString("role")).append(": ")
                .append(m.optString("content"))
        }
        return count(sb.toString(), model)
    }

    // ── Model context-limit lookup (real vendor value → static table → null) ──

    /** Memoized per "providerId:model" for the app run; Optional so misses are cached too. */
    private val limitCache = ConcurrentHashMap<String, Optional<Int>>()

    /**
     * Real input context window for the model: provider fetchModelInfos first
     * (OpenRouter reports context_length, Gemini reports inputTokenLimit), static
     * documented vendor table as fallback. null = unknown → gauge shows "limit unknown".
     */
    suspend fun modelContextLimit(provider: ChatProvider?, model: String, apiKey: String?): Int? {
        if (provider == null) return null
        val cacheKey = provider.id + ":" + model
        limitCache[cacheKey]?.let { return it.orElse(null) }
        val live = try {
            withTimeoutOrNull(5000) { provider.fetchModelInfos(apiKey) }
                ?.firstOrNull { it.id == model }?.maxInputTokens
        } catch (_: Exception) { null }
        val resolved = live ?: staticContextLimit(provider.id, model)
        limitCache[cacheKey] = Optional.ofNullable(resolved)
        return resolved
    }

    /**
     * Documented vendor context windows for the providers whose /models endpoints
     * don't report limits. Deliberately only well-established values; anything
     * unlisted returns null (gauge: "limit unknown") rather than a guess.
     */
    fun staticContextLimit(providerId: String, model: String): Int? = when {
        providerId == "openai" && (model.startsWith("gpt-4") || model.startsWith("gpt-5")) -> 128_000
        providerId == "claude" && model.startsWith("claude-") -> 200_000
        providerId == "xai" && model.startsWith("grok") -> 131_000
        providerId == "deepseek" -> 128_000
        else -> null
    }
}
