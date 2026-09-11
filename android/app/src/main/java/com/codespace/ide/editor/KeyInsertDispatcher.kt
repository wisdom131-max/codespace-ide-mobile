package com.codespace.ide.editor

/**
 * KEY-INSERT-DISPATCHER (2026-09-11):
 *
 * The extra-keys row (coding toolbar) previously funneled every key press through a
 * single shell-level `((String) -> Unit)?` slot that CodeEditor instances OVERWROTE
 * on mount (LaunchedEffect(Unit) { onInsertHandler?.invoke { ... } }). EditorPane
 * mounts up to three CodeEditor instances (split main / split pane / normal main),
 * all writing the same slot:
 *
 *  - LAST-registered wins: with a split open, key presses went to the split pane
 *    even when the user was working in the main pane.
 *  - ZOMBIE handlers: when an instance DISPOSED (split closed, tab switched) its
 *    registration was never revoked — if no newer instance re-registered after,
 *    key presses invoked the dead instance's closure (flipping its mcMode, running
 *    its undo snapshots: visible no-ops).
 *
 * The dispatcher makes registration EXPLICIT and identity-aware: CodeEditor
 * registers its handler on mount and unregisters ITS OWN handler on dispose
 * (reference equality — a stale unregister can never revoke a newer instance's
 * handler).
 */
class KeyInsertDispatcher {
    @Volatile
    private var handler: ((String) -> Unit)? = null

    /** Claim the dispatch slot. Last registration wins; store the exact reference. */
    fun register(h: ((String) -> Unit)?) {
        handler = h
    }

    /** Release the slot ONLY if this exact handler instance still owns it. */
    fun unregister(h: ((String) -> Unit)?) {
        if (handler === h) handler = null
    }

    /** Dispatch a key press to the current owner (no-op if unowned). */
    fun dispatch(text: String) {
        handler?.invoke(text)
    }
}
