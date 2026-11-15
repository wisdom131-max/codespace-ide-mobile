package com.codespace.ide.agent.voice

/**
 * PROJECT 2.19 (2026-11-15): speech engines behind one interface, with the honest ones only.
 *
 * The Brahma dex has three paths and two of them cannot be used here:
 *
 * - Its Edge path reaches `speech.platform.bing.com` with a hardcoded client token. That is not a
 *   published API, the token is not ours, and using it would be leaning on someone else's service.
 *   It is therefore marked UNSUPPORTED, not "available".
 * - Its Gemini path calls the published Gemini TTS models with an API key. That API is real, but it
 *   needs the owner's own key and it costs money per request. It is therefore CONFIGURABLE: present
 *   only when a key exists, never silently enabled.
 * - Android `TextToSpeech` is the fallback that always exists when a device has an engine.
 *
 * Nothing here carries a token, and no code or asset from the APK is reused; what is reused is the
 * published tuning numbers (pitch and rate), which are two decimals, not an implementation.
 */
object VoiceProviders {

    enum class Availability { AVAILABLE, CONFIGURABLE, UNSUPPORTED, UNAVAILABLE }

    data class Descriptor(
        val id: String,
        val label: String,
        val availability: Availability,
        val detail: String,
        val requiresKey: Boolean,
        val networkRequired: Boolean,
    )

    /** The catalogue the settings screen shows, including the ones we refuse to use. */
    fun catalogue(hasGeminiKey: Boolean, hasAndroidEngine: Boolean): List<Descriptor> = listOf(
        Descriptor(
            id = ANDROID_TTS,
            label = "Android speech engine (on-device)",
            availability = if (hasAndroidEngine) Availability.AVAILABLE else Availability.UNAVAILABLE,
            detail = if (hasAndroidEngine)
                "Speaks with the engine installed on this device. Works offline. Voice quality depends on the engine and the installed voices."
            else
                "No speech engine is installed on this device.",
            requiresKey = false,
            networkRequired = false,
        ),
        Descriptor(
            id = GEMINI_TTS,
            label = "Gemini neural voice (cloud)",
            availability = if (hasGeminiKey) Availability.AVAILABLE else Availability.CONFIGURABLE,
            detail = if (hasGeminiKey)
                "A natural neural male voice. Your reply text is sent to Gemini to be turned into audio, and each request is billed to your own API key."
            else
                "A natural neural male voice, available once you add your own Gemini API key. Your reply text leaves the device for each request.",
            requiresKey = true,
            networkRequired = true,
        ),
        Descriptor(
            id = EDGE_READALOUD,
            label = "Microsoft Edge read-aloud voice",
            availability = Availability.UNSUPPORTED,
            detail = "Used by the app this voice mode was inspired by, but it relies on an undocumented endpoint and an embedded client token. It is not a supported integration and will not be implemented.",
            requiresKey = false,
            networkRequired = true,
        ),
        Descriptor(
            id = VOSK_LOCAL,
            label = "Vosk offline recognition (voice input only)",
            availability = Availability.CONFIGURABLE,
            detail = "A 40 MB offline recognition model. Not downloaded, and not installed, until you approve the download.",
            requiresKey = false,
            networkRequired = false,
        ),
    )

    /** What the UI must show about the selected provider. Never softened. */
    data class Status(val providerId: String, val label: String, val detail: String, val degraded: Boolean)

    /**
     * Choose the provider to run, and say whether that choice is a fallback.
     *
     * Preference: the owner's explicit choice if it is usable, then Gemini if a key exists and network
     * voices are allowed, then the Android engine. A fallback is always reported, because silently
     * speaking in a worse voice and letting the user think it is the good one is a lie of omission.
     */
    fun resolve(
        preferredId: String?,
        hasGeminiKey: Boolean,
        hasAndroidEngine: Boolean,
        networkVoicesAllowed: Boolean,
    ): Status {
        val catalogue = catalogue(hasGeminiKey, hasAndroidEngine)
        val byId = catalogue.associateBy { it.id }
        val preferred = preferredId?.let { byId[it] }

        if (preferred != null && preferred.availability == Availability.AVAILABLE &&
            (!preferred.networkRequired || networkVoicesAllowed)
        ) {
            return Status(preferred.id, preferred.label, preferred.detail, degraded = false)
        }

        if (hasGeminiKey && networkVoicesAllowed) {
            val gemini = byId.getValue(GEMINI_TTS)
            val fallingBack = preferred != null && preferred.id != GEMINI_TTS
            return Status(
                gemini.id, gemini.label,
                if (fallingBack) "Your chosen voice is not available here, so Gemini's neural voice is used." else gemini.detail,
                degraded = fallingBack,
            )
        }

        if (hasAndroidEngine) {
            val android = byId.getValue(ANDROID_TTS)
            val reason = when {
                preferred == null -> ""
                preferred.id == EDGE_READALOUD -> "The Edge voice is not a supported integration. "
                preferred.id == GEMINI_TTS && !hasGeminiKey -> "No Gemini API key is set. "
                preferred.id == GEMINI_TTS && !networkVoicesAllowed -> "Cloud voices are switched off. "
                else -> "Your chosen voice is unavailable. "
            }
            return Status(android.id, android.label, reason + android.detail, degraded = preferred != null)
        }

        return Status(
            NONE_AVAILABLE,
            "No voice available",
            "This device has no speech engine and no cloud voice is configured, so Copilot cannot speak. Voice input still works.",
            degraded = true,
        )
    }

    const val ANDROID_TTS = "android_tts"
    const val GEMINI_TTS = "gemini_tts"
    const val EDGE_READALOUD = "edge_readaloud"
    const val VOSK_LOCAL = "vosk_local"
    const val NONE_AVAILABLE = "none"
}
