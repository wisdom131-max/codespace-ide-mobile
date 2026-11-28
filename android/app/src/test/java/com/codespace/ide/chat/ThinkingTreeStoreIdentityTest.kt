package com.codespace.ide.chat

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OWNER FIX (2026-11-28): a tree belongs to one conversation, and the old renderer's data is gone.
 *
 * These are the two rules that stop a stale tree appearing in a new chat: no shared file for an
 * unidentified conversation, and one purge of everything the previous schema left behind.
 */
class ThinkingTreeStoreIdentityTest {

    private fun root(): File = Files.createTempDirectory("vncode-tree").toFile()

    private fun step(id: String) = ThinkingTreeStore.Step(nodeId = id, label = "read", atMs = 1L)

    @Test
    fun `two conversations never share a tree file`() {
        val r = root()
        assertTrue(ThinkingTreeStore.appendTo(r, "p1", "c1", step("n1")))
        assertTrue(ThinkingTreeStore.appendTo(r, "p1", "c2", step("n2")))
        assertEquals(listOf("n1"), ThinkingTreeStore.loadFrom(r, "p1", "c1").map { it.nodeId })
        assertEquals(listOf("n2"), ThinkingTreeStore.loadFrom(r, "p1", "c2").map { it.nodeId })
    }

    @Test
    fun `a blank conversation id reads nothing and writes nothing`() {
        val r = root()
        assertFalse("a blank id has no file to write", ThinkingTreeStore.appendTo(r, "p1", "", step("n1")))
        assertFalse(ThinkingTreeStore.appendTo(r, "p1", "   ", step("n2")))
        assertFalse(ThinkingTreeStore.writeTo(r, "p1", "", listOf(step("n3"))))
        assertTrue("nothing may be read for an unidentified conversation",
            ThinkingTreeStore.loadFrom(r, "p1", "").isEmpty())
        assertTrue("a blank project has no tree either",
            ThinkingTreeStore.loadFrom(r, "", "c1").isEmpty())
        val files = r.walkTopDown().filter { it.isFile }.toList()
        assertTrue("no file was created: " + files.map { it.name }, files.isEmpty())
    }

    @Test
    fun `an unidentified chat cannot inherit another conversation's steps`() {
        val r = root()
        ThinkingTreeStore.appendTo(r, "p1", "c1", step("n1"))
        assertTrue(
            "a blank id must not fall through to a shared file",
            ThinkingTreeStore.loadFrom(r, "p1", "").isEmpty(),
        )
    }

    @Test
    fun `the old schema is purged once and never again`() {
        val r = root()
        val old = File(r, "p1").apply { mkdirs() }
        File(old, "c1.tree.json").writeText("""[{"nodeId":"old","label":"stale"}]""")
        File(old, "c2.tree.json").writeText("""[{"nodeId":"old2","label":"stale"}]""")

        assertEquals("both stale trees are removed", 2, ThinkingTreeStore.migrateOnce(r))
        assertFalse(File(old, "c1.tree.json").exists())
        assertEquals("the purge is idempotent", 0, ThinkingTreeStore.migrateOnce(r))
    }

    @Test
    fun `a tree written after the purge survives the next start`() {
        val r = root()
        ThinkingTreeStore.migrateOnce(r)
        assertTrue(ThinkingTreeStore.appendTo(r, "p1", "c1", step("fresh")))
        assertEquals("the marker stops a second wipe", 0, ThinkingTreeStore.migrateOnce(r))
        assertEquals(listOf("fresh"), ThinkingTreeStore.loadFrom(r, "p1", "c1").map { it.nodeId })
    }
}
