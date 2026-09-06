package com.codespace.ide.editor

import androidx.compose.ui.text.TextRange

/**
 * MultiCursorEngine — VS Code-faithful multi-cursor edit transactions (Plan A, 2026-09-06).
 *
 * Ported from real VS Code source (src/vs/editor/common/cursor/):
 *  - cursorCollection.ts CursorCollection.normalize(): cursors sorted by start,
 *    touching cursors merged when either is collapsed, otherwise only true
 *    overlaps merge.
 *  - cursorTypeEditOperations.ts SimpleCharacterTypeOperation.getEdits():
 *    `for each selection: commands[i] = new ReplaceCommand(selections[i], ch)` —
 *    ONE edit per cursor built up-front.
 *  - cursor.ts executeEdits(): all edits apply in a SINGLE model transaction
 *    (pushEditOperations) with a cursor-state-computer recomputing every cursor
 *    atomically; the undo stack records ONE entry for the whole set.
 *
 * Our equivalent of the transaction: BasicTextField has already applied the
 * PRIMARY edit when onValueChange fires. We diff old->new into ONE precise edit
 * triple (start/deleted/inserted — replaces the old length-delta guessing which
 * could not distinguish replace edits), then replay that edit at every extra
 * cursor and apply ALL fan-out edits in a SINGLE value write. One undo snapshot
 * per transaction is guaranteed by the caller (the snapshot is pushed once in
 * onValueChange before this runs).
 *
 * Extra cursors are full TextRange selections (start==end = collapsed caret).
 */
object MultiCursorEngine {

    /** A single content edit: `deleted` at [start] is replaced by `inserted`. */
    data class McEdit(val start: Int, val deleted: String, val inserted: String)

    data class FanOutResult(
        val text: String,
        val primary: TextRange,
        val extras: List<TextRange>,
    )

    /**
     * Content diff old->new via common prefix/suffix (same technique as
     * EditShiftHelper.findChangeStart, extended with the suffix scan) producing
     * ONE precise edit triple. Returns null when text is unchanged.
     */
    fun diffEdit(old: String, new: String): McEdit? {
        if (old == new) return null
        var prefix = 0
        val maxPrefix = minOf(old.length, new.length)
        while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        val maxSuffix = minOf(old.length, new.length) - prefix
        while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        val start = prefix
        val deleted = old.substring(start, old.length - suffix)
        val inserted = new.substring(start, new.length - suffix)
        return McEdit(start, deleted, inserted)
    }

    /**
     * VS Code CursorCollection.normalize() port: sort by start and merge.
     *  - If either of two adjacent cursors is collapsed, touching merges.
     *  - Otherwise only true overlaps merge.
     */
    fun normalize(cursors: List<TextRange>): List<TextRange> {
        if (cursors.size <= 1) return cursors
        val sorted = cursors.sortedWith(compareBy({ it.min }, { it.max }))
        val out = ArrayList<TextRange>(sorted.size)
        for (cur in sorted) {
            val last = out.lastOrNull()
            if (last == null) {
                out.add(cur)
                continue
            }
            val shouldMerge = if (cur.min == cur.max || last.min == last.max) {
                cur.min <= last.max
            } else {
                cur.min < last.max
            }
            if (shouldMerge) {
                out[out.size - 1] = TextRange(last.min, maxOf(last.max, cur.max))
            } else {
                out.add(cur)
            }
        }
        return out
    }

    /**
     * Shift a position through an edit, VS Code marker semantics:
     * positions before the edit stay; positions at/after the edit move by
     * (inserted - deleted); positions inside the deleted region collapse to
     * the edit end.
     */
    private fun shiftPos(p: Int, e: McEdit): Int = when {
        p <= e.start -> p
        p >= e.start + e.deleted.length -> p - e.deleted.length + e.inserted.length
        else -> e.start + e.inserted.length
    }

    private fun clampRange(r: TextRange, text: String): TextRange {
        val len = text.length
        return TextRange(r.start.coerceIn(0, len), r.end.coerceIn(0, len))
    }

    /**
     * Fan the primary edit out to every extra cursor, applying ALL edits in ONE
     * text write (ascending positions with a running shift so no position ever
     * goes stale). Mirrors VS Code's ReplaceCommand set + cursor-state-computer:
     * every cursor's new position is computed from the edit results, the primary
     * selection is adjusted for fan-out edits that landed before it, and the
     * extras are normalized (sorted/merged) with any cursor equal to the
     * primary dropped (the old Test-51 duplicate-cursor class of bug, solved
     * structurally).
     *
     * Direction handling for deletions mirrors the primary edit: if the primary
     * caret sat at/after the end of the deleted region the deletion happened
     * BEFORE the caret (backspace), so each extra deletes the same length before
     * its own caret; otherwise AFTER (delete key). Backspace at offset 0 clamps
     * (VS Code: line join). Replace-style edits (insert + delete, e.g. stripped
     * composition) replace the same length before each cursor.
     */
    fun applyFanOut(
        oldText: String,
        newText: String,
        primaryOld: TextRange,
        primaryNew: TextRange,
        extras: List<TextRange>,
    ): FanOutResult {
        val edit = diffEdit(oldText, newText)
            ?: return FanOutResult(newText, primaryNew, extras.map { clampRange(it, newText) })
        val insLen = edit.inserted.length
        val delLen = edit.deleted.length
        val primaryDeletedBefore = primaryOld.end >= edit.start + delLen && delLen > 0

        // Build ONE edit per extra cursor (VS Code: commands[i] = ReplaceCommand(selections[i], ...))
        data class Pending(val start: Int, val deleteLen: Int)
        val pendings = ArrayList<Pending>(extras.size)
        for (extra in extras) {
            if (extra.min != extra.max) {
                // Non-collapsed extra selection: the selection is replaced by the inserted text
                val from = shiftPos(extra.min, edit)
                val to = shiftPos(extra.max, edit)
                pendings.add(Pending(from, (to - from).coerceAtLeast(0)))
            } else {
                val q = shiftPos(extra.min, edit)
                if (primaryDeletedBefore) {
                    val from = (q - delLen).coerceAtLeast(0)
                    pendings.add(Pending(from, q - from))
                } else {
                    pendings.add(Pending(q, delLen))
                }
            }
        }

        // Apply all fan-out edits in ONE write — ascending with a running shift
        var text = newText
        var shift = 0
        val newExtras = ArrayList<TextRange>(pendings.size)
        var primaryAdjust = 0
        for (p in pendings.sortedBy { it.start }) {
            val from = (p.start + shift).coerceIn(0, text.length)
            val to = (p.start + p.deleteLen + shift).coerceIn(from, text.length)
            text = text.substring(0, from) + edit.inserted + text.substring(to)
            val delta = insLen - (to - from)
            if (p.start < primaryNew.min) primaryAdjust += delta
            newExtras.add(TextRange(from + insLen))
            shift += delta
        }

        val newPrimary = TextRange(
            (primaryNew.min + primaryAdjust).coerceIn(0, text.length),
            (primaryNew.max + primaryAdjust).coerceIn(0, text.length),
        )
        val normalized = normalize(newExtras).filter {
            it.min != newPrimary.min || it.max != newPrimary.max
        }
        return FanOutResult(text, newPrimary, normalized)
    }
}
