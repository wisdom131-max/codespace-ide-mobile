package com.codespace.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.codespace.ide.chat.CustomEndpointStore
import com.codespace.ide.chat.keys.StoredCredentialsReader
import com.codespace.ide.chat.panel.CustomEndpointEditor
import com.codespace.ide.chat.panel.KeysManagerDialog
import com.codespace.ide.data.SecureTokenStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.codespace.ide.agent.image.ImageModels
import com.codespace.ide.editor.ProjectSettingsStore

/**
 * Settings → Providers and Models. This is the surface the owner asked the keys to
 * MOVE to: the chat panel keeps a gear that opens the same dialogs, but the panel
 * header no longer carries a Keys entry, because credential management is
 * configuration, not conversation.
 *
 * Privacy contract, per the owner's own check B: this section shows provider ids,
 * slot NAMES and COUNTS. It never reads, prints, or formats a key value - the reader
 * exposes `hasKey` only, and nothing here touches the token store directly.
 */
@Composable
fun ProvidersAndModelsSection(tokenStore: SecureTokenStore) {
    val context = LocalContext.current
    var showKeys by remember { mutableStateOf(false) }
    var showEndpoints by remember { mutableStateOf(false) }
    var bump by remember { mutableStateOf(0) }

    val reader = remember(bump) {
        StoredCredentialsReader(tokenStore, CustomEndpointStore)
    }
    val snapshot = remember(bump, showKeys, showEndpoints) { reader.snapshot() }
    val configured = snapshot.keySlots.count { it.hasKey }
    val endpointCount = snapshot.endpoints.size

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Providers and Models", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.padding(top = 4.dp))
        Text(
            configured.toString() + " key slots found" +
                if (endpointCount > 0) " . " + endpointCount + " custom endpoint(s)" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        if (configured == 0) {
            Text(
                "No keys stored yet. Add one here or via the chat gear.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        ImageGenerationSection()
        // Slot NAMES only, never values: this is the readout the device sheet asks for.
        for (slot in snapshot.keySlots.filter { it.hasKey }.sortedBy { it.slotName }) {
            Text(
                slot.slotName + "  (" + slot.providerId +
                    (if (slot.poolIndex != null) ", pool " + slot.poolIndex else "") + ")",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Start) {
            Button(onClick = { showKeys = true }) { Text("Manage keys") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { showEndpoints = true }) { Text("Custom endpoints") }
        }
        HorizontalDivider(Modifier.padding(top = 12.dp))
    }

    if (showKeys) {
        KeysManagerDialog(
            tokenStore = tokenStore,
            onDismiss = { showKeys = false; bump++ },
        )
    }
    if (showEndpoints) {
        CustomEndpointEditor(
            tokenStore = tokenStore,
            onClose = { showEndpoints = false; bump++ },
        )
    }
}

/**
 * OWNER REPORT (2026-11-23): the image model lived as a free-text box on the settings page. It lives
 * here now, beside the keys it depends on, and it offers only models that can actually generate.
 * VS Code's rule is the same one: the picker shows what declares the capability, and nothing is typed
 * in by hand.
 */
@Composable
private fun ImageGenerationSection() {
    val stored = ProjectSettingsStore.geminiImageModel.value
    val chosen = ImageModels.resolve(stored)
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().padding(top = 20.dp),
    ) {
        Text("Image generation", style = MaterialTheme.typography.titleSmall)
        Text(
            "Used by Generate Image with AI. Only models that can return an image are offered.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp),
        )
        for (model in ImageModels.usable) {
            val selected = model.id == chosen
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Color(0xFF2A3A2E) else Color.Transparent)
                    .clickable { ProjectSettingsStore.setGeminiImageModel(model.id) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (selected) "\u2713" else "\u25CB",
                    color = if (selected) Color(0xFF7BD88F) else Color(0xFF888888),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
                androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
                    Text(model.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        model.provider + " \u00b7 " + model.id,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Text(
            "Cannot generate images",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 14.dp),
        )
        for (model in ImageModels.unusable) {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(model.label + "  (" + model.provider + ")", fontSize = 12.sp, color = Color(0xFF9A9A9A))
                Text(model.reason, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A8A8A))
            }
        }
    }
}
