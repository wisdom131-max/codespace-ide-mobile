package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P0.3 (2026-11-19): a workspace is verified BEFORE any success is claimed. These pin the three
 * outcomes the device report needed: usable, missing, and not-a-folder.
 */
class WorkspaceVerificationTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `an existing writable folder is usable`() {
        assertNull(WorkspaceContext.verifyUsable(tmp.newFolder("project")))
    }

    @Test
    fun `a missing folder is refused with a reason that names the path`() {
        val gone = java.io.File(tmp.root, "deleted-project")
        val reason = WorkspaceContext.verifyUsable(gone)
        assertNotNull(reason)
        org.junit.Assert.assertTrue(reason!!.contains("does not exist"))
        org.junit.Assert.assertTrue(reason.contains("deleted-project"))
    }

    @Test
    fun `a file is not a folder`() {
        val f = tmp.newFile("notes.txt")
        org.junit.Assert.assertTrue(WorkspaceContext.verifyUsable(f)!!.contains("not a folder"))
    }

    @Test
    fun `no selection is stated plainly`() {
        assertEquals("no workspace folder is selected", WorkspaceContext.verifyUsable(null))
    }
}
