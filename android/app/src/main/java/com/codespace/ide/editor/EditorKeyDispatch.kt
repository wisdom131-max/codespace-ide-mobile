package com.codespace.ide.editor

/**
 * BATCH4-P1 (dead-chord dispatch): the registry of which EditorActions have
 * working hardware-keyboard chord dispatch, the DOCUMENTED exclusions, and
 * the shared dispatch helpers.
 *
 * Why this file exists: 39 actions are bindable in Settings but only 10 had
 * registry-backed chord dispatch (the dead-chord report), and CodeEditor.kt
 * sits at ~338KB near the JVM 64KB method limit — so the MAP and the helpers
 * live HERE, not in the composable. The when() blocks in CodeEditor stay
 * one-liners that call the SAME functions the menus and buttons call; this
 * file holds no UI.
 *
 * Regression contract (EditorKeyDispatchTest): every EditorAction must be in
 * DISPATCHED (the chord fires the same function the menu/button calls) or in
 * EXCLUDED with an owner-visible reason — adding an action to the enum
 * without wiring or documenting it fails the test, so the dead-chord state
 * (29 of 39 chords doing nothing) can never be reintroduced silently.
 */
object EditorKeyDispatch {

    /**
     * Actions with working chord dispatch. The 10 pre-existing registry-backed
     * actions plus one wave per P1 commit.
     */
    val DISPATCHED: Set<EditorAction> = setOf(
        // Pre-existing registry dispatch (ctrl/alt branches in CodeEditor)
        EditorAction.UNDO,
        EditorAction.REDO,
        EditorAction.DUPLICATE_LINE,
        EditorAction.COMMENT_TOGGLE,
        EditorAction.DELETE_LINE,
        EditorAction.FIND,
        EditorAction.GO_TO_LINE,
        EditorAction.SAVE,
        EditorAction.MOVE_LINE_UP,
        EditorAction.MOVE_LINE_DOWN,
        // P1a — find/navigate (F3, Shift+F3, F12, F2)
        EditorAction.FIND_NEXT,
        EditorAction.FIND_PREVIOUS,
        EditorAction.GO_TO_DEFINITION,
        EditorAction.RENAME,
    )

    /**
     * Every action NOT yet dispatched, each with the reason. Shrinks one wave
     * per P1 commit; what remains is the honest residue for the owner review.
     */
    val EXCLUDED: Map<EditorAction, String> = buildMap {
        // Gray zone — the IME/platform may handle these natively; wiring them via
        // the registry risks double-handling. Owner per-row ruling pending.
        put(EditorAction.COPY, "IME/platform may handle natively; double-handling risk, owner per-row ruling pending")
        put(EditorAction.PASTE, "IME/platform may handle natively; double-handling risk, owner per-row ruling pending")
        put(EditorAction.CUT, "IME/platform may handle natively; double-handling risk, owner per-row ruling pending")
        put(EditorAction.SELECT_ALL, "IME/platform may handle natively; double-handling risk, owner per-row ruling pending")
        // Handled on the editor's built-in key paths BEFORE the registry consults,
        // so a registry dispatch would duplicate them.
        put(EditorAction.TAB_ACCEPT_COMPLETION, "handled on the built-in Tab path before the registry (completion accept, snippet nav)")
        put(EditorAction.ESCAPE, "handled by the editor's own dismiss paths before the registry")
        put(EditorAction.SMART_ENTER, "handled by the editor's own Enter path (auto-indent) before the registry")
        put(EditorAction.INDENT, "handled by the built-in Tab path before the registry")
        put(EditorAction.UNINDENT, "handled by the built-in Shift+Tab path before the registry")
        // Rest of the P1 waves — this batch, next commits.
        put(EditorAction.FORMAT, "wired in the P1b edit wave (this batch)")
        put(EditorAction.QUICK_FIX, "wired in the P1b edit wave (this batch)")
        put(EditorAction.ORGANIZE_IMPORTS, "wired in the P1b edit wave (this batch)")
        put(EditorAction.TOGGLE_WORD_WRAP, "wired in the P1c view wave (this batch)")
        put(EditorAction.ZOOM_IN, "wired in the P1c view wave (this batch)")
        put(EditorAction.ZOOM_OUT, "wired in the P1c view wave (this batch)")
        put(EditorAction.ZOOM_RESET, "wired in the P1c view wave (this batch)")
        put(EditorAction.COMMAND_PALETTE, "wired in the P1c view wave (this batch)")
        put(EditorAction.CLOSE_TAB, "wired in the P1d tab wave (this batch)")
        put(EditorAction.NEXT_TAB, "wired in the P1d tab wave (this batch)")
        put(EditorAction.PREV_TAB, "wired in the P1d tab wave (this batch)")
        // Honest residue — feature exists but chord wiring not in this batch.
        put(EditorAction.REPLACE, "the Find bar opens with FIND (chord-wired); a dedicated Replace chord is a future wave")
        put(EditorAction.SHOW_HOVER, "hover exists via long-press; chord wiring was not in the owner's P1 list")
        put(EditorAction.OPEN_FILE, "quick-open exists via the palette's file mode; needs palette-open-with-mode wiring, future wave")
        // Unknowns — no verified feature function to call yet.
        put(EditorAction.SELECT_WORD, "no default chord and no dedicated function; candidate for a future wave")
        put(EditorAction.SELECT_LINE, "no default chord and no dedicated function; candidate for a future wave")
        put(EditorAction.TOGGLE_INLAY_HINTS, "inlay hints exist but no verified toggle function; candidate for a future wave")
    }

    /**
     * Find-bar match stepping, shared by the FindReplaceBar nav buttons AND
     * the FIND_NEXT/FIND_PREVIOUS chords — both paths call THIS function
     * (BATCH4-P1a condition: same function, no separate code path).
     */
    fun stepMatchIndex(matchCount: Int, currentIndex: Int, delta: Int): Int {
        if (matchCount <= 0) return 0
        return (((currentIndex + delta) % matchCount) + matchCount) % matchCount
    }
}
