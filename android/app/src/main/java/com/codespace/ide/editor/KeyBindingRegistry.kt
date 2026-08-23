package com.codespace.ide.editor

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import com.codespace.ide.editor.settings.JsonSettingsStoreType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEvent
import com.codespace.ide.editor.settings.JsonSettingsStore

/**
 * Represents a keyboard shortcut combination.
 * @param key The primary key (e.g., Key.S, Key.F, Key.Enter)
 * @param ctrl Whether Ctrl/Cmd must be held
 * @param shift Whether Shift must be held
 * @param alt Whether Alt/Option must be held
 */
data class KeyCombination(
    val key: Key,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false
) {
    override fun toString(): String {
        val parts = mutableListOf<String>()
        if (ctrl) parts.add("Ctrl")
        if (shift) parts.add("Shift")
        if (alt) parts.add("Alt")
        parts.add(key.toString().removePrefix("Key: "))
        return parts.joinToString("+")
    }
}

/**
 * Editor actions that can be bound to keyboard shortcuts.
 */
enum class EditorAction {
    SAVE,
    FIND,
    REPLACE,
    FIND_NEXT,
    FIND_PREVIOUS,
    GO_TO_LINE,
    FORMAT,
    COMMENT_TOGGLE,
    INDENT,
    UNINDENT,
    DUPLICATE_LINE,
    DELETE_LINE,
    MOVE_LINE_UP,
    MOVE_LINE_DOWN,
    SELECT_ALL,
    SELECT_WORD,
    SELECT_LINE,
    UNDO,
    REDO,
    COPY,
    PASTE,
    CUT,
    TAB_ACCEPT_COMPLETION,
    ESCAPE,
    SMART_ENTER,
    GO_TO_DEFINITION,
    SHOW_HOVER,
    QUICK_FIX,
    RENAME,
    ORGANIZE_IMPORTS,
    TOGGLE_WORD_WRAP,
    TOGGLE_INLAY_HINTS,
    ZOOM_IN,
    ZOOM_OUT,
    ZOOM_RESET,
    COMMAND_PALETTE,
    OPEN_FILE,
    CLOSE_TAB,
    NEXT_TAB,
    PREV_TAB,
}

/**
 * Registry of configurable keybindings.
 * Stores a mapping from EditorAction → KeyCombination.
 * Defaults match VS Code's keybindings.
 */
object KeyBindingRegistry {
    private val bindings = mutableMapOf<EditorAction, KeyCombination>()
    private val defaults = mutableMapOf<EditorAction, KeyCombination>()

    private fun registerDefaults() {
        // File operations
        defaults[EditorAction.SAVE] = KeyCombination(Key.S, ctrl = true)
        defaults[EditorAction.OPEN_FILE] = KeyCombination(Key.O, ctrl = true)
        defaults[EditorAction.CLOSE_TAB] = KeyCombination(Key.W, ctrl = true)
        defaults[EditorAction.NEXT_TAB] = KeyCombination(Key.Tab, ctrl = true)
        defaults[EditorAction.PREV_TAB] = KeyCombination(Key.Tab, ctrl = true, shift = true)

        // Search
        defaults[EditorAction.FIND] = KeyCombination(Key.F, ctrl = true)
        defaults[EditorAction.REPLACE] = KeyCombination(Key.H, ctrl = true)
        defaults[EditorAction.FIND_NEXT] = KeyCombination(Key.F3)
        defaults[EditorAction.FIND_PREVIOUS] = KeyCombination(Key.F3, shift = true)
        defaults[EditorAction.GO_TO_LINE] = KeyCombination(Key.G, ctrl = true)

        // Editing
        defaults[EditorAction.UNDO] = KeyCombination(Key.Z, ctrl = true)
        defaults[EditorAction.REDO] = KeyCombination(Key.Y, ctrl = true)
        defaults[EditorAction.COPY] = KeyCombination(Key.C, ctrl = true)
        defaults[EditorAction.PASTE] = KeyCombination(Key.V, ctrl = true)
        defaults[EditorAction.CUT] = KeyCombination(Key.X, ctrl = true)
        defaults[EditorAction.SELECT_ALL] = KeyCombination(Key.A, ctrl = true)
        defaults[EditorAction.FORMAT] = KeyCombination(Key.I, ctrl = true, shift = true)
        defaults[EditorAction.COMMENT_TOGGLE] = KeyCombination(Key.Slash, ctrl = true)
        defaults[EditorAction.DUPLICATE_LINE] = KeyCombination(Key.D, ctrl = true, shift = true)
        defaults[EditorAction.DELETE_LINE] = KeyCombination(Key.K, ctrl = true, shift = true)
        defaults[EditorAction.INDENT] = KeyCombination(Key.Tab)
        defaults[EditorAction.UNINDENT] = KeyCombination(Key.Tab, shift = true)
        defaults[EditorAction.SMART_ENTER] = KeyCombination(Key.Enter)

        // Navigation
        defaults[EditorAction.GO_TO_DEFINITION] = KeyCombination(Key.F12)
        defaults[EditorAction.SHOW_HOVER] = KeyCombination(Key.K, ctrl = true)
        defaults[EditorAction.QUICK_FIX] = KeyCombination(Key.Period, ctrl = true)
        defaults[EditorAction.RENAME] = KeyCombination(Key.F2)
        defaults[EditorAction.COMMAND_PALETTE] = KeyCombination(Key.P, ctrl = true, shift = true)

        // View
        defaults[EditorAction.TOGGLE_WORD_WRAP] = KeyCombination(Key.Z, alt = true)
        defaults[EditorAction.ZOOM_IN] = KeyCombination(Key.Equals, ctrl = true)
        defaults[EditorAction.ZOOM_OUT] = KeyCombination(Key.Minus, ctrl = true)
        defaults[EditorAction.ZOOM_RESET] = KeyCombination(Key.Zero, ctrl = true)
        defaults[EditorAction.MOVE_LINE_UP] = KeyCombination(Key.DirectionUp, alt = true)
        defaults[EditorAction.MOVE_LINE_DOWN] = KeyCombination(Key.DirectionDown, alt = true)
    }

    init {
        registerDefaults()
        // Copy defaults into active bindings
        bindings.putAll(defaults)
    }

    /**
     * Get the key combination for an action, or null if not bound.
     */
    fun getBinding(action: EditorAction): KeyCombination? = bindings[action]

    /**
     * Set or update a key combination for an action.
     */
    fun setBinding(action: EditorAction, combination: KeyCombination) {
        bindings[action] = combination
        persistBinding(action)
        // Sync to unified JSON store
        val value = "${combination.key.keyCode}|${combination.ctrl}|${combination.shift}|${combination.alt}"
        try { JsonSettingsStore.setKeybinding(action.name, value) } catch (_: Exception) { }
    }

    /**
     * Reset a binding to its default.
     */
    fun resetBinding(action: EditorAction) {
        defaults[action]?.let { bindings[action] = it }
        prefs?.edit()?.remove(action.name)?.apply()
        try { JsonSettingsStore.removeKeybinding(action.name) } catch (_: Exception) { }
    }

    /**
     * Get all current bindings.
     */
    fun getAllBindings(): Map<EditorAction, KeyCombination> = bindings.toMap()

    /**
     * Reset all bindings to their defaults.
     */
    fun resetAllBindings() {
        bindings.clear()
        bindings.putAll(defaults)
        prefs?.edit()?.clear()?.apply()
        try { JsonSettingsStore.clearKeybindings() } catch (_: Exception) { }
    }

    /**
     * Get the default binding for an action (for UI display).
     */
    fun getDefaultBinding(action: EditorAction): KeyCombination? = defaults[action]

    // ── Persistence ─────────────────────────────────────────────────
    private const val PREFS_NAME = "keybindings"
    private var prefs: android.content.SharedPreferences? = null

    /**
     * Load saved bindings from SharedPreferences. Call once at app startup.
     */
    fun init(context: android.content.Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs?.all?.forEach { (key, value) ->
            try {
                val action = EditorAction.valueOf(key)
                val parts = (value as String).split('|')
                val keyCode = parts[0].toIntOrNull() ?: return@forEach
                val composeKey = Key(keyCode)
                bindings[action] = KeyCombination(
                    key = composeKey,
                    ctrl = parts.getOrNull(1) == "true",
                    shift = parts.getOrNull(2) == "true",
                    alt = parts.getOrNull(3) == "true",
                )
            } catch (_: Exception) { }
        }
        // Also load any overrides from the unified JSON store that aren't in SharedPreferences
        try {
            for ((actionName, value) in JsonSettingsStore.getKeybindingOverrides()) {
                if (!prefs!!.contains(actionName)) {
                    val action = EditorAction.valueOf(actionName)
                    val parts = value.split('|')
                    val keyCode = parts[0].toIntOrNull() ?: continue
                    val composeKey = Key(keyCode)
                    bindings[action] = KeyCombination(
                        key = composeKey,
                        ctrl = parts.getOrNull(1) == "true",
                        shift = parts.getOrNull(2) == "true",
                        alt = parts.getOrNull(3) == "true",
                    )
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * Persist a single binding to SharedPreferences.
     */
    fun persistBinding(action: EditorAction) {
        val combo = bindings[action] ?: return
        val value = "${combo.key.keyCode}|${combo.ctrl}|${combo.shift}|${combo.alt}"
        prefs?.edit()?.putString(action.name, value)?.apply()
    }

    /**
     * Persist all bindings.
     */
    fun persistAll() {
        val editor = prefs?.edit() ?: return
        for ((action, combo) in bindings) {
            val value = "${combo.key.keyCode}|${combo.ctrl}|${combo.shift}|${combo.alt}"
            editor.putString(action.name, value)
        }
        editor.apply()
    }

    /**
     * Clear persisted overrides and reset to defaults.
     */
    fun clearPersisted() {
        prefs?.edit()?.clear()?.apply()
        resetAllBindings()
    }

    /**
     * Match a key event against all bindings and return the matching action, if any.
     */
    fun match(keyEvent: KeyEvent): EditorAction? {
        if (keyEvent.type != KeyEventType.KeyDown) return null
        val k = keyEvent.key
        val ctrl = keyEvent.isCtrlPressed
        val shift = keyEvent.isShiftPressed
        val alt = keyEvent.isAltPressed

        for ((action, combo) in bindings) {
            if (combo.key == k && combo.ctrl == ctrl && combo.shift == shift && combo.alt == alt) {
                return action
            }
        }
        return null
    }

    /**
     * Match using explicit modifier flags (for compatibility with
     * event.nativeKeyEvent.isCtrlPressed pattern used elsewhere in CodeEditor).
     */
    fun match(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean): EditorAction? {
        for ((action, combo) in bindings) {
            if (combo.key == key && combo.ctrl == ctrl && combo.shift == shift && combo.alt == alt) {
                return action
            }
        }
        return null
    }
}
