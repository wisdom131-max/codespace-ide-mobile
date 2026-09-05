package com.codespace.ide.editor

import androidx.compose.ui.text.TextLayoutResult

/**
 * GUTTER-ALIGN / LIGHTBULB FIX: single source of truth for "where is line N".
 *
 * Every vertical coordinate in this editor must derive from the SAME chain:
 *   document line -> visual line (VisualLineMapper: folds + wrap)
 *   -> content-space top/height (TextLayoutResult - the layout that actually
 *   renders the code) -> viewport space (subtract vScroll, add sticky pad).
 *
 * This mirrors the architecture verified in VS Code's source (microsoft/vscode):
 * the text lines view part (viewLines/view.ts) and the margin overlays
 * (viewOverlays.ts - line numbers, glyph margin) BOTH use the same
 * VisibleLinesCollection from viewLayer.ts, and both position their per-line
 * DOM nodes with the identical layoutLine(lineNumber, deltaTop, lineHeight)
 * call, where deltaTop comes from ViewLayout.getVerticalOffsetForLineNumber
 * and lineHeight from viewLayout.getLineHeightForLineNumber. "Gutter matches
 * text" is therefore a structural given in VS Code - the margin is not a
 * separate coordinate system. Widget positioning (lightbulb as IContentWidget
 * or glyph-margin decoration) is layered ON TOP of that same data.
 *
 * It also mirrors Sora Editor's EditorRenderer: line numbers are drawn at
 * (getRowTop(row) + getRowBottom(row)) / 2 from the same ContentLayout rows
 * used to draw the text.
 *
 * Previously this codebase had THREE independent coordinate computations that
 * drifted apart:
 *   1. The gutter: a fixed fontSize * 1.25f grid of rows (drifts from the
 *      text's actual line geometry - font natural height, first-line font
 *      padding, leading distribution).
 *   2. The lightbulb: raw document line used as a visual line index, ignoring
 *      the sticky pad entirely.
 *   3. Inlay hints: correct doc-to-visual mapping but ignoring the sticky pad.
 */
object EditorLinePositioning {

    /**
     * Content-space top (px) of [visualLine] - layout-driven, grid fallback.
     * Content space = the coordinate space of the text layout (0 = top of the
     * first line, before scroll and before the sticky pad). The gutter lives
     * inside the scrolled Row, so it uses these values directly; viewport
     * overlays must additionally subtract vScroll and add the sticky pad.
     */
    fun visualLineTopPx(
        layout: TextLayoutResult?,
        visualLine: Int,
        fallbackLineHeightPx: Float,
    ): Float {
        if (layout != null && visualLine >= 0 && visualLine < layout.lineCount) {
            return layout.getLineTop(visualLine)
        }
        return visualLine * fallbackLineHeightPx
    }

    /**
     * Content-space height (px) of [visualLine] - layout-driven, grid fallback.
     * Uses the real distance to the next line's top so rows follow the text
     * even when line heights are non-uniform (last line, wrapped segments).
     */
    fun visualLineHeightPx(
        layout: TextLayoutResult?,
        visualLine: Int,
        fallbackLineHeightPx: Float,
    ): Float {
        if (layout == null || visualLine < 0 || visualLine >= layout.lineCount) {
            return fallbackLineHeightPx
        }
        if (visualLine + 1 <= layout.lineCount - 1) {
            return layout.getLineTop(visualLine + 1) - layout.getLineTop(visualLine)
        }
        // Last line: measure with getLineBottom.
        return layout.getLineBottom(visualLine) - layout.getLineTop(visualLine)
    }
}
