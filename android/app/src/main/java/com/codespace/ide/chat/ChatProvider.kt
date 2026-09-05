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
}

/** Everything a provider needs to answer one turn. convMsgs includes the leading system entry. */
data class ChatRequest(
    val model: String,
    val systemPrompt: String,
    val convMsgs: JSONArray,
    /** API key from SecureTokenStore — null for local providers (requiresApiKey = false). */
    val apiKey: String?,
)
