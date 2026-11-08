package com.codespace.ide.editor

/**
 * The active document's shape, remembered so a zoom gesture made in the SHELL can ask the same
 * question the editor asks: "would this font size fit the document that is open right now?"
 *
 * The zoom control lives in the shell (ProjectShellScreen), which does not hold the text. Rather
 * than reach into the editor, the editor writes these three numbers whenever the active document or
 * font size changes, and the zoom handler reads them.
 *
 * Deliberately plain: no Android types, no state framework, so the clamp is JVM-testable.
 */
internal object LayoutZoomMemory {

    @Volatile var lineCount: Int = 1
    @Volatile var avgLineChars: Int = 80
    @Volatile var wrap: Boolean = false
    @Volatile private var fontSizeSp: Float = 13f
    @Volatile private var density: Float = 2.75f
    @Volatile private var fontScale: Float = 1f
    @Volatile private var widthPx: Int = 1080

    fun record(
        lineCount: Int,
        avgLineChars: Int,
        wrap: Boolean,
        fontSizeSp: Float,
        density: Float,
        fontScale: Float,
        widthPx: Int,
    ) {
        this.lineCount = lineCount.coerceAtLeast(1)
        this.avgLineChars = avgLineChars.coerceAtLeast(1)
        this.wrap = wrap
        this.fontSizeSp = fontSizeSp
        this.density = density
        this.fontScale = fontScale
        this.widthPx = widthPx
    }

    fun metrics(): EditorLayoutLimits.Metrics = EditorLayoutLimits.Metrics(
        fontSizeSp = fontSizeSp,
        density = density,
        fontScale = fontScale,
        widthPx = widthPx,
    )

    /** Test-only reset so one test cannot leak state into the next. */
    fun resetForTest() {
        lineCount = 1
        avgLineChars = 80
        wrap = false
        fontSizeSp = 13f
        density = 2.75f
        fontScale = 1f
        widthPx = 1080
    }
}
