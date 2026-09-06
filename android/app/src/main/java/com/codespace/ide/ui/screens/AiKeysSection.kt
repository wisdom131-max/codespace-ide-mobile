package com.codespace.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.codespace.ide.chat.AiKeyFormats
import com.codespace.ide.chat.ChatModelSelection
import com.codespace.ide.chat.ChatProvider
import com.codespace.ide.chat.ChatProviderRegistry
import com.codespace.ide.data.SecureTokenStore
import kotlinx.coroutines.launch

/**
 * AI KEYS SECTION — Settings/credential redesign phases 1-3 (2026-09-06).
 *
 * Replaces the old flat AI-Providers block (always-visible key inputs + one global
 * "Save API Keys" button). New model, grounded in the audited VS Code Copilot
 * source (SecretStorage / handleAPIKeyUpdate pattern):
 *
 *   PHASE 1 — masked key status, never echoed: each provider row shows key
 *   presence ("✓ Key saved · live: N models" / "No key"). The stored key is NEVER
 *   rendered back into a field. Tap "Add key" / "Replace key" to open the ONE input.
 *   Empty submit = delete the key. Valid submit = auto-save IMMEDIATELY
 *   (no global Save button). Malformed key = inline error, nothing written.
 *
 *   PHASE 2 — paste-to-route: a key pasted into the WRONG provider's field that
 *   matches another provider's format triggers "This looks like a X key — apply
 *   to X?" instead of a silent wrong-slot write.
 *
 *   PHASE 3 — keys manager: per-provider status incl. a live fetchModels check
 *   after every save ("live: N models" / "key rejected (or unreachable)") and
 *   inline Remove / Replace actions.
 *
 * CREDENTIAL CONTRACT: SecureTokenStore keys are UNCHANGED ("ai_" +
 * id.uppercase(), "active") so existing saved keys keep working. Active-provider
 * activation still writes the shared persisted "provider:model" ChatModelSelection
 * (cross-routing fix) — both chat panels read it.
 *
 * LOCAL providers (requiresApiKey=false, e.g. a future Ollama registration) render
 * as a plain row with just the Switch — isAvailable() is the live status there.
 */
@Composable
internal fun AiKeysSection(tokenStore: SecureTokenStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers = remember { ChatProviderRegistry.all() }

    // Saved-key presence — the recomposition source of truth, synced to the store.
    val savedKeyIds = remember {
        mutableStateListOf<String>().apply {
            providers.forEach { p ->
                if (p.requiresApiKey && tokenStore.aiKey(p.id.uppercase()) != null) add(p.id)
            }
        }
    }

    // Per-provider editing + live-check state (all remember() at top — CI rule).
    val uiStates = remember {
        mutableStateMapOf<String, AiKeyUiState>().apply {
            providers.forEach { put(it.id, AiKeyUiState()) }
        }
    }

    // Active provider restore: stored "active" key -> first provider WITH a saved
    // key -> claude default (same fallback order as the old section).
    var activeProviderId by remember {
        mutableStateOf(
            try { tokenStore.aiKey("active")?.lowercase() } catch (_: Exception) { null }
                ?: providers.firstOrNull { tokenStore.aiKey(it.id.uppercase()) != null }?.id
                ?: "claude"
        )
    }

    Text("AI Providers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))

    providers.forEach { provider ->
        val state = uiStates[provider.id] ?: AiKeyUiState()
        val isActive = provider.id == activeProviderId
        val hasKey = savedKeyIds.contains(provider.id)

        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            ListItem(
                headlineContent = { Text(provider.displayName) },
                supportingContent = { Text(keyStatusLine(provider, hasKey, state, isActive)) },
                trailingContent = {
                    Switch(checked = isActive, onCheckedChange = { enable ->
                        if (enable) {
                            activeProviderId = provider.id
                            tokenStore.setAiKey("active", provider.id.uppercase())
                            // Cross-routing fix: activating a provider in Settings must
                            // switch chat dispatch too — write the shared, persisted
                            // "provider:model" selection both chat panels read.
                            try { ChatModelSelection.set(context, provider.id + ":" + provider.defaultModel) } catch (_: Exception) {}
                        }
                    })
                },
            )

            // ── Manager actions (phase 1/3): Add / Replace / Remove ──
            if (provider.requiresApiKey) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                    TextButton(onClick = {
                        uiStates[provider.id] = state.copy(editing = true, draft = "", showDraft = false, error = null, routeCandidate = null)
                    }) { Text(if (hasKey) "Replace key" else "Add key") }
                    if (hasKey) {
                        TextButton(onClick = {
                            tokenStore.setAiKey(provider.id.uppercase(), null)
                            savedKeyIds.remove(provider.id)
                            uiStates[provider.id] = AiKeyUiState(liveStatus = LiveStatus.UNCHECKED)
                        }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            // ── Key editor (phase 1): one input, auto-save, empty = delete ──
            if (state.editing) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = { raw ->
                        val trimmed = raw.trim()
                        // Phase 2 — paste-to-route: format matches a DIFFERENT provider?
                        val candidate = AiKeyFormats.detect(trimmed)
                            .firstOrNull { it != provider.id && AiKeyFormats.isValid(it, trimmed) }
                        // Read the CURRENT map value — rapid typing can fire multiple
                        // onValueChange before recomposition, and a stale captured
                        // `state` would drop the showDraft toggle mid-edit.
                        val cur = uiStates[provider.id] ?: state
                        uiStates[provider.id] = cur.copy(
                            draft = raw,
                            error = null,
                            routeCandidate = candidate,
                        )
                    },
                    label = { Text("${provider.displayName} API Key") },
                    singleLine = true,
                    visualTransformation = if (state.showDraft) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = {
                            val cur = uiStates[provider.id] ?: state
                            uiStates[provider.id] = cur.copy(showDraft = !cur.showDraft)
                        }) {
                            Icon(
                                if (state.showDraft) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    isError = state.error != null,
                    supportingText = if (state.error != null) {
                        { Text(state.error ?: "", color = MaterialTheme.colorScheme.error) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Phase 2 — route prompt: key looks like ANOTHER provider's format.
                if (state.routeCandidate != null) {
                    val target = providers.firstOrNull { it.id == state.routeCandidate }
                    if (target != null && target.id != provider.id) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Looks like a ${target.displayName} key",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                            OutlinedButton(onClick = { applyKeyToTarget(provider, target, state.draft, tokenStore, savedKeyIds, uiStates, scope) }) {
                                Text("Apply to ${target.displayName}")
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = {
                        // Dismiss = no-op (Copilot pattern) — nothing written.
                        uiStates[provider.id] = AiKeyUiState(liveStatus = state.liveStatus)
                    }) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        shape = RoundedCornerShape(10.dp),
                        onClick = {
                            val trimmed = state.draft.trim()
                            if (trimmed.isEmpty()) {
                                // Phase 1 — empty submit = DELETE the key.
                                if (savedKeyIds.contains(provider.id)) {
                                    tokenStore.setAiKey(provider.id.uppercase(), null)
                                    savedKeyIds.remove(provider.id)
                                }
                                uiStates[provider.id] = AiKeyUiState(liveStatus = LiveStatus.UNCHECKED)
                            } else if (!AiKeyFormats.isValid(provider.id, trimmed)) {
                                // Phase 1 — malformed token: inline error, nothing written.
                                uiStates[provider.id] = state.copy(
                                    error = "That does not look like a valid ${provider.displayName} key.",
                                )
                            } else {
                                // Phase 1 — auto-save IMMEDIATELY (no global Save button).
                                tokenStore.setAiKey(provider.id.uppercase(), trimmed)
                                if (!savedKeyIds.contains(provider.id)) savedKeyIds.add(provider.id)
                                uiStates[provider.id] = AiKeyUiState(liveStatus = LiveStatus.CHECKING)
                                runLiveCheck(provider, tokenStore, uiStates, scope)
                            }
                        },
                    ) { Text(if (savedKeyIds.contains(provider.id)) "Replace" else "Save key") }
                }
            }
        }
        HorizontalDivider()
    }
}

// ── Helpers (top-level, private to file) ─────────────────────────────────────

private enum class LiveStatus { UNCHECKED, CHECKING, LIVE, REJECTED }

private data class AiKeyUiState(
    val editing: Boolean = false,
    val draft: String = "",
    val showDraft: Boolean = false,
    val error: String? = null,
    val routeCandidate: String? = null,
    val liveStatus: LiveStatus = LiveStatus.UNCHECKED,
    val liveModelCount: Int = 0,
)

private fun keyStatusLine(provider: ChatProvider, hasKey: Boolean, state: AiKeyUiState, isActive: Boolean): String {
    val suffix = if (isActive) " · ACTIVE" else ""
    if (!provider.requiresApiKey) {
        return (if (provider.isAvailable(null)) "Available" else "Server not reachable") + suffix
    }
    if (!hasKey) return "No key" + suffix
    return when (state.liveStatus) {
        LiveStatus.UNCHECKED -> "✓ Key saved · tap Replace to update" + suffix
        LiveStatus.CHECKING  -> "✓ Key saved · checking…" + suffix
        LiveStatus.LIVE      -> "✓ Key saved · live: ${state.liveModelCount} models" + suffix
        LiveStatus.REJECTED  -> "✗ Key rejected (or unreachable)" + suffix
    }
}

private fun runLiveCheck(
    provider: ChatProvider,
    tokenStore: SecureTokenStore,
    uiStates: androidx.compose.runtime.snapshots.SnapshotStateMap<String, AiKeyUiState>,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    scope.launch {
        val key = tokenStore.aiKey(provider.id.uppercase())
        val models = try { provider.fetchModels(key) } catch (_: Exception) { emptyList() }
        val current = uiStates[provider.id] ?: AiKeyUiState()
        uiStates[provider.id] = current.copy(
            liveStatus = if (models.isNotEmpty()) LiveStatus.LIVE else LiveStatus.REJECTED,
            liveModelCount = models.size,
        )
    }
}

/** Phase 2 — apply a detected-foreign key to its ACTUAL provider (auto-save + live check). */
private fun applyKeyToTarget(
    from: ChatProvider,
    target: ChatProvider,
    draft: String,
    tokenStore: SecureTokenStore,
    savedKeyIds: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    uiStates: androidx.compose.runtime.snapshots.SnapshotStateMap<String, AiKeyUiState>,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val trimmed = draft.trim()
    tokenStore.setAiKey(target.id.uppercase(), trimmed)
    if (!savedKeyIds.contains(target.id)) savedKeyIds.add(target.id)
    // Close the WRONG-slot editor without writing to `from`.
    uiStates[from.id] = AiKeyUiState(liveStatus = uiStates[from.id]?.liveStatus ?: LiveStatus.UNCHECKED)
    uiStates[target.id] = AiKeyUiState(liveStatus = LiveStatus.CHECKING)
    runLiveCheck(target, tokenStore, uiStates, scope)
}
