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
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.Delete
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
import com.codespace.ide.chat.CustomEndpointStore
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
            providers.forEach {
                put(it.id, if (it.id == "custom") {
                    AiKeyUiState(urlDraft = CustomEndpointStore.baseUrl ?: "")
                } else AiKeyUiState())
            }
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
                    if (hasKey && com.codespace.ide.chat.ChatKeyPool.activeSuffix(provider.id) != null &&
                        com.codespace.ide.chat.ChatKeyPool.activeSuffix(provider.id) != provider.id.uppercase()) {
                        TextButton(onClick = {
                            com.codespace.ide.chat.ChatKeyPool.setActive(provider.id, provider.id.uppercase())
                            val st = uiStates[provider.id] ?: state
                            uiStates[provider.id] = st.copy(slotChecks = st.slotChecks)
                        }) { Text("Set primary active") }
                    }
                    if (hasKey) {
                        TextButton(onClick = {
                            tokenStore.setAiKey(provider.id.uppercase(), null)
                            if (com.codespace.ide.chat.ChatKeyPool.activeSuffix(provider.id) == provider.id.uppercase()) {
                                com.codespace.ide.chat.ChatKeyPool.clearActive(provider.id)
                            }
                            // MULTI-KEY: extras may keep the provider alive — recheck pool.
                            if (!com.codespace.ide.chat.ChatKeyPool.hasAnyKey(tokenStore, provider.id)) {
                                savedKeyIds.remove(provider.id)
                            }
                            uiStates[provider.id] = AiKeyUiState(liveStatus = LiveStatus.UNCHECKED)
                        }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }

                // ── MULTI-KEY: additional keys — a growing list, unlimited slots.
                // Order = failover order; selection is automatic (ChatKeyFailover). ──
                val extraSlots = com.codespace.ide.chat.ChatKeyPool.slots(provider.id)
                    .filterIndexed { i, suf -> i > 0 && !tokenStore.aiKey(suf).isNullOrBlank() }
                extraSlots.forEach { suf ->
                    val kkey = tokenStore.aiKey(suf) ?: ""
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                com.codespace.ide.chat.ChatKeyPool.label(suf).ifEmpty { suf },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "\u2022\u2022\u2022" + kkey.takeLast(4) +
                                    (state.slotChecks[suf]?.let { " \u00b7 " + it } ?: " \u00b7 saved"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val isActiveKey = com.codespace.ide.chat.ChatKeyPool.activeSuffix(provider.id) == suf
                        if (isActiveKey) {
                            Text(
                                "\u25cf Active",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            TextButton(onClick = {
                                com.codespace.ide.chat.ChatKeyPool.setActive(provider.id, suf)
                                // touch state so the rows recompose with the new active
                                val st = uiStates[provider.id] ?: state
                                uiStates[provider.id] = st.copy(slotChecks = st.slotChecks)
                            }) { Text("Set active") }
                        }
                        TextButton(onClick = {
                            scope.launch {
                                val st0 = uiStates[provider.id] ?: state
                                uiStates[provider.id] = st0.copy(slotChecks = st0.slotChecks + (suf to "checking\u2026"))
                                val result = try {
                                    val m = provider.fetchModels(kkey)
                                    if (m.isNotEmpty()) "live: " + m.size + " models" else "reachable, 0 models"
                                } catch (e: Exception) { "\u2717 " + (e.message ?: "failed").take(80) }
                                val st1 = uiStates[provider.id] ?: st0
                                uiStates[provider.id] = st1.copy(slotChecks = st1.slotChecks + (suf to result))
                            }
                        }) { Text("Test") }
                        IconButton(onClick = {
                            com.codespace.ide.chat.ChatKeyPool.removeKey(tokenStore, provider.id, suf)
                            com.codespace.ide.chat.ChatKeyFailover.clearCooldowns()
                            val st2 = uiStates[provider.id] ?: state
                            uiStates[provider.id] = st2.copy(slotChecks = st2.slotChecks - suf)
                            if (!com.codespace.ide.chat.ChatKeyPool.hasAnyKey(tokenStore, provider.id)) {
                                savedKeyIds.remove(provider.id)
                            }
                        }) {
                            Icon(
                                Icons.Default.Delete, contentDescription = "Remove key",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                val activeSuf = com.codespace.ide.chat.ChatKeyPool.activeSuffix(provider.id)
                val slot1Suf = provider.id.uppercase()
                if (activeSuf != null && activeSuf != slot1Suf &&
                    !tokenStore.aiKey(activeSuf).isNullOrBlank()) {
                    Text(
                        "Active key: " + com.codespace.ide.chat.ChatKeyPool.label(activeSuf).ifEmpty { activeSuf } +
                            " \u2014 tried first; failover picks the next key only when it fails",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
                if (!hasKey && extraSlots.isNotEmpty()) {
                    Text(
                        "Failover only: primary key is empty \u2014 " + extraSlots.size +
                            " additional key(s) answering for this provider",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
                if (state.addingKey) {
                    OutlinedTextField(
                        value = state.addLabel,
                        onValueChange = { raw ->
                            val cur = uiStates[provider.id] ?: state
                            uiStates[provider.id] = cur.copy(addLabel = raw, addError = null)
                        },
                        label = { Text("Label (e.g. Work key)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.addDraft,
                        onValueChange = { raw ->
                            val cur = uiStates[provider.id] ?: state
                            uiStates[provider.id] = cur.copy(addDraft = raw, addError = null)
                        },
                        label = { Text(provider.displayName + " API key") },
                        singleLine = true,
                        visualTransformation = if (state.showDraft) VisualTransformation.None else PasswordVisualTransformation(),
                        isError = state.addError != null,
                        supportingText = if (state.addError != null) {
                            { Text(state.addError ?: "", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            val cur = uiStates[provider.id] ?: state
                            uiStates[provider.id] = cur.copy(addingKey = false, addDraft = "", addLabel = "", addError = null)
                        }) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            shape = RoundedCornerShape(10.dp),
                            onClick = {
                                val trimmed = state.addDraft.trim()
                                val cur = uiStates[provider.id] ?: state
                                if (trimmed.isEmpty()) {
                                    uiStates[provider.id] = cur.copy(addError = "Paste a key first.")
                                } else if (!AiKeyFormats.isValid(provider.id, trimmed)) {
                                    uiStates[provider.id] = cur.copy(
                                        addError = "That does not look like a valid " + provider.displayName + " key.",
                                    )
                                } else {
                                    // MULTI-KEY: append at the first free slot + label + live check.
                                    val suf = com.codespace.ide.chat.ChatKeyPool.addKey(tokenStore, provider.id, trimmed)
                                    com.codespace.ide.chat.ChatKeyPool.setLabel(
                                        suf, state.addLabel.trim().ifEmpty { "Key " + suf.substringAfterLast('_') })
                                    com.codespace.ide.chat.ChatKeyFailover.clearCooldowns()
                                    if (!savedKeyIds.contains(provider.id)) savedKeyIds.add(provider.id)
                                    uiStates[provider.id] = cur.copy(
                                        addingKey = false, addDraft = "", addLabel = "", addError = null,
                                        slotChecks = cur.slotChecks + (suf to "checking\u2026"),
                                    )
                                    scope.launch {
                                        val k = com.codespace.ide.chat.ChatKeyPool.keys(tokenStore, provider.id)
                                            .firstOrNull { it.first == suf }?.second
                                        val result = if (k == null) "saved" else try {
                                            val m = provider.fetchModels(k)
                                            if (m.isNotEmpty()) "live: " + m.size + " models" else "reachable, 0 models"
                                        } catch (e: Exception) { "\u2717 " + (e.message ?: "failed").take(80) }
                                        val st = uiStates[provider.id] ?: cur
                                        uiStates[provider.id] = st.copy(slotChecks = st.slotChecks + (suf to result))
                                    }
                                }
                            },
                        ) { Text("Save key") }
                    }
                } else if (hasKey || extraSlots.isNotEmpty()) {
                    TextButton(onClick = {
                        val cur = uiStates[provider.id] ?: state
                        uiStates[provider.id] = cur.copy(addingKey = true, addDraft = "", addLabel = "", addError = null)
                    }) { Text("+ Add another key") }
                }
            }

            // ── CUSTOM ENDPOINT URL (2026-09-11): base URL against the shared
            // OpenAI-compatible transport. Auto-saved on tap; live check re-runs so
            // "live: N models" verifies the endpoint, not just the key. ──
            if (provider.id == "custom") {
                OutlinedTextField(
                    value = state.urlDraft,
                    onValueChange = { raw ->
                        val cur = uiStates[provider.id] ?: state
                        uiStates[provider.id] = cur.copy(urlDraft = raw, urlError = null, urlSaved = false)
                    },
                    label = { Text("Endpoint base URL") },
                    singleLine = true,
                    isError = state.urlError != null,
                    supportingText = if (state.urlError != null) {
                        { Text(state.urlError ?: "", color = MaterialTheme.colorScheme.error) }
                    } else {
                        { Text("https://host/v1 — /chat/completions is appended automatically") }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (state.urlSaved) {
                        Text("Endpoint saved", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                    Button(
                        shape = RoundedCornerShape(10.dp),
                        onClick = {
                            val trimmed = state.urlDraft.trim()
                            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                                uiStates[provider.id] = state.copy(
                                    urlError = "Endpoint must start with http:// or https://",
                                )
                            } else {
                                CustomEndpointStore.baseUrl = trimmed
                                val cur = uiStates[provider.id] ?: state
                                uiStates[provider.id] = cur.copy(urlSaved = true, urlError = null)
                                // Re-verify the endpoint with a live model fetch.
                                if (savedKeyIds.contains(provider.id)) {
                                    uiStates[provider.id] =
                                        (uiStates[provider.id] ?: cur).copy(liveStatus = LiveStatus.CHECKING)
                                    runLiveCheck(provider, tokenStore, uiStates, scope)
                                }
                            }
                        },
                    ) { Text(if (CustomEndpointStore.baseUrl == null) "Save endpoint" else "Update endpoint") }
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
                                // Phase 1 — empty submit = DELETE the key (slot 1).
                                if (savedKeyIds.contains(provider.id)) {
                                    tokenStore.setAiKey(provider.id.uppercase(), null)
                                    if (com.codespace.ide.chat.ChatKeyPool.activeSuffix(provider.id) == provider.id.uppercase()) {
                                        com.codespace.ide.chat.ChatKeyPool.clearActive(provider.id)
                                    }
                                    if (!com.codespace.ide.chat.ChatKeyPool.hasAnyKey(tokenStore, provider.id)) {
                                        savedKeyIds.remove(provider.id)
                                    }
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
    val liveError: String? = null,
    // Custom-endpoint URL editor (provider id "custom" only)
    val urlDraft: String = "",
    val urlError: String? = null,
    // MULTI-KEY: per-slot live-check results (slot suffix -> status line)
    val slotChecks: Map<String, String> = emptyMap(),
    // MULTI-KEY: "+ Add another key" inline editor
    val addingKey: Boolean = false,
    val addDraft: String = "",
    val addLabel: String = "",
    val addError: String? = null,
    val urlSaved: Boolean = false,
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
        LiveStatus.REJECTED  -> "✗ Key rejected (or unreachable)" +
            (state.liveError?.let { " — " + it } ?: "") + suffix
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
        // CUSTOM-ENDPOINT-FIX (b): fetchModelList now throws the REAL failure
        // (HTTP status + vendor message) — surface it instead of "unreachable".
        var fetchError: String? = null
        // MULTI-KEY: try every key of the provider — the status line reflects the
        // failover reality (any working key = live), not just slot 1.
        val models = try {
            com.codespace.ide.chat.ChatKeyFailover.execute(provider.id, tokenStore) { k ->
                provider.fetchModels(k)
            }
        } catch (e: Exception) {
            fetchError = e.message?.take(120)
            emptyList()
        }
        val current = uiStates[provider.id] ?: AiKeyUiState()
        uiStates[provider.id] = current.copy(
            liveStatus = if (models.isNotEmpty()) LiveStatus.LIVE else LiveStatus.REJECTED,
            liveModelCount = models.size,
            liveError = fetchError,
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
