package com.codespace.ide.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 2.19: which speech engine runs, and what the user is told about it.
 *
 * The Edge path that inspired this work is deliberately marked unsupported rather than offered:
 * it depends on an undocumented endpoint and an embedded token that is not ours.
 */
class VoiceProvidersTest {

    @Test
    fun theEdgePathIsRefusedNotOffered() {
        val edge = VoiceProviders.catalogue(hasGeminiKey = true, hasAndroidEngine = true)
            .first { it.id == VoiceProviders.EDGE_READALOUD }
        assertEquals(VoiceProviders.Availability.UNSUPPORTED, edge.availability)
        assertTrue(edge.detail, edge.detail.contains("not a supported integration"))
    }

    @Test
    fun noDescriptorCarriesASecret() {
        VoiceProviders.catalogue(true, true).forEach { descriptor ->
            val haystack = (descriptor.id + descriptor.label + descriptor.detail).lowercase()
            assertTrue("descriptor must not embed a token: " + descriptor.id,
                !haystack.contains("trustedclienttoken") && !haystack.contains("api_key="))
        }
    }

    @Test
    fun geminiIsOnlyAvailableWithAKey() {
        val without = VoiceProviders.catalogue(hasGeminiKey = false, hasAndroidEngine = true)
            .first { it.id == VoiceProviders.GEMINI_TTS }
        assertEquals(VoiceProviders.Availability.CONFIGURABLE, without.availability)
        assertTrue(without.requiresKey)
        val with = VoiceProviders.catalogue(hasGeminiKey = true, hasAndroidEngine = true)
            .first { it.id == VoiceProviders.GEMINI_TTS }
        assertEquals(VoiceProviders.Availability.AVAILABLE, with.availability)
    }

    @Test
    fun noAndroidEngineIsReportedAsUnavailable() {
        val android = VoiceProviders.catalogue(hasGeminiKey = false, hasAndroidEngine = false)
            .first { it.id == VoiceProviders.ANDROID_TTS }
        assertEquals(VoiceProviders.Availability.UNAVAILABLE, android.availability)
    }

    @Test
    fun theChosenProviderIsUsedWhenItIsUsable() {
        val status = VoiceProviders.resolve(
            VoiceProviders.ANDROID_TTS, hasGeminiKey = true, hasAndroidEngine = true, networkVoicesAllowed = true)
        assertEquals(VoiceProviders.ANDROID_TTS, status.providerId)
        assertTrue(!status.degraded)
    }

    @Test
    fun aFallingBackChoiceSaysSoRatherThanPretending() {
        val status = VoiceProviders.resolve(
            VoiceProviders.GEMINI_TTS, hasGeminiKey = false, hasAndroidEngine = true, networkVoicesAllowed = true)
        assertEquals(VoiceProviders.ANDROID_TTS, status.providerId)
        assertTrue(status.degraded)
        assertTrue(status.detail, status.detail.contains("No Gemini API key"))
    }

    @Test
    fun cloudVoicesOffKeepsItLocal() {
        val status = VoiceProviders.resolve(
            VoiceProviders.GEMINI_TTS, hasGeminiKey = true, hasAndroidEngine = true, networkVoicesAllowed = false)
        assertEquals(VoiceProviders.ANDROID_TTS, status.providerId)
        assertTrue(status.detail, status.detail.contains("cloud voices", ignoreCase = true))
    }

    @Test
    fun choosingTheUnsupportedEdgeVoiceFallsBackWithAReason() {
        val status = VoiceProviders.resolve(
            VoiceProviders.EDGE_READALOUD, hasGeminiKey = false, hasAndroidEngine = true, networkVoicesAllowed = true)
        assertEquals(VoiceProviders.ANDROID_TTS, status.providerId)
        assertTrue(status.detail, status.detail.contains("not a supported integration"))
    }

    @Test
    fun nothingAtAllIsSaidPlainly() {
        val status = VoiceProviders.resolve(null, hasGeminiKey = false, hasAndroidEngine = false, networkVoicesAllowed = false)
        assertEquals(VoiceProviders.NONE_AVAILABLE, status.providerId)
        assertTrue(status.detail, status.detail.contains("cannot speak"))
    }

    @Test
    fun geminiIsPreferredWhenNoChoiceWasMadeAndAKeyExists() {
        val status = VoiceProviders.resolve(null, hasGeminiKey = true, hasAndroidEngine = true, networkVoicesAllowed = true)
        assertEquals(VoiceProviders.GEMINI_TTS, status.providerId)
        assertTrue(!status.degraded)
    }
}
