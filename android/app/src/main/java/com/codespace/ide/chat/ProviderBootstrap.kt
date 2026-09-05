package com.codespace.ide.chat

import com.codespace.ide.chat.providers.AnthropicProvider
import com.codespace.ide.chat.providers.DeepSeekProvider
import com.codespace.ide.chat.providers.GeminiProvider
import com.codespace.ide.chat.providers.OpenAiProvider
import com.codespace.ide.chat.providers.OpenRouterProvider

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
    )
}
