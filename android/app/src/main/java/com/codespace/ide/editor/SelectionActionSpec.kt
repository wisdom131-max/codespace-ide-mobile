package com.codespace.ide.editor

/**
 * The selection toolbar (2026-10-31, owner request).
 *
 * VS Code puts a floating toolbar on a selection with Ask / Edit / Fix. The ordering of those
 * three is a judgement call, not layout: Fix only makes sense when the selection actually has an
 * error, and Edit is the only one that needs to ask the user a question first. So the rules live
 * here, where they are unit tested, and the composable only draws the result.
 */
enum class SelectionAction { ASK, EDIT, FIX }

internal object SelectionActionSpec {

    /** A stray one-character tap on a symbol is not a selection worth a toolbar. */
    const val MIN_SELECTION_CHARS = 1

    data class Entry(
        val action: SelectionAction,
        val label: String,
        val enabled: Boolean,
        val reason: String? = null,
    )

    /**
     * Fix sits first when it applies, because it is the only one that changes code without the
     * user having to type anything. It disappears entirely without an error: a disabled "Fix"
     * teaches the user to ignore the toolbar.
     */
    fun entries(hasDiagnostic: Boolean, selectionChars: Int): List<Entry> {
        val longEnough = selectionChars >= MIN_SELECTION_CHARS
        val ask = Entry(SelectionAction.ASK, "Ask", longEnough, if (longEnough) null else "Select some code")
        val edit = Entry(SelectionAction.EDIT, "Edit", longEnough, if (longEnough) null else "Select some code")
        val fix = Entry(SelectionAction.FIX, "Fix", longEnough && hasDiagnostic, if (hasDiagnostic) null else "No error in selection")
        return if (hasDiagnostic) listOf(fix, ask, edit) else listOf(ask, edit)
    }

    /** The question the inline field starts with, so the field is never a blank stare. */
    fun placeholder(): String = "How should this change?"

    /**
     * One prompt builder for the three actions. Fix and Edit are code-producing, so their result
     * is staged through write_file and arrives as a reviewable proposal; Ask answers in chat.
     */
    fun prompt(
        action: SelectionAction,
        instruction: String,
        ctx: EditorActionMenuSpec.PromptContext,
    ): String = when (action) {
        SelectionAction.ASK -> EditorActionMenuSpec.build(CodeActionKindAlias.aiExplain, ctx)
        SelectionAction.FIX -> EditorActionMenuSpec.build(EditorActionMenuSpec.FIX_WITH_AI, ctx)
        SelectionAction.EDIT -> {
            val ask = instruction.trim().ifBlank { "Refactor this code." }
            val header = EditorActionMenuSpec.header(ctx)
            header + "Instruction: " + ask + "\n\nCode:\n" + ctx.selectionText +
                EditorActionMenuSpec.STAGING_INSTRUCTION
        }
    }

    // ── Placement ─────────────────────────────────────────────────────────────

    const val TOOLBAR_HEIGHT_DP = 40f
    const val EDGE_MARGIN_DP = 6f

    /**
     * Above the selection when it would otherwise cover the keyboard, below when there is room.
     * Always clamped inside the viewport, so the toolbar can never be dragged out of reach by a
     * line at the very top or bottom of the file.
     */
    fun anchorY(
        selectionTopDp: Float,
        lineHeightDp: Float,
        toolbarHeightDp: Float,
        viewportHeightDp: Float,
    ): Float {
        val below = selectionTopDp + lineHeightDp + EDGE_MARGIN_DP
        val above = selectionTopDp - toolbarHeightDp - EDGE_MARGIN_DP
        val chosen = if (below + toolbarHeightDp <= viewportHeightDp - EDGE_MARGIN_DP) below else above
        val maxY = (viewportHeightDp - toolbarHeightDp - EDGE_MARGIN_DP).coerceAtLeast(EDGE_MARGIN_DP)
        return chosen.coerceIn(EDGE_MARGIN_DP, maxY)
    }
}

/** Local alias so this file does not depend on the LSP package for a single string. */
internal object CodeActionKindAlias {
    const val aiExplain = "ai.explain"
}
