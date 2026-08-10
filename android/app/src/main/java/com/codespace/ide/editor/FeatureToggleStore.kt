package com.codespace.ide.editor

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState

/**
 * Persisted feature toggles for the editor — stored in SharedPreferences.
 * Survives app restarts. Read from SettingsScreen and CodeEditor/EditorPane.
 */
object FeatureToggleStore {
    private const val PREFS = "feature_toggles"
    private lateinit var prefs: android.content.SharedPreferences

    // Each toggle has a key, default, and live Compose state
    data class Toggle(
        val key: String,
        val default: Boolean,
        val label: String,
        val description: String,
    )

    val toggles = listOf(
        Toggle("word_wrap", false, "Word wrap", "Wrap long lines instead of horizontal scroll"),
        Toggle("inlay_hints", true, "Inlay hints", "Inline type and parameter hints"),
        Toggle("minimap", true, "Minimap", "Code overview minimap in the gutter"),
        Toggle("code_lens", true, "CodeLens", "Run/Debug actions above functions"),
        Toggle("sticky_scroll", true, "Sticky scroll", "Pin current scope header while scrolling"),
        Toggle("error_lens", true, "Error lens", "Show inline error messages at end of line"),
        Toggle("color_swatches", true, "Color swatches", "Color preview boxes next to hex colors"),
        Toggle("document_links", true, "Document links", "Clickable links in comments and strings"),
        Toggle("ghost_text", true, "Ghost text", "AI suggestion preview as dimmed text"),
        Toggle("merge_conflicts", true, "Merge conflicts", "Highlight merge conflict markers with resolve buttons"),
        Toggle("lsp_highlights", true, "LSP highlights", "Highlight occurrences of symbol under cursor"),
    )

    private val states = mutableMapOf<String, MutableState<Boolean>>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (t in toggles) {
            states[t.key] = mutableStateOf(prefs.getBoolean(t.key, t.default))
        }
    }

    fun get(key: String): Boolean = states[key]?.value ?: toggles.find { it.key == key }?.default ?: true

    fun set(key: String, value: Boolean) {
        states[key]?.value = value
        prefs.edit().putBoolean(key, value).apply()
    }

    fun state(key: String): MutableState<Boolean> {
        if (key !in states) {
            states[key] = mutableStateOf(prefs.getBoolean(key, toggles.find { it.key == key }?.default ?: true))
        }
        return states[key]!!
    }

    fun toEditorFeatureToggles(): EditorFeatureToggles {
        return EditorFeatureToggles(
            showCodeLens = get("code_lens"),
            showLspHighlights = get("lsp_highlights"),
            showErrorLens = get("error_lens"),
            showColorSwatches = get("color_swatches"),
            showDocumentLinks = get("document_links"),
            showStickyScroll = get("sticky_scroll"),
            showGhostText = get("ghost_text"),
            showInlayHints = get("inlay_hints"),
            showMergeConflicts = get("merge_conflicts"),
            showMinimap = get("minimap"),
            showWordWrap = get("word_wrap"),
        )
    }
}
