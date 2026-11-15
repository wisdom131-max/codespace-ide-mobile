package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GATE 2: a write call's id is derived from what it asks for, so a repeat collides on purpose and
 * the ledger can answer it. Everything else keeps a fresh id.
 */
class WriteCallIdentityTest {

    @Test
    fun theSameWriteAskedTwiceHasOneId() {
        val a = WriteCallIdentity.idFor("write_file", """{"path":"/p/a.txt","content":"hi"}""")
        val b = WriteCallIdentity.idFor("write_file", """{"path":"/p/a.txt","content":"hi"}""")
        assertEquals(a, b)
    }

    @Test
    fun keyOrderAndWhitespaceAreNotIntent() {
        val a = WriteCallIdentity.idFor("write_file", """{"path":"/p/a.txt","content":"hi"}""")
        val b = WriteCallIdentity.idFor("write_file", """{ "content" : "hi" , "path" : "/p/a.txt" }""")
        assertEquals(a, b)
    }

    @Test
    fun differentContentIsADifferentCall() {
        val a = WriteCallIdentity.idFor("write_file", """{"path":"/p/a.txt","content":"one"}""")
        val b = WriteCallIdentity.idFor("write_file", """{"path":"/p/a.txt","content":"two"}""")
        assertNotEquals(a, b)
    }

    @Test
    fun differentToolsAreDifferentCalls() {
        val a = WriteCallIdentity.idFor("write_file", """{"path":"/p/a.txt","content":"hi"}""")
        val b = WriteCallIdentity.idFor("edit_file", """{"path":"/p/a.txt","content":"hi"}""")
        assertNotEquals(a, b)
    }

    @Test
    fun aDeliberateSecondCommandKeepsAFreshId() {
        // The same command twice can be a real second intent, and it has its own approval gate.
        assertNull(WriteCallIdentity.idFor("run_command", """{"command":"ls"}"""))
        assertNull(WriteCallIdentity.idFor("git_commit", """{"message":"x"}"""))
        assertNull(WriteCallIdentity.idFor("read_file", """{"path":"/p/a.txt"}"""))
    }

    @Test
    fun everyFileWriterIsCovered() {
        val writers = listOf(
            "write_file", "create_file", "insert_edit_into_file", "multi_replace_string_in_file",
            "edit_file", "apply_patch", "grep_replace_all", "copy_path", "move_path", "delete_path",
        )
        for (w in writers) {
            val id = WriteCallIdentity.idFor(w, """{"path":"/p/a.txt","content":"hi"}""")
            requireNotNull(id) { "$w must carry a derived id" }
            require(id.startsWith("$w:"))
        }
    }

    @Test
    fun unparseableArgumentsStillGiveAStableId() {
        val a = WriteCallIdentity.idFor("write_file", """{"path": "/p/a.txt", "content": """)
        val b = WriteCallIdentity.idFor("write_file", """{"path": "/p/a.txt", "content": """)
        assertEquals(a, b)
    }
}
