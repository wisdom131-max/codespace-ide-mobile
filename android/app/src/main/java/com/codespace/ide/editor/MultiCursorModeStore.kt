package com.codespace.ide.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * MULTI-CURSOR-MODE-GLOBAL (2026-09-11):
 *
 * MC mode previously lived as a `remember { mutableStateOf(false) }` INSIDE each
 * CodeEditor instance. Two problems on-device:
 *
 * 1. The extra-keys "MC" chip dispatches through a single shell-level slot that the
 *    LAST-mounted CodeEditor instance owns. In split view (and in any zombie-handler
 *    window after a split closes) the toggle flipped a DIFFERENT instance's local
 *    flag than the visible editor reads — MC appeared dead.
 * 2. The extra-keys row had no access to the flag, so the chip gave zero visual
 *    feedback — even when the toggle worked it looked like nothing happened.
 *
 * Hoisting the flag to a global store fixes both: every instance reads/writes the
 * SAME state (whichever closure receives the key press flips the real mode), and
 * the EditorExtraKeysRow chip can read it to render an active highlight.
 *
 * Mode is intentionally GLOBAL (not per-file): it survives tab switches and editor
 * remounts, matching the sticky feel of VS Code's multi-cursor add-mode.
 */
object MultiCursorModeStore {
    var enabled by mutableStateOf(false)
}
