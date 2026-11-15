package com.codespace.ide.agent.voice

/**
 * PROJECT 2.18 (2026-11-15): which recognition engine will actually run, and whether that has to be
 * said out loud.
 *
 * `EXTRA_PREFER_OFFLINE` is a request, not a promise: the recogniser is free to ignore it. So the
 * only honest design is to ask the platform first, decide second, and tell the user third.
 *
 * The rules, in order:
 * 1. If on-device recognition is genuinely available for the language, use it. Nothing leaves the phone.
 * 2. If it is not, and the user has not consented to cloud recognition, refuse and explain. Never
 *    fall back silently - that would send their voice to a server they did not choose.
 * 3. If they have consented, use the platform recogniser and say plainly that audio goes to Google.
 *
 * Pure, so every branch is testable on the JVM.
 */
object RecognitionPlan {

    /** What the platform reported, flattened so this file needs no Android types. */
    data class Capability(
        val recognizerInstalled: Boolean,
        val onDeviceAvailable: Boolean,
        val onDeviceLanguagePackReady: Boolean,
        val cloudAllowedByUser: Boolean,
        val languageTag: String,
    )

    sealed class Decision {
        /** On-device recognition will be used. Nothing leaves the device. */
        data class OnDevice(val languageTag: String) : Decision()

        /** The platform recogniser will be used, audio leaves the device. Must be disclosed. */
        data class Cloud(val languageTag: String, val disclosure: String) : Decision()

        /** Refuse, with a reason a person can act on. */
        data class Unavailable(val reason: String) : Decision()
    }

    fun decide(capability: Capability): Decision {
        if (!capability.recognizerInstalled) {
            return Decision.Unavailable(
                "This device has no speech recognition service installed."
            )
        }
        if (capability.onDeviceAvailable && capability.onDeviceLanguagePackReady) {
            return Decision.OnDevice(capability.languageTag)
        }
        if (!capability.cloudAllowedByUser) {
            val why = when {
                !capability.onDeviceAvailable ->
                    "On-device recognition is not available here, and cloud recognition is switched off."
                else ->
                    "On-device recognition exists but has no language pack for " +
                        capability.languageTag + ", and cloud recognition is switched off."
            }
            return Decision.Unavailable(
                why + " Install the language pack in Android settings, or allow cloud recognition in " +
                    "Voice settings."
            )
        }
        return Decision.Cloud(
            capability.languageTag,
            "Android's speech service will recognise your voice, which means your audio is sent to " +
                "Google. Switch this off in Voice settings to keep voice local.",
        )
    }

    /**
     * The extras that decide whether the recogniser stays local. `EXTRA_PREFER_OFFLINE` alone is
     * insufficient - when on-device recognition is required, the caller must also use the on-device
     * recogniser, which is why this returns both flags rather than just the intent extra.
     */
    data class EngineExtras(val preferOffline: Boolean, val requireOnDevice: Boolean)

    fun engineExtras(decision: Decision): EngineExtras = when (decision) {
        is Decision.OnDevice -> EngineExtras(preferOffline = true, requireOnDevice = true)
        is Decision.Cloud -> EngineExtras(preferOffline = false, requireOnDevice = false)
        is Decision.Unavailable -> EngineExtras(preferOffline = true, requireOnDevice = true)
    }
}
