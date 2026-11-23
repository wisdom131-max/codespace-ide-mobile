package com.codespace.ide.chat.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.keys.LocalModelProbe
import com.codespace.ide.chat.keys.LocalProbeResult
import com.codespace.ide.chat.keys.ModelListFetcher
import com.codespace.ide.chat.keys.SlotNaming
import com.codespace.ide.chat.keys.StoredCredentialsReader
import com.codespace.ide.engine.ProviderPresets
import kotlinx.coroutines.launch

/**
 * OWNER REPORT (2026-11-23): "for the app key section ... mine is halfway".
 *
 * Custom endpoints have had Test and Fetch-models since they were built; saved keys for the built-in
 * providers had neither, so a key could only be judged by sending a whole chat turn and reading the
 * failure. Both actions now exist here, using the SAME fetcher the custom endpoints use, so there is
 * one answer to "does this key work" rather than two.
 */
@Composable
internal fun KeyProviderActions(
    providerId: String,
    reader: StoredCredentialsReader,
    accent: androidx.compose.ui.graphics.Color,
    textSecondary: androidx.compose.ui.graphics.Color,
    textPrimary: androidx.compose.ui.graphics.Color,
    fontSizeSmall: Float,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var verdict by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }

    val baseUrl = remember(providerId) { ProviderPresets.byId(providerId)?.baseUrl }
    val slot = remember(providerId) { SlotNaming.primarySlot(providerId) }

    fun run(wantModels: Boolean) {
        val url = baseUrl ?: run {
            verdict = "No known endpoint for this provider."
            return
        }
        val key = reader.keyValue(slot).orEmpty()
        busy = true
        verdict = null
        models = emptyList()
        scope.launch {
            val res = ModelListFetcher.fetch(url, key)
            busy = false
            verdict = ModelListFetcher.verdictOf(res)
            if (wantModels && res is ModelListFetcher.FetchResult.Models) models = res.ids
        }
    }

    Column(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { run(false) }, enabled = !busy) {
                Text(if (busy) "Testing..." else "Test", color = accent, fontSize = fontSizeSmall.sp)
            }
            TextButton(onClick = { run(true) }, enabled = !busy) {
                Text("Fetch models", color = accent, fontSize = fontSizeSmall.sp)
            }
        }
        verdict?.let {
            Text(it, color = textSecondary, fontSize = (fontSizeSmall - 1f).coerceAtLeast(9f).sp)
        }
        if (models.isNotEmpty()) {
            Text(
                models.joinToString(", "),
                color = textPrimary,
                fontSize = (fontSizeSmall - 1f).coerceAtLeast(9f).sp,
            )
        }
    }
}

/**
 * Ollama as VS Code renders it: a local server with a state, not a key slot with a blank value.
 * The model list is the one the server actually has, and picking one saves it as the active model
 * through the same preference the model menu writes.
 */
@Composable
internal fun LocalModelsSection(
    accent: androidx.compose.ui.graphics.Color,
    textSecondary: androidx.compose.ui.graphics.Color,
    textPrimary: androidx.compose.ui.graphics.Color,
    fontSizeSmall: Float,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LocalProbeResult?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun check() {
        busy = true
        scope.launch {
            state = LocalModelProbe.tags()
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("Local models", color = textPrimary, fontSize = fontSizeSmall.sp)
        Text(
            when {
                busy -> "Checking port 11434..."
                state == null -> "Not checked yet"
                else -> LocalModelProbe.statusLine(state!!)
            },
            color = textSecondary,
            fontSize = (fontSizeSmall - 1f).coerceAtLeast(9f).sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { check() }, enabled = !busy) {
                Text("Check again", color = accent, fontSize = fontSizeSmall.sp)
            }
        }
        val models = (state as? LocalProbeResult.Running)?.models.orEmpty()
        models.forEach { name ->
            TextButton(onClick = {
                // Same preference the model menu writes, so the choice survives a restart.
                val current = ChatSettingsPrefs.selection() ?: com.codespace.ide.chat.panel.PanelSelection()
                ChatSettingsPrefs.setSelection(
                    current.copy(
                        providerId = "ollama",
                        modelId = name,
                        keySlot = SlotNaming.primarySlot("ollama"),
                        baseUrl = ProviderPresets.byId("ollama")?.baseUrl ?: current.baseUrl,
                    )
                )
                state = LocalProbeResult.Running(models)
            }) {
                Text("Use " + name, color = accent, fontSize = fontSizeSmall.sp)
            }
        }
    }
}
