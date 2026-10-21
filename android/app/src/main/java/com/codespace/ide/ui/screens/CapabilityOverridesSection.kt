package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.chat.CustomEndpointStore
import com.codespace.ide.chat.capabilities.ChatCapabilityFlags
import com.codespace.ide.chat.capabilities.ChatCapabilityGate
import com.codespace.ide.chat.capabilities.ChatCapabilityOverrides
import com.codespace.ide.chat.capabilities.ChatCapabilitySeam

/**
 * G-E-c (advisor GO 2026-10-21, with the hiding change): per-model IMAGE
 * CAPABILITY OVERRIDE rows in MK settings, one tri-state per endpoint model
 * — follow-table (null) / yes (true) / no (false), rank-1 in resolution.
 *
 * OWNER RULING (advisor item 2, 2026-10-21): this whole section renders
 * NOTHING while [ChatCapabilityFlags.capabilityTableEnabled] is OFF — no
 * dead controls for a table that is not active. It appears only when the
 * switch is ON.
 *
 * Scope (stated honestly): rows cover CUSTOM ENDPOINT models here (manual +
 * live lists — where MK settings already live). The attach-sheet offer
 * (CopilotChatPanelOverlay wiring) writes overrides for ANY provider's
 * selection. A built-in-provider override panel was not in the ruled scope.
 *
 * UI rules: rounded 8-12dp, item padding 12h/10v minimum.
 */
@Composable
internal fun CapabilityOverridesSection(
    modifier: Modifier = Modifier,
) {
    // Flag OFF = render NOTHING (the advisor's no-dead-controls ruling).
    if (!ChatCapabilityFlags.capabilityTableEnabled) return
    // All remember() at top (CI rule); tick re-reads stores after each write.
    var tick by remember { mutableStateOf(0) }
    val endpoints = remember(tick) { CustomEndpointStore.list() }
    if (endpoints.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Image capability overrides",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Text(
            "Tri-state: follow table / force yes / force no. Overrides beat the live list and the bundled table.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
        endpoints.forEach { ep ->
            val models = remember(tick, ep.id) {
                (CustomEndpointStore.manualModels(ep.id) + CustomEndpointStore.liveModels(ep.id)).distinct()
            }
            if (models.isNotEmpty()) CapabilityOverridesEndpointBody(
                providerId = CustomEndpointStore.providerIdFor(ep.id),
                endpointLabel = ep.label,
                models = models,
                onChanged = { tick++ },
            )
        }
    }
}

/** One endpoint's override rows (separate composable — 64KB rule, small bodies). */
@Composable
private fun CapabilityOverridesEndpointBody(
    providerId: String,
    endpointLabel: String,
    models: List<String>,
    onChanged: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            endpointLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
        models.take(30).forEach { modelId -> CapabilityOverrideModelRow(providerId, modelId, onChanged) }
        if (models.size > 30) {
            Text(
                "+${models.size - 30} more models not listed",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** One model's tri-state row. */
@Composable
private fun CapabilityOverrideModelRow(
    providerId: String,
    modelId: String,
    onChanged: () -> Unit,
) {
    val current = remember(providerId, modelId) {
        ModelCapabilityOverrides.get(providerId, modelId)?.acceptsImages
    }
    val resolved = remember(providerId, modelId) {
        ChatCapabilityGate.resolve(providerId, modelId).acceptsImages
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modelId,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (resolved == null) "resolved: unknown" else if (resolved) "resolved: yes" else "resolved: no",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when (current) {
                null -> "Images: follow"
                true -> "Images: yes"
                false -> "Images: no"
            },
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp),
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(8.dp),
                )
                .clickable {
                    val next = ChatCapabilitySeam.nextImagesOverride(current)
                    ChatCapabilitySeam.setImagesOverride("$providerId:$modelId", next)
                    onChanged()
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}
