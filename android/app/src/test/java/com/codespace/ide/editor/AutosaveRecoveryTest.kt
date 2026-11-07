package com.codespace.ide.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner order 2026-11-07, follow-up 1: a preview must never produce an autosave, and the recovery
 * path must never offer a slice as "unsaved edits" for the whole file.
 *
 * Two independent facts are pinned here:
 *   1. a preview cannot go dirty, so the 30-second autosave loop (which filters isDirty) never sees
 *      it, and the loop also filters readOnly explicitly as belt and braces;
 *   2. if an autosave DOES contain only the head of the file, recovery refuses it.
 */
class AutosaveRecoveryTest {

    private val whole = (1..200).joinToString("\n") { "line $it" } + "\n"
    private val slice = (1..50).joinToString("\n") { "line $it" } + "\n"

    @Test
    fun aPreviewCannotGoDirtySoItIsNeverAutosaved() {
        assertFalse(
            "the autosave loop filters isDirty; a preview must never satisfy it",
            PreviewPolicy.canGoDirty(readOnly = true),
        )
        assertTrue(PreviewPolicy.canGoDirty(readOnly = false))
    }

    @Test
    fun aSliceIsRecognisedAsAPartialCopyAndRealEditsAreNot() {
        assertTrue(
            "the first 50 lines of a 200-line file are a slice",
            com.codespace.ide.ui.panes.isPartialSliceOfFile(slice, whole),
        )
        assertFalse(
            "genuine unsaved edits are not a prefix: they differ in the middle",
            com.codespace.ide.ui.panes.isPartialSliceOfFile(
                whole.replace("line 100", "line 100 EDITED"),
                whole,
            ),
        )
        assertFalse(
            "a longer autosave (user added lines) is not a slice",
            com.codespace.ide.ui.panes.isPartialSliceOfFile(whole + "line 201\n", whole),
        )
        assertFalse(
            "empty content is not a slice",
            com.codespace.ide.ui.panes.isPartialSliceOfFile("", whole),
        )
        assertFalse(
            "an unrelated file is not a slice",
            com.codespace.ide.ui.panes.isPartialSliceOfFile("totally different\n", whole),
        )
    }

    @Test
    fun deletingTheTailOfAFileIsAlsoCaught() {
        // A user who deleted the last 150 lines must still be recoverable: that content is a
        // prefix of the disk file too, so it is refused as ambiguous. Stated as a known cost.
        val truncated = (1..50).joinToString("\n") { "line $it" } + "\n"
        assertTrue(com.codespace.ide.ui.panes.isPartialSliceOfFile(truncated, whole))
    }
}
