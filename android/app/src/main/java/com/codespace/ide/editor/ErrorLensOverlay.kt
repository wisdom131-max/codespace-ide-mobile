package com.codespace.ide.editor

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.zIndex

/**
 * Extracted ErrorLens overlay with same-line diagnostic stacking.
 *
 * Replaces the inline ErrorLens block in CodeEditor.kt.
 *
 * B6 FIX (2026-09-06) — DIAGNOSTIC TEXT RENDERED ONE LINE ABOVE ITS LINE:
 * this overlay previously had its OWN vertical coordinate math:
 *     rawDocLine * lineHeightDp - vScrollDp
 * That is the exact mistake the gutter/lightbulb fix (eaf67ec) diagnosed and
 * eliminated: (1) raw DOCUMENT line used as a grid index, skipping the
 * VisualLineMapper doc-to-visual mapping (wrong under folds/wrap), (2) a fixed
 * lineHeight grid that drifts from the real Compose text layout geometry
 * (font natural height/leading), and (3) no sticky-header padding term — so with
 * the sticky pad active every message landed one line-height HIGH, exactly the
 * reported "one line above its actual line" symptom.
 * Y now flows through the SAME shared chain the gutter and lightbulb use
 * (EditorLinePositioning doc):
 *   doc line -> visual line (VisualLineMapper) -> content-space top
 *   (TextLayoutResult via EditorLinePositioning) -> viewport (−vScroll +stickyPad).
 * X positioning is unchanged (it was correct); folded lines hide the message
 * (same policy as the lightbulb).
 */
@Composable
internal fun BoxScope.ErrorLensOverlay(
    showErrorLens: Boolean,
    lintErrors: List<LintError>,
    hasCompletions: Boolean,
    value: androidx.compose.ui.text.input.TextFieldValue,
    fontSize: Int,
    // B6: viewport-space inputs — same sources the lightbulb uses (px, not dp).
    vScrollPx: Int,
    stickyPadPx: Float,
    GUTTER_WIDTH: Float,
    displayLineCount: Int,
    textLayoutResult: TextLayoutResult? = null,
    positionMapper: PositionMapper? = null,
    // B6: same mapper the gutter/lightbulb use (folds + wrap).
    visualLineMapper: VisualLineMapper? = null,
) {
    if (!showErrorLens || lintErrors.isEmpty() || hasCompletions) return

    val density = LocalDensity.current
    // Fallback grid — same formula the lightbulb uses; real geometry comes from
    // the textLayoutResult via EditorLinePositioning.
    val lineHeightPx = with(density) { (fontSize * 1.25f).sp.toPx() }

    // Group diagnostics by document line and compute stacking offset
    val lineGroupCount = mutableMapOf<Int, Int>()

    lintErrors.forEach { err ->
        // Find which document line this error is on
        val textBefore = value.text.substring(0, err.start.coerceIn(0, value.text.length))
        val errorLine = textBefore.count { it == '\n' }

        // B6 FIX 1: document line -> visual line via the shared mapper. -1 means the
        // line is inside a folded range — hide the message instead of guessing.
        val visualLine = visualLineMapper?.docToVisualLine(errorLine) ?: errorLine
        if (visualLine < 0) return@forEach

        // STACKING: slot index of this diagnostic among those on the same doc line
        val slotIndex = lineGroupCount.getOrPut(errorLine) { 0 }
        lineGroupCount[errorLine] = slotIndex + 1

        // B6 FIX 2: content-space top from the SAME text layout that renders the code
        // (layout-driven, grid fallback) + real row height for stacking separation.
        val contentTopPx = EditorLinePositioning.visualLineTopPx(textLayoutResult, visualLine, lineHeightPx)
        val rowHeightPx = EditorLinePositioning.visualLineHeightPx(textLayoutResult, visualLine, lineHeightPx)
        val stackHeightPx = rowHeightPx * 1.1f

        // B6 FIX 3: viewport space = content - scroll + sticky pad (was missing the
        // sticky term entirely — the direct cause of the one-line-high drift).
        val lineTopPx = (contentTopPx - vScrollPx + stickyPadPx + slotIndex * stackHeightPx).coerceAtLeast(0f)

        // X-positioning: unchanged (was correct) — end-of-code via the layout when
        // available, else the estimated char-width fallback. Kept in dp as before.
        val lineLeftDp = if (textLayoutResult != null && positionMapper != null) {
            val clampedEnd = positionMapper.lineEnd(errorLine).coerceIn(0, textLayoutResult.layoutInput.text.length)
            (textLayoutResult.getHorizontalPosition(clampedEnd, true) / density) + GUTTER_WIDTH + 8f
        } else {
            val lineStart = value.text.lastIndexOf('\n', (err.start - 1).coerceAtLeast(0)) + 1
            val lineEnd = value.text.indexOf('\n', err.start)
            val lineLength = (if (lineEnd < 0) value.text.length else lineEnd) - lineStart
            (lineLength * fontSize * 0.6f) + GUTTER_WIDTH + 8f
        }

        // Only render if visible in viewport (coarse culling bound, grid approximation)
        if (lineTopPx >= -stackHeightPx && lineTopPx < (displayLineCount + 5) * lineHeightPx) {
            val msgColor = when (err.severity) {
                1 -> Color(0xFFFF6B6B).copy(alpha = 0.7f)  // Error — red
                2 -> Color(0xFFCCA700).copy(alpha = 0.7f)  // Warning — amber
                else -> Color(0xFF75BEFF).copy(alpha = 0.7f) // Info — blue
            }
            androidx.compose.runtime.key(err.start, err.message) {
                Text(
                    text = "  ${if (err.code != null) "[${err.code}] " else ""}${err.message.replace("\n", " ").take(80)}",
                    color = msgColor,
                    fontSize = (fontSize * 0.8f).sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = lineLeftDp.dp, y = with(density) { lineTopPx.toDp() })
                        .zIndex(3f)
                )
            }
        }
    }
}
