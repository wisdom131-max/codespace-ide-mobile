package com.codespace.ide.editor

import androidx.compose.ui.text.TextRange
import com.codespace.ide.diagnostics.AppOutputLog

/**
 * McEditTransaction — the SINGLE DOOR for cursor state while multi-cursor is
 * active (MC-CHOKEPOINT restructure, 2026-09-12, user-approved).
 *
 * VS Code model (researched from microsoft/vscode real source, 2026-09-12):
 * `CursorCollection` owns ALL cursors in one array; every input enters through
 * ONE public chokepoint (`cursor.ts` `_executeEdit`), which (1) builds one edit
 * command PER CURSOR up-front, (2) applies them in a single model transaction,
 * (3) recomputes EVERY cursor's position from the applied edits, (4) writes the
 * whole collection back via `setStates`. There is no public API that can move
 * one cursor without updating the rest.
 *
 * Our Android constraint: BasicTextField owns the primary cursor and the IME
 * owns text input, so we cannot intercept edits BEFORE they happen the way VS
 * Code does. This object is the inverted equivalent: every writer of
 * text/selection in CodeEditor routes its cursor consequences through HERE —
 * one function computes what happens to the extra cursors for each event class:
 *
 *  - USER EDIT (text changed via onValueChange)
 *      -> fan the primary edit out to every extra cursor
 *         (VS Code ReplaceCommand-per-cursor; MultiCursorEngine.applyFanOut).
 *
 *  - SELECTION-ONLY change while MC active (caret moved, no text change —
 *      taps, IME caret quirks; the exact event class that caused the 2026-09-12
 *      stale-extras bug where a delete degenerated into a caret move and the
 *      extras froze at pre-move offsets)
 *      -> VS Code click semantics: COLLAPSE to a single cursor. Android cannot
 *         reliably distinguish soft-keyboard arrow moves (VS Code moves all
 *         cursors) from taps (VS Code collapses) because both arrive as IME
 *         selection events — so we take the SAFE choice: a wrong collapse is
 *         recoverable (re-add cursors); a stale extra silently corrupts text on
 *         the next fan-out.
 *
 *  - EXTERNAL/PROGRAMMATIC edit (split-view peer sync, format, snippets,
 *      find/replace, ghost-text accept, line ops)
 *      -> MAP every extra cursor through the edit, marker-style (VS Code
 *      selection markers). Never fan out — the edit already happened once;
 *      replaying it at each cursor would duplicate it.
 *
 * PERMANENT TRIPWIRE: every MC-active transaction logs one compact
 * [MC-TRIPWIRE] line to the Output log (lsp channel). A writer that mutates
 * text/selection while MC is active WITHOUT routing through this door is
 * visible by the ABSENCE of its tripwire line — and [McEditTransaction.warn]
 * exists for any future raw writer to name itself loudly.
 */
object McEditTransaction {

    /** Result of one chokepoint transaction (text may be rewritten by fan-out). */
    data class Txn(
        val text: String,
        val primary: TextRange,
        val extras: List<TextRange>,
        val kind: String,
    )

    private fun cursorList(ranges: List<TextRange>): String =
        if (ranges.isEmpty()) "none" else ranges.joinToString(",") { it.min.toString() + ".." + it.max }

    private fun trip(kind: String, site: String, detail: String) {
        AppOutputLog.log("[MC-TRIPWIRE] kind=" + kind + " site=" + site + " " + detail, "lsp")
    }

    /**
     * Loud warning for any FUTURE raw writer of text/selection while MC is
     * active that cannot (yet) route through the door. Call this at the top of
     * any such writer so the log names the offender instead of extras silently
     * desyncing.
     */
    fun warn(site: String) {
        AppOutputLog.log(
            "[MC-TRIPWIRE] WARNING: RAW text/selection write via '" + site + "' while multi-cursor " +
                "is active — NOT routed through McEditTransaction. Extra cursors WILL desync. " +
                "Route this writer through the chokepoint.",
            "lsp"
        )
    }

    /**
     * USER edit from onValueChange — the one entry point that fans the primary
     * edit out to every extra cursor (VS Code cursor.ts executeEdits). Handles
     * BOTH event classes so the smoking-gun path (selection-only event)
     * collapses instead of silently bailing (the old applyFanOut early-return).
     */
    fun apply(
        oldText: String,
        newText: String,
        primaryOld: TextRange,
        primaryNew: TextRange,
        extras: List<TextRange>,
        site: String,
    ): Txn {
        if (extras.isEmpty()) return Txn(newText, primaryNew, extras, "noop")
        if (oldText == newText) {
            if (primaryOld == primaryNew) return Txn(newText, primaryNew, extras, "noop")
            trip(
                "selection_collapse", site,
                "primary " + primaryOld.min + ".." + primaryOld.max + " -> " +
                    primaryNew.min + ".." + primaryNew.max + " (caret moved, no text change) — collapsed " +
                    extras.size + " extras (VS Code click semantics): " + cursorList(extras)
            )
            return Txn(newText, primaryNew, emptyList(), "selection_collapse")
        }
        val res = MultiCursorEngine.applyFanOut(oldText, newText, primaryOld, primaryNew, extras)
        trip(
            "user_fan_out", site,
            "len " + oldText.length + "->" + res.text.length + " primary " +
                primaryOld.min + ".." + primaryOld.max + " -> " + res.primary.min + ".." + res.primary.max +
                " extras: " + cursorList(res.extras)
        )
        return Txn(res.text, res.primary, res.extras, "user_fan_out")
    }

    /**
     * EXTERNAL/programmatic text change (peer split-view sync, format, snippet,
     * line ops, find/replace): map every extra cursor through the precise
     * common-prefix/suffix diff, marker-style. Returns the mapped extras only —
     * the caller already computed text + primary selection.
     */
    fun mapExternal(
        oldText: String,
        newText: String,
        extras: List<TextRange>,
        site: String,
    ): List<TextRange> {
        if (extras.isEmpty()) return extras
        val len = newText.length
        val edit = MultiCursorEngine.diffEdit(oldText, newText)
        val mapped = if (edit == null) {
            extras
        } else {
            extras.map { r ->
                // Preserve range orientation: map start/end markers, not min/max.
                TextRange(
                    MultiCursorEngine.shiftPos(r.start, edit).coerceIn(0, len),
                    MultiCursorEngine.shiftPos(r.end, edit).coerceIn(0, len),
                )
            }
        }
        trip(
            "external_map", site,
            "len " + oldText.length + "->" + newText.length + " extras: " + cursorList(mapped)
        )
        return mapped
    }

    /**
     * Selection-only writer while MC active (programmatic caret jumps, tap
     * gesture caret placement): collapse to a single cursor.
     */
    fun collapseSelection(extras: List<TextRange>, site: String): List<TextRange> {
        if (extras.isEmpty()) return extras
        trip(
            "selection_collapse", site,
            "collapsed " + extras.size + " extras: " + cursorList(extras)
        )
        return emptyList()
    }
}
