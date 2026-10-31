package com.codespace.ide.editor

import android.content.Context
import com.codespace.ide.lsp.CodeActionKind

/**
 * The editor's AI actions, as a spec instead of a hardcoded dropdown body (2026-10-31).
 *
 * Why this exists: the ⋮ chip, the lightbulb menu and the long-press menu each carried their own
 * copy of "which AI action, what label, what prompt", so a change in one was invisible in the
 * others and the chip grew to carry every LSP action too. One list, one prompt builder, tested.
 *
 * Prompt policy (owner decision, 2026-10-31): an action that produces CODE must land through the
 * agent's write tool, which stages it as a proposal the user reviews and Applies. An action that
 * produces PROSE stays in chat. That split is the difference between an answer you can use and a
 * wall of text you have to copy by hand.
 */
enum class EditorChipState {
    /** Fully visible. */
    SOLID,

    /** Tappable but dimmed, so it stops competing with the text it floats over. */
    GHOST,

    /** Reduced to a small handle: out of the way, never out of reach. */
    HIDDEN,
}

internal data class ChipEntry(
    val kind: String,
    val label: String,
    val enabled: Boolean = true,
    val reason: String? = null,
)

internal object EditorActionMenuSpec {

    /** Sentinel for the inline "Fix with AI" row, which is not an LSP kind. */
    const val FIX_WITH_AI = "ai.fix"

    const val MORE_LABEL = "More actions..."

    /** Order is the menu the owner sees: the fix first, then the transforms, then the door. */
    val AI_ACTIONS: List<Pair<String, String>> = listOf(
        CodeActionKind.AIExplain to "Explain Code",
        CodeActionKind.AIGenerateDoc to "Generate Documentation",
        CodeActionKind.AIGenerateTests to "Generate Unit Tests",
        CodeActionKind.AIOptimize to "Optimize Code",
        CodeActionKind.AIRewrite to "Rewrite Code",
        CodeActionKind.AISimplify to "Simplify Code",
        CodeActionKind.AIRefactor to "Refactor with AI",
        CodeActionKind.AIAddComments to "Add Comments",
        CodeActionKind.AIImprovePerf to "Improve Performance",
    )

    /** Kinds that produce code the user must review. Everything else answers in chat. */
    val CODE_PRODUCING: Set<String> = setOf(
        FIX_WITH_AI,
        CodeActionKind.AIGenerateDoc,
        CodeActionKind.AIGenerateTests,
        CodeActionKind.AIOptimize,
        CodeActionKind.AIRewrite,
        CodeActionKind.AISimplify,
        CodeActionKind.AIRefactor,
        CodeActionKind.AIAddComments,
        CodeActionKind.AIImprovePerf,
    )

    fun isCodeProducing(kind: String?): Boolean = kind != null && CODE_PRODUCING.contains(kind)

    /**
     * The chip's menu: AI only. "Fix with AI" is ALWAYS the first row (disabled with a reason when
     * there is no error nearby) so the list never reorders under the user's thumb between taps.
     */
    fun entries(hasDiagnostic: Boolean): List<ChipEntry> {
        val fix = ChipEntry(
            kind = FIX_WITH_AI,
            label = "Fix with AI",
            enabled = hasDiagnostic,
            reason = if (hasDiagnostic) null else "No error on this line",
        )
        return listOf(fix) + AI_ACTIONS.map { (kind, label) -> ChipEntry(kind, label) }
    }

    // ── Chip state ────────────────────────────────────────────────────────────

    fun nextState(state: EditorChipState): EditorChipState = when (state) {
        EditorChipState.SOLID -> EditorChipState.GHOST
        EditorChipState.GHOST -> EditorChipState.HIDDEN
        EditorChipState.HIDDEN -> EditorChipState.SOLID
    }

    fun alpha(state: EditorChipState): Float = when (state) {
        EditorChipState.SOLID -> 1f
        EditorChipState.GHOST -> 0.35f
        EditorChipState.HIDDEN -> 0.25f
    }

    /** HIDDEN shrinks to a handle instead of vanishing, so it can always be brought back. */
    fun diameterDp(state: EditorChipState): Int = when (state) {
        EditorChipState.HIDDEN -> 14
        else -> 36
    }

    /**
     * When the chip is worth showing at all. It used to appear on ANY word under the caret, which
     * on a phone means a control sitting over the text at all times. A caret on a word is no
     * longer enough on its own: there must be a selection, an error, or the caret inside an
     * identifier the user could plausibly be asking about.
     */
    fun shouldShow(
        state: EditorChipState,
        hasSelection: Boolean,
        hasWordUnderCursor: Boolean,
        hasNearbyDiagnostic: Boolean,
        imeVisible: Boolean,
    ): Boolean {
        if (imeVisible) return false
        // The hidden handle is always drawn; it is the way back.
        if (state == EditorChipState.HIDDEN) return true
        return hasSelection || hasNearbyDiagnostic || hasWordUnderCursor
    }

    // ── Placement ─────────────────────────────────────────────────────────────

    const val EDGE_MARGIN_DP = 4f

    /** Keeps the chip inside the editor after a drag, a rotation or a window resize. */
    fun clamp(x: Float, y: Float, chipDp: Int, widthDp: Float, heightDp: Float): Pair<Float, Float> {
        val maxX = (widthDp - chipDp - EDGE_MARGIN_DP).coerceAtLeast(EDGE_MARGIN_DP)
        val maxY = (heightDp - chipDp - EDGE_MARGIN_DP).coerceAtLeast(EDGE_MARGIN_DP)
        return x.coerceIn(EDGE_MARGIN_DP, maxX) to y.coerceIn(EDGE_MARGIN_DP, maxY)
    }

    /**
     * Release behaviour: snap to whichever vertical edge the chip is closer to, so a chip the user
     * parked mid-text does not sit on the words it is meant to act on.
     */
    fun snapped(x: Float, y: Float, chipDp: Int, widthDp: Float, heightDp: Float): Pair<Float, Float> {
        val (cx, cy) = clamp(x, y, chipDp, widthDp, heightDp)
        val left = EDGE_MARGIN_DP
        val right = (widthDp - chipDp - EDGE_MARGIN_DP).coerceAtLeast(EDGE_MARGIN_DP)
        return if (kotlin.math.abs(cx - left) <= kotlin.math.abs(right - cx)) left to cy else right to cy
    }

    // ── Prompt building ───────────────────────────────────────────────────────

    internal data class PromptContext(
        val fileName: String,
        val languageName: String,
        val imports: String,
        val selectionText: String,
        val hasSelection: Boolean,
        val diagnosticMessage: String? = null,
        val diagnosticLine: Int? = null,
    )

    /** Shared header: file, language, imports, and what part of the file is in scope. */
    fun header(ctx: PromptContext): String {
        val scope = if (ctx.hasSelection) "selected text" else "current line"
        val imports = if (ctx.imports.isNotBlank()) "Imports:\n" + ctx.imports + "\n" else ""
        return "File: " + ctx.fileName + " (" + ctx.languageName + ")\n" + imports +
            "Selection (" + scope + "):\n"
    }

    /**
     * Build the context the three menus share, from primitives only, so it is testable and so no
     * caller can disagree with another about what "the selection" means.
     */
    fun contextFor(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        fileName: String?,
        languageName: String,
        diagnosticMessage: String? = null,
        diagnosticLine: Int? = null,
    ): PromptContext {
        val start = selectionStart.coerceIn(0, text.length)
        val end = selectionEnd.coerceIn(0, text.length)
        val hasSelection = start != end
        val selectionText = if (hasSelection) {
            text.substring(kotlin.math.min(start, end), kotlin.math.max(start, end))
        } else {
            val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            text.substring(lineStart.coerceAtLeast(0), lineEnd.coerceAtMost(text.length))
        }
        val imports = text.lines().take(30).filter {
            val t = it.trim()
            t.startsWith("import ") || t.startsWith("from ") || t.startsWith("package ") ||
                t.startsWith("#include")
        }.joinToString("\n")
        return PromptContext(
            fileName = fileName?.substringAfterLast('/') ?: "untitled",
            languageName = languageName,
            imports = imports,
            selectionText = selectionText,
            hasSelection = hasSelection,
            diagnosticMessage = diagnosticMessage,
            diagnosticLine = diagnosticLine,
        )
    }

    fun build(kind: String, ctx: PromptContext): String {
        val head = header(ctx)
        val body = when (kind) {
            FIX_WITH_AI -> head + diagnosticLine(ctx) + "Fix this error:\n" + ctx.selectionText
            CodeActionKind.AIExplain -> head + "Explain this code:\n" + ctx.selectionText
            CodeActionKind.AIExplainError -> head + diagnosticLine(ctx) +
                "Explain the error in this code:\n" + ctx.selectionText
            CodeActionKind.AIGenerateDoc -> head + "Generate documentation for this code:\n" + ctx.selectionText
            CodeActionKind.AIGenerateTests -> head + "Generate unit tests for this code:\n" + ctx.selectionText
            CodeActionKind.AIOptimize -> head + "Optimize this code for better performance:\n" + ctx.selectionText
            CodeActionKind.AIRewrite -> head + "Rewrite this code for clarity:\n" + ctx.selectionText
            CodeActionKind.AISimplify -> head + "Simplify this code:\n" + ctx.selectionText
            CodeActionKind.AIRefactor -> head + "Refactor this code for better structure:\n" + ctx.selectionText
            CodeActionKind.AIAddComments -> head + "Add inline comments to this code:\n" + ctx.selectionText
            CodeActionKind.AIImprovePerf -> head + "Suggest performance improvements for:\n" + ctx.selectionText
            else -> head + kind + ":\n" + ctx.selectionText
        }
        return if (isCodeProducing(kind)) body + STAGING_INSTRUCTION else body
    }

    private fun diagnosticLine(ctx: PromptContext): String {
        val msg = ctx.diagnosticMessage ?: return ""
        val where = ctx.diagnosticLine?.let { " on line " + it } ?: ""
        return "Error" + where + ": " + msg + "\n"
    }

    /**
     * The instruction that turns an AI answer into a reviewable proposal. `write_file` stages into
     * PendingChangesStore, so the user still gets the apply gate they already trust.
     */
    const val STAGING_INSTRUCTION =
        "\n\nApply the result with your write_file tool at the path in the header, changing only " +
            "the region shown, so it arrives as a proposal the user can review and Apply. Do not " +
            "paste the whole file into your answer; one short summary is enough."
}

/** Per-install chip state and position. Persisted so a drag survives a restart. */
internal object EditorChipPrefs {
    private const val PREFS = "editor_chip"
    private const val KEY_STATE = "state"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun state(context: Context): EditorChipState = try {
        val raw = prefs(context).getString(KEY_STATE, null)
        // The owner's complaint was a chip over the text, so a fresh install starts Ghost.
        if (raw == null) EditorChipState.GHOST else EditorChipState.valueOf(raw)
    } catch (_: Exception) {
        EditorChipState.GHOST
    }

    fun setState(context: Context, state: EditorChipState) {
        try {
            prefs(context).edit().putString(KEY_STATE, state.name).apply()
        } catch (_: Exception) {
        }
    }

    /** Null = never dragged; the host picks the default corner. */
    fun position(context: Context): Pair<Float, Float>? = try {
        val p = prefs(context)
        if (!p.contains(KEY_X)) null else p.getFloat(KEY_X, 0f) to p.getFloat(KEY_Y, 0f)
    } catch (_: Exception) {
        null
    }

    fun setPosition(context: Context, x: Float, y: Float) {
        try {
            prefs(context).edit().putFloat(KEY_X, x).putFloat(KEY_Y, y).apply()
        } catch (_: Exception) {
        }
    }
}
