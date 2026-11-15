package com.codespace.ide.agent.voice

import android.content.Context

/**
 * PROJECT 2.23 (2026-11-15): the voice settings, persisted.
 *
 * Every default is chosen so that an untouched install is quiet and local: voice mode off until the
 * user opens it, spoken replies off until asked for, cloud recognition off until consented to, and
 * cloud voices off until the owner allows them.
 *
 * There is no greeting setting because there is no greeting: the only speech this feature produces
 * is Copilot's reply to a question the user asked.
 */
class VoiceSettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): VoiceSettings.Prefs = VoiceSettings.Prefs(
        spokenReplies = prefs.getBoolean(KEY_SPOKEN, false),
        welcomeEnabled = false,
        voiceName = prefs.getString(KEY_VOICE, null),
        pitch = VoiceSettings.clampPitch(prefs.getFloat(KEY_PITCH, VoiceSettings.DEFAULT_PITCH)),
        rate = VoiceSettings.clampRate(prefs.getFloat(KEY_RATE, VoiceSettings.DEFAULT_RATE)),
        localOnly = prefs.getBoolean(KEY_LOCAL_ONLY, true),
    )

    fun saveVoice(prefs: VoiceSettings.Prefs) {
        this.prefs.edit()
            .putBoolean(KEY_SPOKEN, prefs.spokenReplies)
            .putString(KEY_VOICE, prefs.voiceName)
            .putFloat(KEY_PITCH, VoiceSettings.clampPitch(prefs.pitch))
            .putFloat(KEY_RATE, VoiceSettings.clampRate(prefs.rate))
            .putBoolean(KEY_LOCAL_ONLY, prefs.localOnly)
            .apply()
    }

    var spokenReplies: Boolean
        get() = prefs.getBoolean(KEY_SPOKEN, false)
        set(value) = prefs.edit().putBoolean(KEY_SPOKEN, value).apply()

    var micEnabled: Boolean
        get() = prefs.getBoolean(KEY_MIC, true)
        set(value) = prefs.edit().putBoolean(KEY_MIC, value).apply()

    var providerId: String
        get() = prefs.getString(KEY_PROVIDER, VoiceProviders.ANDROID_TTS) ?: VoiceProviders.ANDROID_TTS
        set(value) = prefs.edit().putString(KEY_PROVIDER, value).apply()

    var cloudRecognitionAllowed: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_STT, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_STT, value).apply()

    var networkVoicesAllowed: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_TTS, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_TTS, value).apply()

    var preferOnDevice: Boolean
        get() = prefs.getBoolean(KEY_ON_DEVICE, true)
        set(value) = prefs.edit().putBoolean(KEY_ON_DEVICE, value).apply()

    var languageTag: String
        get() = prefs.getString(KEY_LANGUAGE, "en-US") ?: "en-US"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var volume: Int
        get() = prefs.getInt(KEY_VOLUME, 100).coerceIn(0, 100)
        set(value) = prefs.edit().putInt(KEY_VOLUME, value.coerceIn(0, 100)).apply()

    /** Silence before an utterance is considered finished, in milliseconds. */
    var endSilenceMs: Int
        get() = prefs.getInt(KEY_SILENCE, 800).coerceIn(400, 2_000)
        set(value) = prefs.edit().putInt(KEY_SILENCE, value.coerceIn(400, 2_000)).apply()

    companion object {
        private const val FILE = "vncode_voice_settings"
        private const val KEY_SPOKEN = "spoken_replies"
        private const val KEY_VOICE = "voice_name"
        private const val KEY_PITCH = "pitch"
        private const val KEY_RATE = "rate"
        private const val KEY_LOCAL_ONLY = "local_only"
        private const val KEY_MIC = "mic_enabled"
        private const val KEY_PROVIDER = "provider_id"
        private const val KEY_CLOUD_STT = "cloud_recognition_allowed"
        private const val KEY_CLOUD_TTS = "network_voices_allowed"
        private const val KEY_ON_DEVICE = "prefer_on_device"
        private const val KEY_LANGUAGE = "language_tag"
        private const val KEY_VOLUME = "volume"
        private const val KEY_SILENCE = "end_silence_ms"
    }
}
