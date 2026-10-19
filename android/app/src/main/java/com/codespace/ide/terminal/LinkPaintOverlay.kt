package com.codespace.ide.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalBuffer

/**
 * D1 RESOLVED-SPAN OVERLAY (advisor round 2026-10-19: "for others, show the
 * resolved span after resolution").
 *
 * The paint pass (TerminalLinkPaint) can only highlight what DETECTION can see:
 * quoted/escaped spans whole (explicit forms), the extension-bearing chunk for
 * unquoted space paths. The FULL unquoted span is only known once the resolver
 * picked a winner by file existence. After a successful tap resolve, TerminalPane
 * maps the winning span from the LOGICAL wrapped line into per-row CELL pieces
 * and hands them to TerminalView.setLinkOverlay — the renderer paints them with
 * the same link style until the TTL expires.
 *
 * Mapping (JVM-tested in TerminalLinkPaint.overlayCells): cell widths are
 * TERMINAL CELL widths (WcWidth), never UTF-16 char counts.
 */
object LinkPaintOverlay {

    /** Display lifetime of one resolved-span overlay (advisor-visible seconds,
     *  bounded so a stale highlight never survives long terminal sessions). */
    const val TTL_MS = 30_000L

    /**
     * The overlay pieces for a resolved span, or null when it cannot be mapped.
     * Pure mapping aside from the buffer reads (all in-memory, main thread).
     *
     * @param emulator the tap's TerminalView emulator (columns + buffer)
     * @param tapRow the EXTERNAL row that was tapped (piece rows are absolute)
     * @param logicalText the FULL wrapped logical line (what getWrappedLineAtLocation returned)
     * @param spanStart the winning span's char start in [logicalText]
     * @param spanEnd the winning span's char end in [logicalText] (exclusive)
     */
    fun piecesFor(
        emulator: TerminalEmulator?,
        tapRow: Int,
        logicalText: String?,
        spanStart: Int,
        spanEnd: Int,
    ): IntArray? {
        if (emulator == null || logicalText == null) return null
        if (spanStart < 0 || spanEnd <= spanStart) return null
        val columns = emulator.mColumns
        if (columns <= 0) return null
        val screen: TerminalBuffer = emulator.screen ?: return null
        // First row of the LOGICAL line: walk up while the previous row is a
        // wrapped continuation (same discovery getWrappedLineAtLocation uses —
        // a wrapped continuation joins without a '\n' in the selection text).
        var firstRow = tapRow
        while (firstRow > 0) {
            val joined = try {
                screen.getSelectedText(0, firstRow - 1, columns, firstRow, true, true)
            } catch (_: Exception) { break }
            if (joined.contains("\n")) break
            firstRow--
        }
        val cells = TerminalLinkPaint.overlayCells(logicalText, spanStart, spanEnd, columns)
        if (cells.isEmpty()) return null
        val out = IntArray(cells.size * 3)
        for ((i, c) in cells.withIndex()) {
            out[i * 3] = firstRow + c.row
            out[i * 3 + 1] = c.cellStart
            out[i * 3 + 2] = c.cellEnd
        }
        return out
    }

    fun expiresAt(now: Long = System.currentTimeMillis()): Long = now + TTL_MS
}
