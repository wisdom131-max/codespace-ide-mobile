package com.codespace.ide.editor

/**
 * 2026-11-05: the caret's last known position per file, plus a one-shot request to put it back.
 *
 * Why this exists. `EditorUndoHost` documents that a snapshot carries its selection, so an undo
 * restores the caret the way VS Code restores it with the model version. The pane was honoured on
 * the way IN but not on the way OUT: it built its snapshot with `TextRange.Zero`, so a menu undo
 * jumped the caret to offset 0, and even a correct snapshot's selection was never applied to the
 * editor, because the pane can only hand the editor new text.
 *
 * Two pieces, both deliberately pure so they are unit tested rather than eyeballed on a device:
 *  - [remember]: the editor reports its selection as it moves (one call, no logic in the composable).
 *  - [requestRestore] / [consumeRestore]: the pane says "the next content sync for this file should
 *    land the caret here", and the editor consumes it exactly once, on that sync.
 *
 * Single-shot on purpose: a restore that persisted would fight the user's next caret tap.
 */
internal object EditorSelectionMemory {

    /** Caret/selection offsets, plain ints so this file needs no Compose import. */
    data class Sel(val start: Int, val end: Int)

    private val last = HashMap<String, Sel>()
    private val pending = HashMap<String, Sel>()

    /** Called as the caret moves; never creates a restore request. */
    @Synchronized
    fun remember(path: String, start: Int, end: Int) {
        if (path.isEmpty()) return
        last[path] = Sel(start, end)
    }

    /** The last selection seen for this file, or null when the editor has not reported one yet. */
    @Synchronized
    fun selectionFor(path: String): Sel? = last[path]

    /** Ask for the next content sync of [path] to place the caret here. Replaces any earlier request. */
    @Synchronized
    fun requestRestore(path: String, start: Int, end: Int) {
        if (path.isEmpty()) return
        pending[path] = Sel(start, end)
    }

    /** Take the pending request for [path], if any. Consumes it: a second call returns null. */
    @Synchronized
    fun consumeRestore(path: String): Sel? = pending.remove(path)

    /** A rename keeps the file's caret history: the old key is dropped, the entry moves with it. */
    @Synchronized
    fun rekey(oldPath: String, newPath: String) {
        if (oldPath.isEmpty() || newPath.isEmpty() || oldPath == newPath) return
        last.remove(oldPath)?.let { last[newPath] = it }
        pending.remove(oldPath)?.let { pending[newPath] = it }
    }

    @Synchronized
    fun forget(path: String) {
        last.remove(path)
        pending.remove(path)
    }

    @Synchronized
    internal fun resetForTest() {
        last.clear()
        pending.clear()
    }

    /**
     * What the caret should be after a content sync: a pending restore if the pane asked for one,
     * else the offsets the sync itself mapped from the previous selection. One call so the 64 KB
     * CodeEditor body does not grow a branch.
     */
    @Synchronized
    fun resolveRestore(path: String, mappedStart: Int, mappedEnd: Int, length: Int): Sel {
        val pendingSel = pending.remove(path)
        return clamp(pendingSel ?: Sel(mappedStart, mappedEnd), length)
    }

    /** Clamp a remembered selection into text that may have shrunk since it was recorded. */
    fun clamp(sel: Sel, length: Int): Sel {
        val s = sel.start.coerceIn(0, length)
        val e = sel.end.coerceIn(0, length)
        return if (s <= e) Sel(s, e) else Sel(e, s)
    }
}
