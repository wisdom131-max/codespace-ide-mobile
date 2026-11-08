package com.codespace.ide.editor

/**
 * The same Compose ceiling that limits the editor also limits every OTHER surface that renders a
 * whole document in one layout: a chat code block holding a pasted file, a diff pane drawing one
 * row per line in a Column, an output view, a preview.
 *
 * Constraints packs width and height into one 32-bit field and throws above 262,143 px
 * (EditorLayoutLimits.MAX_DIMENSION_PX, verified in Constraints$Companion.bitsNeedForSize), so the
 * safe rule for a NON-lazy surface is the same one the editor uses: never hand a single layout more
 * lines than the screen can represent.
 *
 * LazyColumn/LazyRow surfaces are already safe (they only compose what is visible) and are NOT
 * capped here; capping them would throw away content for no reason.
 */
internal object TextRenderLimits {

    /**
     * Lines a non-lazy text surface may draw at once.
     *
     * 2,000 lines at the tallest realistic line height (about 70 px at the densest profile) is
     * 140,000 px, comfortably inside the guard, and it is far more than a chat block or a diff hunk
     * needs to be useful.
     */
    const val MAX_RENDERED_LINES = 2_000

    /** What the surface says when it stopped early. Never silent, never a lie about the content. */
    fun truncatedNote(shownLines: Int, totalLines: Int): String =
        "Showing the first $shownLines of $totalLines lines. Copy or open the file to see the rest."

    /**
     * The head of [text] that a non-lazy surface may draw, plus the note to show when it stopped.
     * Returns the text untouched when it already fits, so callers pay nothing in the common case.
     */
    fun cap(text: String, maxLines: Int = MAX_RENDERED_LINES): Pair<String, String?> {
        val total = EditorLayoutLimits.lineCount(text)
        if (total <= maxLines) return text to null
        val head = EditorLayoutLimits.previewSlice(text, 0, maxLines)
        return head to truncatedNote(maxLines, total)
    }
}
