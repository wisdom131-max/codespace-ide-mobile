package com.codespace.ide.editor

/**
 * Stale-preview detection (owner order 2026-11-07, item d).
 *
 * A preview tab shows a SLICE read at open time. If the file changes underneath it (the agent, the
 * terminal, a Git checkout), the slice is no longer the file: the banner's line numbers would point
 * at the wrong lines, and worse, the user would be reading a document that no longer exists.
 *
 * The rule is deliberately conservative:
 *   - the file's identity stamp changed, but the page still exists at the same start line
 *       -> RELOAD the page silently (it is still the same part of the same file);
 *   - the file became shorter than the page's start line
 *       -> NOTIFY with a Reload action; never silently jump the reader somewhere else;
 *   - nothing changed
 *       -> nothing.
 *
 * Pure, so the decision table is testable without a device.
 */
internal object PreviewStaleness {

    enum class Action { NOTHING, RELOAD, NOTIFY }

    /** The identity of a file version we can compare cheaply, without reading its content. */
    data class Stamp(val lastModified: Long, val length: Long)

    fun decide(
        shownStamp: Stamp?,
        currentStamp: Stamp?,
        pageStartLine: Int,
        currentLineCount: Int,
    ): Action {
        if (currentStamp == null) return Action.NOTHING // file gone: the tab keeps what it has
        if (shownStamp == null) return Action.RELOAD // first observation after open
        if (shownStamp == currentStamp) return Action.NOTHING
        // The file changed. Can the same page still be shown?
        return if (currentLineCount > pageStartLine) Action.RELOAD else Action.NOTIFY
    }

    /** The banner sentence for a page that no longer exists. */
    fun staleMessage(fileName: String): String =
        "File changed on disk: $fileName is now shorter than the page you are reading. Tap Reload."

    /** The one-line confirmation when a page was refreshed after an external change. */
    fun reloadedMessage(fileName: String, firstLine: Int, lastLine: Int): String =
        "$fileName changed on disk; reloaded lines $firstLine-$lastLine."
}
