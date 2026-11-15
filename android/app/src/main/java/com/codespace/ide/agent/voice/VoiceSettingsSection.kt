package com.codespace.ide.agent.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * PROJECT 2.27 (2026-11-15): voice settings, with the truth about each switch.
 *
 * Every control here does something specific and says what that is. Spoken replies are off by
 * default; cloud recognition states that audio leaves the device; the provider list shows the Edge
 * voice as unsupported rather than hiding it or pretending it works; and the preview button speaks a
 * fixed line so the user can hear the voice they actually got, including a fallback.
 */
@Composable
internal fun VoiceSettingsSection() {
    val context = LocalContext.current
    val store = remember(context) { VoiceSettingsStore(context) }
    var spokenReplies by remember { mutableStateOf(store.spokenReplies) }
    var cloudRecognition by remember { mutableStateOf(store.cloudRecognitionAllowed) }
    var networkVoices by remember { mutableStateOf(store.networkVoicesAllowed) }
    var pitch by remember { mutableStateOf(store.load().pitch) }
    var rate by remember { mutableStateOf(store.load().rate) }
    var volume by remember { mutableStateOf(store.volume) }
    var silenceMs by remember { mutableStateOf(store.endSilenceMs) }
    var status by remember { mutableStateOf("Checking the speech engine…") }
    var providerDetail by remember { mutableStateOf("") }
    var choiceNote by remember { mutableStateOf("") }

    val provider = remember {
        AndroidTtsProvider(context, object : AndroidTtsProvider.Listener {
            override fun onReady(status: VoiceProviders.Status) {
                providerDetail = status.detail
                choiceNote = status.label
            }
            override fun onUnavailable(reason: String) {
                providerDetail = reason
            }
            override fun onUtteranceStart() = Unit
            override fun onUtteranceDone() = Unit
            override fun onSpeakingStopped() = Unit
        })
    }

    DisposableEffect(Unit) {
        provider.initialise(store.load())
        status = "Voice mode is opened from the chat header, never by itself."
        onDispose { provider.release() }
    }

    Text(
        "Voice",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(16.dp),
    )

    ListItem(
        headlineContent = { Text("Spoken Copilot replies") },
        supportingContent = {
            Text(if (spokenReplies) "Copilot reads its replies out loud in voice mode." else "Off. Copilot answers in text only.")
        },
        trailingContent = {
            Switch(
                checked = spokenReplies,
                onCheckedChange = { next ->
                    spokenReplies = next
                    store.spokenReplies = next
                    if (!next) provider.stop()
                },
            )
        },
    )

    ListItem(
        headlineContent = { Text("Speech engine") },
        supportingContent = {
            Text(
                if (providerDetail.isBlank()) status
                else choiceNote + "\n" + providerDetail
            )
        },
        trailingContent = {
            TextButton(onClick = {
                provider.applyPrefs(store.load())
                provider.speak(VoicePreviewLine, flush = true)
            }) { Text("Preview") }
        },
    )

    VoiceSliderRow(
        label = "Speech rate",
        value = rate,
        range = VoiceSettings.MIN_RATE..VoiceSettings.MAX_RATE,
        display = rate.roundToInt().toString() + "%",
        onChange = { next ->
            rate = next
            val prefs = store.load().copy(rate = next)
            store.saveVoice(prefs)
            provider.applyPrefs(prefs)
        },
    )

    VoiceSliderRow(
        label = "Pitch",
        value = pitch,
        range = VoiceSettings.MIN_PITCH..VoiceSettings.MAX_PITCH,
        display = (pitch * 100f).roundToInt().toString() + "%",
        onChange = { next ->
            pitch = next
            val prefs = store.load().copy(pitch = next)
            store.saveVoice(prefs)
            provider.applyPrefs(prefs)
        },
    )

    VoiceSliderRow(
        label = "Volume",
        value = volume.toFloat(),
        range = 0f..100f,
        display = volume.toString() + "%",
        onChange = { next ->
            volume = next.roundToInt()
            store.volume = volume
        },
    )

    VoiceSliderRow(
        label = "Pause before Copilot answers",
        value = silenceMs.toFloat(),
        range = 400f..2000f,
        display = (silenceMs / 1000f).toString().take(4) + " s",
        onChange = { next ->
            silenceMs = next.roundToInt()
            store.endSilenceMs = silenceMs
        },
    )

    ListItem(
        headlineContent = { Text("Recognise speech on this device") },
        supportingContent = {
            Text(
                if (store.preferOnDevice) "On-device recognition is required; if it cannot run, voice input fails and says why."
                else "On-device recognition is not required, so the platform may use its cloud service."
            )
        },
        trailingContent = {
            Switch(
                checked = store.preferOnDevice,
                onCheckedChange = { next -> store.preferOnDevice = next },
            )
        },
    )

    ListItem(
        headlineContent = { Text("Allow cloud speech recognition") },
        supportingContent = {
            Text(
                if (cloudRecognition) "Your voice audio may be sent to Google to be recognised, and is only used when on-device recognition cannot run."
                else "Off. Recognition stays on the device; if the device cannot do it, voice input stops and explains."
            )
        },
        trailingContent = {
            Switch(
                checked = cloudRecognition,
                onCheckedChange = { next ->
                    cloudRecognition = next
                    store.cloudRecognitionAllowed = next
                },
            )
        },
    )

    ListItem(
        headlineContent = { Text("Allow cloud voices") },
        supportingContent = {
            Text(
                if (networkVoices) "Reply text may be sent to a cloud voice service when one is configured with your own API key."
                else "Off. Only voices installed on this device are used."
            )
        },
        trailingContent = {
            Switch(
                checked = networkVoices,
                onCheckedChange = { next ->
                    networkVoices = next
                    store.networkVoicesAllowed = next
                },
            )
        },
    )

    val catalogue = remember(networkVoices) {
        VoiceProviders.catalogue(hasGeminiKey = false, hasAndroidEngine = provider.isReady)
    }
    catalogue.forEach { descriptor ->
        ListItem(
            headlineContent = { Text(descriptor.label) },
            supportingContent = { Text(descriptor.detail) },
            trailingContent = {
                Text(
                    text = when (descriptor.availability) {
                        VoiceProviders.Availability.AVAILABLE -> "Available"
                        VoiceProviders.Availability.CONFIGURABLE -> "Needs setup"
                        VoiceProviders.Availability.UNSUPPORTED -> "Not supported"
                        VoiceProviders.Availability.UNAVAILABLE -> "Unavailable"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Greetings are not part of voice mode. Copilot speaks only when it is answering a " +
                "question you asked. Vosk offline recognition is a 40 MB download and is not installed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VoiceSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display, style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

/** The line the preview speaks: fixed, short, and about the app rather than about the user. */
private const val VoicePreviewLine = "This is VN Code Copilot speaking. Build finished with no errors."
