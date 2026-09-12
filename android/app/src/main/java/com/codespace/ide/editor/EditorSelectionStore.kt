package com.codespace.ide.editor

/**
 * R4-CHAT-PARITY: live editor selection, published for the chat attach picker.
 *
 * CodeEditor writes the current non-empty selection here on every selection
 * change (single guarded call in the existing onValueChange path — no inline
 * composable-body code). The chat panel's attach picker reads the latest
 * snapshot and offers "Attach current editor selection" (VS Code parity).
 *
 * Deliberately tiny: one slot, thread-safe-enough (UI-thread writes + reads),
 * no history — only the LATEST selection exists.
 */
object EditorSelectionStore {

    data class Snapshot(
        val filePath: String,
        val selText: String,
    )

    @Volatile
    private var last: Snapshot? = null

    /** Max selection size we'll publish (huge selections stay unattached). */
    private const val MAX_SEL_CHARS = 20000

    fun record(filePath: String?, text: String, start: Int, end: Int) {
        if (filePath.isNullOrBlank()) return
        if (end <= start) return
        if (end - start > MAX_SEL_CHARS) return
        val sel = try {
            text.substring(start, end)
        } catch (_: Exception) {
            return
        }
        if (sel.isBlank()) return
        last = Snapshot(filePath, sel)
    }

    /** Latest selection snapshot, or null when the editor has no live selection. */
    fun take(): Snapshot? = last

    fun clear() { last = null }
}
