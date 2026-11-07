package com.codespace.ide.editor

/**
 * What a preview tab is allowed to do. One object, one answer per question, so the rule is
 * testable in JVM instead of being an `if (tab.readOnly)` sprinkled across call sites.
 *
 * WHY THIS EXISTS (owner order 2026-11-07, addition 3): a preview holds ONLY the lines that fit
 * under the Compose Constraints ceiling. Anything that treats tab content as the whole file would
 * therefore be describing a file that does not exist:
 *
 *   - the language server would index a truncated document and report phantom errors;
 *   - lint, semantic tokens and completion would all reason about the slice;
 *   - an autosave or a format-on-save would WRITE THE SLICE OVER the real file, destroying it;
 *   - an AI edit would rewrite a partial file as if it were complete.
 *
 * So every one of those is refused for a preview tab, and the only component allowed to touch the
 * real file is the one that reads it from disk by path.
 *
 * LINE NUMBERS: a preview page numbers its own lines from 1 (the gutter cannot be offset cheaply
 * inside the single Compose text field), so anything keyed on a line number is also refused rather
 * than silently applied to the wrong real line: breakpoints, bookmarks, blame, folding persistence,
 * code lens, diagnostics navigation, and editor-to-editor jumps. Navigation INTO a preview is
 * handled by PreviewNavigation, which converts an absolute line to (page, page-relative line).
 */
internal object PreviewPolicy {

    /** Send didOpen/didChange for this document? Never for a preview: the slice is not the file. */
    fun shouldSyncToLsp(readOnly: Boolean): Boolean = !readOnly

    /** Publish lint diagnostics? Never for a preview. */
    fun shouldPublishLint(readOnly: Boolean): Boolean = !readOnly

    /** Serve completion, semantic tokens, hover or signature help from the editor? Never. */
    fun shouldServeLanguageFeatures(readOnly: Boolean): Boolean = !readOnly

    /** Autosave, save-all, format-on-save, save-as-AI-edit? Never: that would truncate the file. */
    fun shouldWriteToDisk(readOnly: Boolean): Boolean = !readOnly

    /** Can the tab become dirty? No, so nothing can ask to save it in the first place. */
    fun canGoDirty(readOnly: Boolean): Boolean = !readOnly

    /** Toggle a breakpoint, bookmark or fold on this tab's line numbers? No: they are page lines. */
    fun shouldApplyLineKeyedEdits(readOnly: Boolean): Boolean = !readOnly

    /** Refactors, rename, format, code actions: all rewrite a whole document. Never for a preview. */
    fun shouldApplyDocumentWideEdit(readOnly: Boolean): Boolean = !readOnly

    /** The one sentence the tab carries, so the reason is never a mystery. */
    fun note(readOnly: Boolean, lineRange: String?): String? =
        if (!readOnly) null
        else "Large file: " + (lineRange ?: "showing the first lines") + ", read-only. "
}

/** Converting an absolute line in the real file to the page that holds it. */
internal object PreviewNavigation {

    /**
     * Which page holds absolute [line] (1-based), given [pageLines] per page.
     * Returns the 0-based start line of that page.
     */
    fun pageStartFor(line: Int, pageLines: Int): Int {
        if (pageLines <= 0) return 0
        val zeroBased = (line - 1).coerceAtLeast(0)
        return (zeroBased / pageLines) * pageLines
    }

    /** The line number to show inside that page (1-based) for absolute [line]. */
    fun pageLineFor(line: Int, pageLines: Int): Int {
        if (pageLines <= 0) return 1
        val zeroBased = (line - 1).coerceAtLeast(0)
        return (zeroBased % pageLines) + 1
    }

    /** Absolute line for a page-relative line. Inverse of [pageLineFor]. */
    fun absoluteLineFor(pageStart: Int, pageLine: Int): Int =
        pageStart + pageLine.coerceAtLeast(1)
}
