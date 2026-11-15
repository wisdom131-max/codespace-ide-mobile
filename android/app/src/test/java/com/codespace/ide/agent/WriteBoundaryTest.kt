package com.codespace.ide.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 5b: which calls are checked for containment, and what the refusal promises.
 *
 * The bug this pins: the containment helper existed but only copy and move used it, so a write,
 * an edit or a delete could name a path outside the open project.
 */
class WriteBoundaryTest {

    @Test
    fun everyFileMutatorIsChecked() {
        val writers = listOf(
            "write_file", "create_file", "insert_edit_into_file", "multi_replace_string_in_file",
            "edit_file", "apply_patch", "grep_replace_all", "copy_path", "move_path", "delete_path",
        )
        for (w in writers) assertTrue("$w must be boundary-checked", WriteBoundary.isWriter(w))
    }

    @Test
    fun readOnlyToolsAreNotBoundaryChecked() {
        assertFalse(WriteBoundary.isWriter("read_file"))
        assertFalse(WriteBoundary.isWriter("grep_search"))
        assertEquals(emptyList<String>(), WriteBoundary.pathsOf("read_file", JSONObject().put("path", "/x")))
    }

    @Test
    fun aSinglePathWriterYieldsItsPath() {
        val args = JSONObject().put("path", "src/Main.kt")
        assertEquals(listOf("src/Main.kt"), WriteBoundary.pathsOf("write_file", args))
    }

    @Test
    fun aMoveChecksBothEnds() {
        val args = JSONObject().put("from", "a.txt").put("to", "/storage/emulated/0/b.txt")
        assertEquals(listOf("a.txt", "/storage/emulated/0/b.txt"), WriteBoundary.pathsOf("move_path", args))
    }

    @Test
    fun aMissingPathYieldsNothingRatherThanAnEmptyCheck() {
        assertEquals(emptyList<String>(), WriteBoundary.pathsOf("write_file", JSONObject()))
    }

    @Test
    fun theRefusalNameTheToolThePathAndTheRootAndSaysNothingWasWritten() {
        val msg = WriteBoundary.refusal("write_file", "/storage/emulated/0/x.txt", "/data/project")
        assertTrue(msg.contains("write_file"))
        assertTrue(msg.contains("/storage/emulated/0/x.txt"))
        assertTrue(msg.contains("/data/project"))
        assertTrue(msg.contains("Nothing was written"))
    }
}
