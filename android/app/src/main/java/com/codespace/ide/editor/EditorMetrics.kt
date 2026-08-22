package com.codespace.ide.editor

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase E: Centralized editor metrics.
 *
 * ALL editor-wide measurement constants (line height, gutter width, char width,
 * minimap dimensions, etc.) are derived from this single data class.
 * Previously these were hard-coded in 30+ locations throughout CodeEditor.kt
 * and EditorOverlays.kt, making font-size changes fragile.
 *
 * Usage in a @Composable:
 *   val metrics = rememberEditorMetrics(fontSize)
 *
 * Then pass `metrics` to overlays, gutter rendering, scroll calculations, etc.
 * Never hard-code `fontSize * 1.25f` or `72f` or `fontSize * 0.6f` again.
 */
data class EditorMetrics(
    /** Source font size in SP units (e.g., 13) */
    val fontSize: Int,

    /** Line height in Dp — every visible text row is this tall. */
    val lineHeightDp: Dp,

    /** Line height in raw pixels — for scroll calculations and overlay positioning. */
    val lineHeightPx: Float,

    /** Approximate character width in pixels — for overlay width estimates. */
    val charWidthPx: Float,

    /** Gutter width in Dp — the left column showing line numbers. */
    val gutterWidthDp: Dp,

    /** Gutter width in raw pixels — for overlay left-padding. */
    val gutterWidthPx: Float,

    /** Tab size in character columns (visual width = tabSize * charWidthPx). */
    val tabSize: Int,

    /** Minimap panel width in Dp. */
    val minimapWidthDp: Dp,

    /** Minimap font size in SP. */
    val minimapFontSize: Int,

    /** Minimap line height in Dp. */
    val minimapLineHeightDp: Dp,

    /** Minimap line height in raw pixels. */
    val minimapLineHeightPx: Float,

    /** Sticky scroll line height in Dp (slightly taller than normal). */
    val stickyLineHeightDp: Dp,

    /** Sticky scroll font size in SP. */
    val stickyFontSize: Int,
) {
    companion object {
        /** Gutter width in Dp — the left column showing line numbers. */
        const val GUTTER_WIDTH_DP = 72f

        /** Line height multiplier applied to fontSize. */
        const val LINE_HEIGHT_MULTIPLIER = 1.25f

        /** Approximate character width as a fraction of fontSize. */
        const val CHAR_WIDTH_MULTIPLIER = 0.6f

        /** Minimap width in Dp. */
        const val MINIMAP_WIDTH_DP = 62f

        /** Minimap font size as a fraction of fontSize. */
        const val MINIMAP_FONT_MULTIPLIER = 0.55f

        /** Minimap line height as a fraction of fontSize. */
        const val MINIMAP_LINE_HEIGHT_MULTIPLIER = 0.6f

        /** Sticky scroll font size as a fraction of fontSize. */
        const val STICKY_FONT_MULTIPLIER = 0.8f

        /** Sticky scroll line height multiplier (taller than normal lines). */
        const val STICKY_LINE_HEIGHT_MULTIPLIER = 1.4f

        /** Default tab size in spaces. */
        const val DEFAULT_TAB_SIZE = 4
    }
}

/**
 * Build EditorMetrics from a font size and Compose Density.
 *
 * Usage:
 *   val density = LocalDensity.current
 *   val metrics = remember(fontSize) { buildEditorMetrics(fontSize, density) }
 *
 * Or use the convenience [rememberEditorMetrics] composable.
 */
fun buildEditorMetrics(fontSize: Int, density: Density): EditorMetrics {
    val lineHeightSp = (fontSize * EditorMetrics.LINE_HEIGHT_MULTIPLIER).sp
    val lineHeightDp = with(density) { lineHeightSp.toDp() }
    val lineHeightPx = with(density) { lineHeightSp.toPx() }

    val gutterWidthDp = EditorMetrics.GUTTER_WIDTH_DP.dp
    val gutterWidthPx = with(density) { gutterWidthDp.toPx() }

    val charWidthPx = fontSize * EditorMetrics.CHAR_WIDTH_MULTIPLIER

    val minimapFontSize = (fontSize * EditorMetrics.MINIMAP_FONT_MULTIPLIER).toInt().coerceAtLeast(1)
    val minimapLineHeightSp = (fontSize * EditorMetrics.MINIMAP_LINE_HEIGHT_MULTIPLIER).sp
    val minimapLineHeightDp = with(density) { minimapLineHeightSp.toDp() }
    val minimapLineHeightPx = with(density) { minimapLineHeightSp.toPx() }

    val stickyFontSize = (fontSize * EditorMetrics.STICKY_FONT_MULTIPLIER).toInt().coerceAtLeast(1)
    val stickyLineHeightSp = (fontSize * EditorMetrics.STICKY_LINE_HEIGHT_MULTIPLIER).sp
    val stickyLineHeightDp = with(density) { stickyLineHeightSp.toDp() }

    return EditorMetrics(
        fontSize = fontSize,
        lineHeightDp = lineHeightDp,
        lineHeightPx = lineHeightPx,
        charWidthPx = charWidthPx,
        gutterWidthDp = gutterWidthDp,
        gutterWidthPx = gutterWidthPx,
        tabSize = EditorMetrics.DEFAULT_TAB_SIZE,
        minimapWidthDp = EditorMetrics.MINIMAP_WIDTH_DP.dp,
        minimapFontSize = minimapFontSize,
        minimapLineHeightDp = minimapLineHeightDp,
        minimapLineHeightPx = minimapLineHeightPx,
        stickyLineHeightDp = stickyLineHeightDp,
        stickyFontSize = stickyFontSize,
    )
}

/**
 * Convenience composable — builds EditorMetrics from the current font size and LocalDensity.
 * Remember keyed on fontSize so it recomputes only when font size changes.
 */
@androidx.compose.runtime.Composable
fun rememberEditorMetrics(fontSize: Int): EditorMetrics {
    val density = LocalDensity.current
    return androidx.compose.runtime.remember(fontSize) {
        buildEditorMetrics(fontSize, density)
    }
}
