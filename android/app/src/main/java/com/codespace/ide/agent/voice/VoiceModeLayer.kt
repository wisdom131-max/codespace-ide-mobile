package com.codespace.ide.agent.voice

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * PROJECT 2.26 (2026-11-15): the layer that turns the controller into a screen.
 *
 * It exists so [com.codespace.ide.chat.panel.ChatPanelScreen] only has to say "voice mode is open"
 * and hand over two lambdas: one to submit a transcript into the existing conversation, one to read
 * the assistant's latest text. No session is created here, no provider client, no reply.
 *
 * Lifecycle: the layer reports background and foreground to the controller, which is what closes the
 * microphone when the app leaves the screen. Leaving voice mode releases the recogniser, the speech
 * engine and any capture thread.
 */
@Composable
internal fun VoiceModeLayer(
    open: Boolean,
    onClose: () -> Unit,
    submit: (String) -> Unit,
    cancelTurn: () -> Unit,
    modelConfigured: () -> Boolean,
    assistantText: () -> String,
    assistantStreaming: Boolean,
) {
    if (!open) return
    val context = LocalContext.current
    val store = remember(context) { VoiceSettingsStore(context) }
    var state by remember { mutableStateOf(VoiceTurnMachine.State()) }
    var status by remember { mutableStateOf("") }
    var provider by remember {
        mutableStateOf(
            VoiceProviders.Status(
                providerId = store.providerId,
                label = "Checking the speech engine",
                detail = "Voice quality depends on the engine installed on this device.",
                degraded = false,
            )
        )
    }
    var spokenReplies by remember { mutableStateOf(store.spokenReplies) }

    val controllerHolder = remember { mutableStateOf<VoiceController?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controllerHolder.value?.onPermissionResult(granted)
    }
    val controller = remember {
        VoiceController(
            context = context,
            store = store,
            submit = submit,
            cancelTurn = cancelTurn,
            requestMicPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) },
            onState = { next -> state = next },
            onStatus = { line -> status = line },
            onProviderStatus = { next -> provider = next },
            modelConfigured = modelConfigured,
        ).also { controllerHolder.value = it }
    }

    LaunchedEffect(Unit) {
        controller.open()
        store.preferOnDevice = true
    }

    // Speech follows the existing reply, and only when the user asked for spoken replies.
    LaunchedEffect(assistantStreaming) {
        if (!store.spokenReplies) return@LaunchedEffect
        val text = assistantText()
        if (text.isNotBlank()) controller.onAssistantText(text, complete = !assistantStreaming)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controller.onBackground()
                Lifecycle.Event.ON_START -> controller.onForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controllerHolder.value = null
            controller.close()
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        VoiceModeScreen(
            state = state,
            statusLine = status,
            providerLabel = provider.label,
            providerDetail = provider.detail,
            spokenReplies = spokenReplies,
            onToggleMic = {
                store.micEnabled = true
                controller.toggleMic()
            },
            onStop = {
                controller.stop()
                onClose()
            },
            onClose = onClose,
            onToggleSpokenReplies = {
                spokenReplies = !spokenReplies
                store.spokenReplies = spokenReplies
                if (!spokenReplies) controller.stop()
            },
        )
    }
}
