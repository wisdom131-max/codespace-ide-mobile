package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP3 preset table locks: every base URL string is asserted EXACTLY so a
 * silent edit (the class of bug where a URL typo ships) cannot pass CI.
 */
class ProviderPresetsTest {

    @Test
    fun baseUrlsArePinnedExactly() {
        assertEquals("https://api.openai.com/v1/chat/completions", ProviderPresets.byId("openai")!!.baseUrl)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", ProviderPresets.byId("openrouter")!!.baseUrl)
        assertEquals("https://api.deepseek.com/chat/completions", ProviderPresets.byId("deepseek")!!.baseUrl)
        assertEquals("https://api.groq.com/openai/v1/chat/completions", ProviderPresets.byId("groq")!!.baseUrl)
        assertEquals("https://api.mistral.ai/v1/chat/completions", ProviderPresets.byId("mistral")!!.baseUrl)
        assertEquals("https://api.x.ai/v1/chat/completions", ProviderPresets.byId("xai")!!.baseUrl)
        assertEquals("http://localhost:11434/v1/chat/completions", ProviderPresets.byId("ollama")!!.baseUrl)
    }

    @Test
    fun ollamaNeedsNoKeyOthersDo() {
        assertFalse(ProviderPresets.byId("ollama")!!.requiresKey)
        assertTrue(ProviderPresets.byId("openai")!!.requiresKey)
        assertTrue(ProviderPresets.byId("openrouter")!!.requiresKey)
    }

    @Test
    fun reasoningKindsAreLocked() {
        assertEquals("effort", ProviderPresets.byId("openai")!!.reasoningKind)
        assertEquals("effort", ProviderPresets.byId("openrouter")!!.reasoningKind)
        assertEquals("effort", ProviderPresets.byId("xai")!!.reasoningKind)
        assertEquals("none", ProviderPresets.byId("deepseek")!!.reasoningKind)
        assertEquals("none", ProviderPresets.byId("groq")!!.reasoningKind)
        assertEquals("none", ProviderPresets.byId("mistral")!!.reasoningKind)
        assertEquals("none", ProviderPresets.byId("ollama")!!.reasoningKind)
    }

    @Test
    fun unknownProvidersAreNotPresets() {
        assertNull(ProviderPresets.byId("custom"))
        assertNull(ProviderPresets.byId("custom_abc123"))
        assertNull(ProviderPresets.byId("huggingface"))
        assertFalse(ProviderPresets.isPreset("custom_abc123"))
        assertNotNull(ProviderPresets.byId("groq"))
    }

    @Test
    fun everyPresetHasUniqueNonBlankFields() {
        val ids = HashSet<String>()
        for (p in ProviderPresets.ALL) {
            assertTrue(p.providerId.isNotBlank())
            assertTrue(p.label.isNotBlank())
            assertTrue(p.baseUrl.startsWith("http"))
            assertTrue(p.baseUrl.contains("chat/completions"))
            assertTrue(p.reasoningKind == "effort" || p.reasoningKind == "none")
            assertTrue(p.notes.isNotBlank())
            assertTrue(ids.add(p.providerId))
        }
        assertEquals(7, ProviderPresets.ALL.size)
    }
}
