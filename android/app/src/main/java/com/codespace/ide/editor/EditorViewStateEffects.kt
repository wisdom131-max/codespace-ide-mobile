package com.codespace.ide.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * 64KB EXTRACTION (2026-09-13, #2790 Method-too-large on CodeEditorKt.CodeEditor):
 * every per-view state effect added by PAD-2/PERSIST-A lives HERE instead of the
 * CodeEditor composable body. CodeEditor calls this once with its locals.
 *
 * Pipeline (the stale-position fix, brought forward from PERSIST-A):
 *  - CAPTURE: on THIS instance's dispose (key(activeId) remounts CodeEditor on
 *    every view switch), report (viewKey, first-visible-line, cursor offset).
 *    The key comes from the disposing instance's own rememberUpdatedState —
 *    never the parent's live state.
 *  - APPLY on mount: cursor single-shot via a remember flag; scroll uses the
 *    LINE-JUMP-READY-FIX retry loop (vScroll.maxValue is 0 before first layout).
 *  - FOLDS: report folded-range changes keyed back to the file's path by the
 *    parent (folding is model state, shared by all views of a file).
 *  - FIND: persist query + toggles to EditorFindState (plain prefs) so the
 *    find bar reopens where it was left (VS Code parity).
 */
@Composable
internal fun EditorViewStateEffects(
    /** PAD-1/2: view id (tab path or split id). Gates only the scroll-lock work upstream. */
    viewKey: String?,
    filePath: String? = null,
    /** PERSIST-A: mount-restore — 0-based first visible line (0 = top / none). */
    initialScrollLine: Int,
    /** PERSIST-A: mount-restore — cursor char offset (-1 = leave selection alone). */
    initialCursorOffset: Int,
    /** PERSIST-A: dispose-time live capture, with THIS instance's view key. */
    onViewStateCapture: ((viewKey: String, scrollLine: Int, cursorOffset: Int) -> Unit)?,
    /** PERSIST-A: fold changes; parent resolves the key back to the file path. */
    onFoldsChange: ((viewKey: String, foldedStarts: Set<Int>) -> Unit)?,
    /** Parent-side cursor apply (writes the parent's TextFieldValue state var). */
    onApplyCursor: (offset: Int) -> Unit,
    /** This editor instance's scroll state (stable object — reads at dispose are live). */
    vScroll: ScrollState,
    /** Current line height in px. Float — all scroll math stays Int (#2719 rule class). */
    lineHeightPx: Float,
    /** Latest buffer content + selection (rememberUpdatedState keeps dispose reads fresh). */
    value: androidx.compose.ui.text.input.TextFieldValue,
    /** Current folded range start lines (0-based). */
    foldedRanges: Set<Int>,
    /** Find-widget state mirrored to the persistent store. */
    findQuery: String,
    useRegex: Boolean,
    caseSensitive: Boolean,
    wholeWord: Boolean,
) {
    // ── CAPTURE ────────────────────────────────────────────────────────────
    val liveValue by rememberUpdatedState(value)
    val liveCapture by rememberUpdatedState(onViewStateCapture)
    val liveViewKey by rememberUpdatedState(viewKey)
    DisposableEffect(liveViewKey) {
        onDispose {
            // Plain local first: delegated vals can't be smart-cast after a
            // null check (#2724 rule class).
            val key = liveViewKey
            if (key != null) {
                // lineHeightPx is Float (#2719 rule class) — Int pixel math only.
                val line = if (lineHeightPx > 0f) (vScroll.value / lineHeightPx).toInt() else 0
                liveCapture?.invoke(key, line, liveValue.selection.end)
            }
        }
    }

    // ── APPLY cursor on mount: single-shot — a remember flag stops content-sync
    // effects from re-firing it if the buffer text shifts after layout.
    var appliedInitialCursor by remember { mutableStateOf(false) }
    LaunchedEffect(initialCursorOffset) {
        if (!appliedInitialCursor && initialCursorOffset >= 0) {
            appliedInitialCursor = true
            onApplyCursor(initialCursorOffset)
        }
    }

    // ── APPLY scroll on mount: LINE-JUMP-READY-FIX retry pattern — on a fresh
    // layout vScroll.maxValue is still 0, so retry every 50ms up to 1s.
    // Viewport-fitting files keep maxValue 0 and time out harmlessly at top.
    LaunchedEffect(initialScrollLine) {
        if (initialScrollLine > 0) {
            // Float lineHeightPx → toInt() before any scroll math (#2719 rule class).
            val targetPx = (initialScrollLine * lineHeightPx).toInt()
            var tries = 0
            while (tries < 20 && vScroll.maxValue == 0) {
                kotlinx.coroutines.delay(50)
                tries++
            }
            if (vScroll.maxValue > 0) {
                vScroll.scrollTo(targetPx.coerceAtMost(vScroll.maxValue))
            }
        }
    }

    // ── FOLDS: report changes (fires on mount too, with the restored set —
    // harmless: the parent writes back what it already has).
    LaunchedEffect(foldedRanges, viewKey) {
        if (viewKey != null) onFoldsChange?.invoke(viewKey, foldedRanges)
    }

    // ── FIND: persist query + toggles so the find bar reopens where it was left.
    // G08 (P4b): saved PER FILE (canonical-keyed entry) — file A's find query no
    // longer overwrites file B's; the global slot stays as last-used default.
    val liveFilePath by rememberUpdatedState(filePath)
    LaunchedEffect(findQuery, useRegex, caseSensitive, wholeWord) {
        EditorFindState.saveFor(liveFilePath, EditorFindState.FindSnapshot(
            findQuery, caseSensitive, wholeWord, useRegex))
    }
}
