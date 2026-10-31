package com.codespace.ide.chat

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Item 17b (2026-10-31). Two defects, both closed here:
 *
 *  1. The store keyed proposals by PATH, so two sessions staging the same file overwrote each
 *     other. Attribution only existed on paper because the map could not hold both.
 *  2. Nothing was written to disk, so a restart lost every proposal and "attribution survives
 *     a restart" was not achievable.
 */
class PendingProposalAttributionTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        PendingChangesStore.clearForTest()
        dir = File(System.getProperty("java.io.tmpdir"), "pending-test-" + System.nanoTime())
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        PendingChangesStore.clearForTest()
        dir.deleteRecursively()
    }

    @Test
    fun `two sessions may stage the same file without overwriting each other`() {
        PendingChangesStore.stageForTest("sessionA", "/proj/Main.kt", "A version")
        PendingChangesStore.stageForTest("sessionB", "/proj/Main.kt", "B version")

        assertEquals(1, PendingChangesStore.pendingCount("sessionA"))
        assertEquals(1, PendingChangesStore.pendingCount("sessionB"))
        assertEquals("A version", PendingChangesStore.pendingFor("sessionA").single().newContent)
        assertEquals("B version", PendingChangesStore.pendingFor("sessionB").single().newContent)
    }

    @Test
    fun `the read-through overlay prefers the active session's proposal`() {
        PendingChangesStore.stageForTest("sessionA", "/proj/Main.kt", "A version")
        PendingChangesStore.stageForTest("sessionB", "/proj/Main.kt", "B version")
        PendingChangesStore.activeSessionId = "sessionB"
        assertEquals("B version", PendingChangesStore.overlayFor("/proj/Main.kt"))
        PendingChangesStore.activeSessionId = "sessionA"
        assertEquals("A version", PendingChangesStore.overlayFor("/proj/Main.kt"))
    }

    @Test
    fun `discarding one session's proposal leaves the other session's intact`() {
        PendingChangesStore.stageForTest("sessionA", "/proj/Main.kt", "A version")
        PendingChangesStore.stageForTest("sessionB", "/proj/Main.kt", "B version")

        PendingChangesStore.discardAll("sessionA")

        assertEquals(0, PendingChangesStore.pendingCount("sessionA"))
        assertEquals(1, PendingChangesStore.pendingCount("sessionB"))
        assertEquals("B version", PendingChangesStore.pendingFor("sessionB").single().newContent)
    }

    @Test
    fun `proposals survive a save and reload with their session and status`() {
        PendingChangesStore.stageForTest("sessionA", "/proj/Main.kt", "A version", base = "old")
        PendingChangesStore.saveTo(dir, "/proj")
        PendingChangesStore.clearForTest()

        PendingChangesStore.loadFrom(dir, "/proj")

        val restored = PendingChangesStore.pendingFor("sessionA").single()
        assertEquals("/proj/Main.kt", restored.path)
        assertEquals("A version", restored.newContent)
        assertEquals("old", restored.baseContent)
        assertEquals(PendingChangesStore.Status.PENDING, restored.status)
    }

    @Test
    fun `a proposal file belonging to another project is never loaded`() {
        PendingChangesStore.stageForTest("sessionA", "/one/Main.kt", "first project")
        PendingChangesStore.saveTo(dir, "/one")
        PendingChangesStore.clearForTest()

        PendingChangesStore.loadFrom(dir, "/two")

        assertEquals(0, PendingChangesStore.pendingCount("sessionA"))
        assertNull(PendingChangesStore.overlayFor("/one/Main.kt"))
    }

    @Test
    fun `a damaged proposal file loads as no history rather than half a proposal`() {
        val file = File(dir, "x.json")
        file.writeText("{not json")
        PendingChangesStore.loadFrom(dir, "/proj")
        assertEquals(0, PendingChangesStore.pendingCount("sessionA"))
    }

    @Test
    fun `saving an empty store removes the file instead of leaving a stale one`() {
        PendingChangesStore.stageForTest("sessionA", "/proj/Main.kt", "A version")
        PendingChangesStore.saveTo(dir, "/proj")
        assertTrue(dir.listFiles()!!.isNotEmpty())

        PendingChangesStore.clearForTest()
        PendingChangesStore.saveTo(dir, "/proj")
        assertTrue("empty store must not leave a file behind", dir.listFiles()!!.isEmpty())
    }
}
