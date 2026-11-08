package com.codespace.ide.editor

/**
 * Where a "jump to line N" lands when the target tab is a read-only PREVIEW.
 *
 * Every navigation in the app funnels through one owned jump (FileOwnedJumpState): search results,
 * go to definition, terminal `file:line` links, Source Control open-at-line, Problems navigation and
 * agent/Files Changed links. When the target file is open as a preview, an absolute line is
 * meaningless to the text field, which only holds one page and numbers it from 1. Without this,
 * jumping to line 9,000 of a previewed file dropped the reader on page 1.
 *
 * Pure, so the arithmetic is pinned without a device; the handlers pass through it.
 */
internal object PreviewJump {

    /** The page to show, and the line inside that page (1-based). */
    data class Target(val pageStart: Int, val pageLine: Int, val needsPageSwap: Boolean)

    /**
     * @param requestedLine absolute 1-based line the navigation asked for
     * @param pageLines lines per preview page (0 for a normal, editable tab)
     * @param isPreview the target tab is read-only
     * @param currentPageStart the page the tab is showing right now
     */
    fun plan(
        requestedLine: Int,
        pageLines: Int,
        isPreview: Boolean,
        currentPageStart: Int = 0,
    ): Target {
        if (!isPreview || pageLines <= 0) {
            return Target(pageStart = 0, pageLine = requestedLine.coerceAtLeast(1), needsPageSwap = false)
        }
        val pageStart = PreviewNavigation.pageStartFor(requestedLine, pageLines)
        return Target(
            pageStart = pageStart,
            pageLine = PreviewNavigation.pageLineFor(requestedLine, pageLines),
            needsPageSwap = pageStart != currentPageStart,
        )
    }
}
