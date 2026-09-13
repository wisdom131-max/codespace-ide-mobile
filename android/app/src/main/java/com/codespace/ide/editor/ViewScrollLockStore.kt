package com.codespace.ide.editor

import androidx.compose.runtime.mutableStateMapOf

/**
 * PAD-1 (2026-09-13): per-view scroll-lock store.
 *
 * A "scroll lock" freezes a view's scroll position: the IME-AWARE-SCROLL effect in
 * CodeEditor (the one that auto-scrolls to keep the cursor above the keyboard) is
 * skipped while the ACTIVE view is locked, so switching focus / typing elsewhere
 * never yanks the pane. Everything else (live content sync, cursor mapping on
 * external edits, scroll anchoring on externalContentSync) is deliberately NOT
 * gated — lock only suppresses the keyboard-reveal auto-scroll.
 *
 * Modeled on MultiCursorModeStore: a global object store so every CodeEditor
 * instance (primary tab or split view) reads the SAME state and survives editor
 * remounts without a remember{} of its own. Keys are VIEW ids: a tab's id is its
 * file path; a split view's id is its SplitViewStore id ("split::...").
 *
 * Lock flags are per-project runtime state; EditorPane clears them when the
 * project changes (persistence ships in PERSIST-A).
 */
object ViewScrollLockStore {
    private val locks = mutableStateMapOf<String, Boolean>()

    fun isLocked(viewKey: String): Boolean = locks[viewKey] == true

    fun toggle(viewKey: String) {
        locks[viewKey] = !isLocked(viewKey)
    }

    /** PERSIST-A entry point: bulk restore after session load. */
    fun setLocked(viewKey: String, locked: Boolean) {
        if (locked) locks[viewKey] = true else locks.remove(viewKey)
    }

    fun clear() = locks.clear()

    /** PERSIST-A: full lock map for per-project persistence (viewKey -> locked). */
    fun snapshot(): Map<String, Boolean> = locks.filterValues { it }
}
