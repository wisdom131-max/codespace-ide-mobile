package com.codespace.ide.chat.panel

import com.codespace.ide.chat.keys.SlotNaming
import com.codespace.ide.chat.keys.StoredCredentialsReader
import com.codespace.ide.chat.keys.StoredCredentialsSnapshot

/**
 * U1 (build 657 findings a + d): the PURE half of panel open-state resolution.
 * Kept free of Compose/Android imports so the JVM suite exercises the real rules.
 */

/** Providers whose bundled default model makes them usable on first open, in preference order. */
private val PREFERRED_KEYED = listOf(
    "gemini", "anthropic", "openai", "openrouter", "deepseek", "xai", "groq", "mistral",
)

/** Pure: what the owner has configured, from the read-only WP1 snapshot. */
fun configuredProvidersFrom(snap: StoredCredentialsSnapshot): ConfiguredProviders {
    val keyed = snap.keySlots.filter { it.hasKey }.map { it.providerId }.distinct()
    val ordered = PREFERRED_KEYED.filter { it in keyed } + keyed.filter { it !in PREFERRED_KEYED }.sorted()
    // Only ENABLED endpoints are candidates (a disabled endpoint is kept, not offered).
    val custom = snap.endpoints.filter { it.enabled }.map { it.providerId to it.baseUrl }
    return ConfiguredProviders(ordered, custom)
}

/**
 * The selection the panel opens with: Ready, Unavailable (stores unreadable) or NeedsSetup.
 * B1: the READ OUTCOME travels with the snapshot, so a failed read can no longer masquerade
 * as "nothing configured".
 */
fun initialResolvedSelection(reader: StoredCredentialsReader): ResolvedSelection {
    val read = reader.readSnapshot()
    return SelectionResolver.resolve(
        ChatSettingsPrefs.selection(),
        configuredProvidersFrom(read.snapshot),
        { pid -> SlotNaming.primarySlot(pid) },
        settingsReadable = read.readable,
    )
}

/**
 * Persist a selection ONLY when it is coherent for the CURRENT stores. This is the
 * guard that stops build 657's "ollama · gemini-3.8-flash" from ever being saved
 * and restored across restarts. Returns whether it was persisted.
 */
fun persistSelectionIfCoherent(reader: StoredCredentialsReader, sel: PanelSelection): Boolean {
    val read = reader.readSnapshot()
    val cfg = configuredProvidersFrom(read.snapshot)
    // B1: an unreadable store must never be able to WRITE a conclusion it could not verify.
    val ok = persistDecision(sel, cfg, read.readable)
    // Persist the CURRENT endpoint URL: the row is a cache for the next cold start, never the
    // identity of the selection (that is providerId + modelId + keySlot).
    if (ok) ChatSettingsPrefs.setSelection(sel.copy(baseUrl = SelectionResolver.currentBaseUrl(sel, cfg)))
    return ok
}

/**
 * B1: the persist decision as a pure function, so the JVM suite proves the rule without Android
 * prefs: never persist while the configuration could not be read.
 */
fun persistDecision(sel: PanelSelection, cfg: ConfiguredProviders, settingsReadable: Boolean): Boolean =
    settingsReadable && SelectionResolver.isCoherent(sel, cfg)
