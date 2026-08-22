package com.codespace.ide.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * Phase F+G extraction: Keeps CodeEditor.kt under the 64KB JVM bytecode limit
 * by moving the DecorationStore construction, VisualLineMapper construction,
 * and decoration sync LaunchedEffects into a separate composable.
 *
 * Called from CodeEditor.kt as a single line.
 */
@Composable
internal fun rememberDecorationSetup(
    text: String,
    foldedLineIndices: Set<Int>,
    wordWrap: Boolean,
    fontSize: Int,
    semanticTokens: List<com.codespace.ide.lsp.SemanticTokensApplier.SemanticRange>,
    bookmarkedLines: Set<Int>,
    foldedRanges: Set<Int>,
    lspFoldingRanges: List<Pair<Int, Int>>
): Pair<DecorationStore, VisualLineMapper> {
    val decorationStore = remember { DecorationStore() }
    val visualLineMapper = remember(text, foldedLineIndices, wordWrap, fontSize) {
        val charWidthPx = fontSize * EditorMetrics.CHAR_WIDTH_MULTIPLIER
        VisualLineMapper(
            text = text,
            foldedLineIndices = foldedLineIndices,
            wrapWidthPx = 0f,
            charWidthPx = charWidthPx,
            tabSize = EditorMetrics.DEFAULT_TAB_SIZE,
        )
    }
    LaunchedEffect(semanticTokens) { decorationStore.updateSemanticTokens(semanticTokens) }
    LaunchedEffect(bookmarkedLines) { decorationStore.updateBookmarks(bookmarkedLines) }
    LaunchedEffect(foldedRanges) { decorationStore.updateFoldedLines(foldedRanges) }
    LaunchedEffect(lspFoldingRanges) {
        decorationStore.updateFoldRanges(lspFoldingRanges.map { FoldRange(it.first, it.second) })
    }
    return Pair(decorationStore, visualLineMapper)
}
