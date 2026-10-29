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
