package com.codespace.ide.chat

import com.codespace.ide.chat.providers.AnthropicProvider
import com.codespace.ide.chat.providers.DeepSeekProvider
import com.codespace.ide.chat.providers.GeminiProvider
import com.codespace.ide.chat.providers.OpenAiProvider
import com.codespace.ide.chat.providers.OpenRouterProvider
import com.codespace.ide.chat.providers.XaiProvider
import com.codespace.ide.chat.providers.CustomOpenAiProvider

/**
 * ProviderBootstrap — registers the built-in cloud API providers, once, on first
 * registry access. Order matches the old AiProviderId enum so the default selected
 * model is unchanged for existing users. Adding a provider = one import + one line.
 *
 * Local model servers (Ollama, LM Studio, llama.cpp) register the exact same way when
 * they return as extension-style plug-ins — see wisdom131-max/codespace-ide-extensions.
 */
object ProviderBootstrap {
    fun registerBuiltIns(): List<ChatProvider> = listOf(
        OpenAiProvider(),
        AnthropicProvider(),
        GeminiProvider(),
        DeepSeekProvider(),
        OpenRouterProvider(),
        XaiProvider(),
    ) + customEndpointProviders()

    /**
     * MK-RESTRUCTURE (2026-09-16): one CustomOpenAiProvider instance per
     * CustomEndpointStore entry — each endpoint is its own pickable provider.
     * The legacy endpoint (id "default") maps to provider id "custom" so
     * existing keys + saved selections keep working. Re-run on every endpoint
     * CRUD mutation (register() replaces by id; dead ids are unregistered by
     * CustomEndpointStore.syncProviders()).
     */
    // Pure builder — SAFE to call inside ChatProviderRegistry's own initializer
    // (does NOT touch the registry; the initializer applies the returned list).
    fun customEndpointProviders(): List<ChatProvider> {
        val customProviders = ArrayList<ChatProvider>()
        try {
            com.codespace.ide.chat.CustomEndpointStore.list().forEach { ep ->
                customProviders.add(CustomOpenAiProvider(ep.id))
            }
        } catch (_: Exception) { }
        return customProviders
    }

    // Re-register (endpoint add/edit/delete + init sync) — must ONLY be called
    // AFTER the registry object is fully constructed (register() replaces by id;
    // dead endpoint ids are unregistered by CustomEndpointStore.syncProviders()).
    fun registerCustomEndpoints(): List<ChatProvider> {
        val customProviders = customEndpointProviders()
        try {
            customProviders.forEach { com.codespace.ide.chat.ChatProviderRegistry.register(it) }
        } catch (_: Exception) { }
        return customProviders
    }
}
