package com.codespace.ide.chat

import com.codespace.ide.data.SecureTokenStore
import org.json.JSONArray

/**
 * ChatProvider — the ONE registration interface every AI chat provider implements,
 * whether it is a cloud API (OpenAI, Anthropic, Gemini, DeepSeek, OpenRouter) or a
 * local model server (Ollama, LM Studio, llama.cpp, ...).
 *
 * Modeled on VS Code's real architecture: the host (chat panel / Settings / model
 * picker) NEVER hardcodes a provider — not an enum, not a prefix set, not a
 * when-branch. Each provider is a self-contained registration unit that knows its
 * own endpoint, auth, request/response shape, and model list. Registering a new
 * provider = one new file implementing this interface + one register() call in
 * ProviderBootstrap. Zero changes to panel/Settings code.
 *
 * CREDENTIAL CONTRACT (matches VS Code's single-SecretStorage pattern):
 *   - SecureTokenStore (EncryptedSharedPreferences + Android Keystore) is the ONE
 *     storage primitive. Providers must NOT invent their own storage.
 *   - A requiresApiKey provider's key lives under "ai_" + id.uppercase()
 *     (e.g. id "openai" -> aiKey("OPENAI")) — the exact keys Settings already
 *     writes, so existing saved keys keep working untouched.
 */
interface ChatProvider {
    /** Stable lowercase id, also the model-string prefix in "openai:gpt-4o". */
    val id: String

    /** Human-readable name shown in Settings and the model picker. */
    val displayName: String

    /** Model used when the user has a key but hasn't picked one yet. */
    val defaultModel: String

    /** Cloud API provider (needs key + network) vs local model server (needs a running server). */
    val isLocal: Boolean

    /** Whether this provider requires an API key from Settings. */
    val requiresApiKey: Boolean

    /** Cloud: key present in the store. Local: server port reachable. Used by picker + chat(). */
    fun isAvailable(tokenStore: SecureTokenStore?): Boolean

    /** Specific, actionable error shown to the user when isAvailable() is false. */
    fun unavailableMessage(): String

    /** Optional: live model list from the provider's own endpoint. Empty = only defaultModel is offered. */
    suspend fun fetchModels(apiKey: String?): List<String> = emptyList()

    /** The ONE entry point the chat panel calls. All HTTP shape logic lives inside the provider. */
    suspend fun complete(request: ChatRequest): String

    /**
     * STREAMING (2026-09-11, VS Code lm-parity round): streams content deltas to
     * onDelta as they arrive and returns the FULL final text (identical contract to
     * complete(), so the agentic tool loop is unchanged). DEFAULT = blocking
     * complete() fallback — providers migrate one at a time, nothing breaks.
     */
    suspend fun completeStreaming(request: ChatRequest, onDelta: (String) -> Unit): String =
        complete(request)

    /**
     * RICH MODEL METADATA (2026-09-11): ChatModelInfo carries display name, real
     * context window, tool-call support. DEFAULT wraps fetchModels() — additive,
     * existing providers keep working unchanged. Providers with richer /models
     * responses (OpenRouter context_length, Gemini inputTokenLimit) override.
     */
    suspend fun fetchModelInfos(apiKey: String?): List<ChatModelInfo> =
        fetchModels(apiKey).map { ChatModelInfo(it) }

    /**
     * TOKEN COUNTING (2026-09-11): vendor-REAL count of the exact request the
     * provider is about to send (Gemini :countTokens endpoint, Anthropic
     * /v1/messages/count_tokens, jtokkit BPE for OpenAI-family). null = caller
     * falls back to TokenCounter's heuristic estimate. Never throws.
     */
    suspend fun countTokens(request: ChatRequest): Int? = null

    /**
     * R8-VISION: whether this provider's API accepts image attachments. Default
     * true — verified against every vendor's docs on 2026-09-12: OpenAI/xAI/
     * DeepSeek/OpenRouter use image_url content parts, Gemini uses inline_data,
     * Anthropic uses base64 source blocks (see ChatImageAttachments for links).
     * A provider that genuinely cannot accept images overrides false — chat()
     * then refuses the send with a clear message instead of a silent API failure.
     */
    val supportsImages: Boolean get() = true

    /**
     * R9-AUDIO: whether this provider's API accepts audio attachments. false by
     * default — chat() refuses the send with a clear message instead of a silent
     * API failure. OpenAI / OpenRouter / Custom Endpoint / Gemini override true
     * (vendor docs support audio input parts); Anthropic, xAI, DeepSeek keep false.
     */
    val supportsAudio: Boolean get() = false

    /**
     * CUSTOM-ENDPOINT-FIX: when true, defaultModel is a PLACEHOLDER, not a real
     * vendor model — it must NEVER appear as a pickable entry (the server would
     * reject it with "Invalid model"). The picker only lists real models fetched
     * from the provider's own /models endpoint for such providers, and shows an
     * error row when that fetch fails.
     */
    val defaultModelIsPlaceholder: Boolean get() = false
}

/**
 * Per-model metadata surfaced to the UI (model picker, context gauge).
 * Mirrors VS Code's LanguageModelChatInformation, trimmed to what the app consumes.
 */
data class ChatModelInfo(
    /** Vendor model id — same string used in "provider:model" selections. */
    val id: String,
    /** Human-readable name (vendor display name when available). */
    val displayName: String = id,
    /** Real vendor-reported input context window, when the /models response carries it. */
    val maxInputTokens: Int? = null,
    /** Whether the model supports tool/function calling, when known. */
    val supportsToolCalling: Boolean? = null,
)

/** Everything a provider needs to answer one turn. convMsgs includes the leading system entry. */
data class ChatRequest(
    val model: String,
    val systemPrompt: String,
    val convMsgs: JSONArray,
    /** API key from SecureTokenStore — null for local providers (requiresApiKey = false). */
    val apiKey: String?,
    /**
     * R8-VISION: images riding the LAST user message, converted to each vendor's
     * multimodal part shape inside the provider. Text attachments keep riding
     * convMsgs as the ATTACHED CONTEXT block; images NEVER enter the text path.
     */
    val images: List<ChatRequestImage> = emptyList(),
    /**
     * R9-AUDIO: audio attachments riding the LAST user message (same rules as
     * images). OpenAI-family: {type:"input_audio"} parts; Gemini: inline_data
     * with an audio mime_type. Providers without supportsAudio refuse via chat().
     */
    val audios: List<ChatRequestAudio> = emptyList(),
)

/**
 * MULTI-KEY: HTTP-level failure from a provider, carrying the status code so the
 * failover engine can distinguish a REJECTED key (401/403 — switch to the next
 * key) from a RATE-LIMITED key (429 — back off and retry the SAME key) from
 * everything else (400/404/5xx — server-side, never fail over).
 */
class ChatHttpException(
    val statusCode: Int,
    message: String,
    /** 429 Retry-After in ms from the response header, capped at 30s; null = none. */
    val retryAfterMs: Long? = null,
) : Exception(message)

/** One base64 image sent with a request (vendor docs: JPEG/PNG/GIF/WebP accepted). */
data class ChatRequestImage(
    val mimeType: String,
    val base64: String,
    val name: String,
)

/** One base64 audio clip sent with a request. Vendor part: OpenAI input_audio (wav|mp3), Gemini inline_data. */
data class ChatRequestAudio(
    val mimeType: String,
    val base64: String,
    /** OpenAI input_audio "format" field: "wav" or "mp3" (derived from the mime type). */
    val format: String,
    val name: String,
)
