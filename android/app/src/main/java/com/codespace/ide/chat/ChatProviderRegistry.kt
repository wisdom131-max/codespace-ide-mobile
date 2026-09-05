package com.codespace.ide.chat

import com.codespace.ide.data.SecureTokenStore

/**
 * ChatProviderRegistry — the single lookup table every consumer (chat panel, Settings,
 * model picker) reads. The host never enumerates providers itself; it asks the registry.
 *
 * Built-in providers self-register on first access (object initializer -> ProviderBootstrap).
 * Future extension-style providers just call register() the same way.
 */
object ChatProviderRegistry {
    private val providers = linkedMapOf<String, ChatProvider>().apply {
        ProviderBootstrap.registerBuiltIns().forEach { put(it.id, it) }
    }

    fun register(provider: ChatProvider) {
        providers[provider.id] = provider
    }

    fun all(): List<ChatProvider> = providers.values.toList()

    fun byId(id: String): ChatProvider? = providers[id]

    /** Providers the user can actually talk to right now (have a key / server running). */
    fun available(tokenStore: SecureTokenStore?): List<ChatProvider> =
        all().filter { it.isAvailable(tokenStore) }
}
