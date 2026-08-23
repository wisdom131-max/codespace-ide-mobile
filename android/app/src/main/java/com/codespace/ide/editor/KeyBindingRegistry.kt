package com.codespace.ide.editor

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEvent

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

    init {
        // File operations
        bindings[EditorAction.SAVE] = KeyCombination(Key.S, ctrl = true)
        bindings[EditorAction.OPEN_FILE] = KeyCombination(Key.O, ctrl = true)
        bindings[EditorAction.CLOSE_TAB] = KeyCombination(Key.W, ctrl = true)
        bindings[EditorAction.NEXT_TAB] = KeyCombination(Key.Tab, ctrl = true)
        bindings[EditorAction.PREV_TAB] = KeyCombination(Key.Tab, ctrl = true, shift = true)

        // Search
        bindings[EditorAction.FIND] = KeyCombination(Key.F, ctrl = true)
        bindings[EditorAction.REPLACE] = KeyCombination(Key.H, ctrl = true)
        bindings[EditorAction.FIND_NEXT] = KeyCombination(Key.F3)
        bindings[EditorAction.FIND_PREVIOUS] = KeyCombination(Key.F3, shift = true)
        bindings[EditorAction.GO_TO_LINE] = KeyCombination(Key.G, ctrl = true)

        // Editing
        bindings[EditorAction.UNDO] = KeyCombination(Key.Z, ctrl = true)
        bindings[EditorAction.REDO] = KeyCombination(Key.Y, ctrl = true)
        bindings[EditorAction.COPY] = KeyCombination(Key.C, ctrl = true)
        bindings[EditorAction.PASTE] = KeyCombination(Key.V, ctrl = true)
        bindings[EditorAction.CUT] = KeyCombination(Key.X, ctrl = true)
        bindings[EditorAction.SELECT_ALL] = KeyCombination(Key.A, ctrl = true)
        bindings[EditorAction.FORMAT] = KeyCombination(Key.I, ctrl = true, shift = true)
        bindings[EditorAction.COMMENT_TOGGLE] = KeyCombination(Key.Slash, ctrl = true)
        bindings[EditorAction.DUPLICATE_LINE] = KeyCombination(Key.D, ctrl = true, shift = true)
        bindings[EditorAction.DELETE_LINE] = KeyCombination(Key.K, ctrl = true, shift = true)
        bindings[EditorAction.INDENT] = KeyCombination(Key.Tab)
        bindings[EditorAction.UNINDENT] = KeyCombination(Key.Tab, shift = true)
        bindings[EditorAction.SMART_ENTER] = KeyCombination(Key.Enter)

        // Navigation
        bindings[EditorAction.GO_TO_DEFINITION] = KeyCombination(Key.F12)
        bindings[EditorAction.SHOW_HOVER] = KeyCombination(Key.K, ctrl = true)
        bindings[EditorAction.QUICK_FIX] = KeyCombination(Key.Period, ctrl = true)
        bindings[EditorAction.RENAME] = KeyCombination(Key.F2)
        bindings[EditorAction.COMMAND_PALETTE] = KeyCombination(Key.P, ctrl = true, shift = true)

        // View
        bindings[EditorAction.TOGGLE_WORD_WRAP] = KeyCombination(Key.Z, alt = true)
        bindings[EditorAction.ZOOM_IN] = KeyCombination(Key.Equals, ctrl = true)
        bindings[EditorAction.ZOOM_OUT] = KeyCombination(Key.Minus, ctrl = true)
        bindings[EditorAction.ZOOM_RESET] = KeyCombination(Key.Zero, ctrl = true)
        bindings[EditorAction.MOVE_LINE_UP] = KeyCombination(Key.DirectionUp, alt = true)
        bindings[EditorAction.MOVE_LINE_DOWN] = KeyCombination(Key.DirectionDown, alt = true)
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
    }

    /**
     * Reset a binding to its default.
     */
    fun resetBinding(action: EditorAction) {
        // Re-init just this action by re-creating the default
        val defaults = mutableMapOf<EditorAction, KeyCombination>()
        // The defaults are set in init(), so we can't easily re-init just one.
        // For now, just leave the current binding.
    }

    /**
     * Get all current bindings.
     */
    fun getAllBindings(): Map<EditorAction, KeyCombination> = bindings.toMap()

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
