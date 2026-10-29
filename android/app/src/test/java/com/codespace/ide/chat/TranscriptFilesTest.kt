package com.codespace.ide.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * BATCH 4 — the file layer, against a real temp directory.
 *
 * These are the failure cases a conversation actually meets on a phone: a write that was
 * interrupted, a file that was damaged, a project id that contains slashes, and "clear"
 * meaning clear.
 */
class TranscriptFilesTest {

    private lateinit var root: File
    private lateinit var files: TranscriptFiles

    @Before
    fun setUp() {
        root = Files.createTempDirectory("chat-transcripts").toFile()
        files = TranscriptFiles(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun msgs(vararg text: String) = text.mapIndexed { i, t -> StoredMessage("user", t, true, i.toLong()) }

    @Test
    fun aSavedConversationComesBackInOrder() {
        assertNull(files.saveNow("proj", "main", msgs("one", "two", "three")))
        val loaded = files.load("proj", "main")
        assertEquals(3, loaded.messages.size)
        assertEquals("one", loaded.messages[0].text)
        assertEquals("three", loaded.messages[2].text)
        assertNull("a clean load has nothing to apologise for", loaded.note)
    }

    @Test
    fun anEmptyConversationIsNothingToRead() {
        val loaded = files.load("proj", "main")
        assertTrue(loaded.messages.isEmpty())
        assertNull(loaded.note)
    }

    @Test
    fun clearingActuallyDeletesSoAGhostCannotComeBack() {
        files.saveNow("proj", "main", msgs("keep me", "and me"))
        // A second save creates the .bak, so clear must remove both.
        files.saveNow("proj", "main", msgs("keep me", "and me", "three"))
        assertNull(files.saveNow("proj", "main", emptyList()))
        assertTrue(files.load("proj", "main").messages.isEmpty())
        val dir = files.fileFor("proj", "main").parentFile
        assertEquals("no .bak may survive a clear", 0, dir.listFiles()!!.count { it.name.endsWith(".bak") })
    }

    @Test
    fun aDamagedFileFallsBackToThePreviousCopyAndSaysSo() {
        files.saveNow("proj", "main", msgs("the good version"))
        files.saveNow("proj", "main", msgs("the good version", "second write makes a backup"))
        // Simulate a torn write: garbage in the primary, a readable backup.
        files.fileFor("proj", "main").writeText("\u0000\u0000 garbage")
        val loaded = files.load("proj", "main")
        assertTrue(loaded.messages.isNotEmpty())
        assertNotNull("recovering from backup must be disclosed", loaded.note)
    }

    @Test
    fun anUnreadableFileIsLeftInPlaceAndReported() {
        files.saveNow("proj", "main", msgs("x"))
        val target = files.fileFor("proj", "main")
        target.writeText("some future format this build cannot read")
        val loaded = files.load("proj", "main")
        assertTrue(loaded.messages.isEmpty())
        assertNotNull(loaded.note)
        assertTrue("never delete a user's file to tidy up", target.exists())
    }

    @Test
    fun aProjectIdWithSlashesCannotEscapeTheStoreDirectory() {
        assertNull(files.saveNow("/storage/emulated/0/My codespace app", "main", msgs("safe")))
        val dir = File(root, "_storage_emulated_0_My_codespace_app")
        assertTrue("the escape is flattened into one segment", dir.isDirectory)
        assertEquals(1, files.load("/storage/emulated/0/My codespace app", "main").messages.size)
    }

    @Test
    fun aTraversalShapedIdCannotLeaveTheRoot() {
        assertNull(files.saveNow("../../etc", "main", msgs("safe")))
        assertFalse("nothing may be written outside the store", File(root.parentFile, "etc").exists())
    }

    @Test
    fun separateConversationsAreSeparateFiles() {
        files.saveNow("proj", "main", msgs("in main"))
        files.saveNow("proj", "second", msgs("in second"))
        assertEquals("in main", files.load("proj", "main").messages[0].text)
        assertEquals("in second", files.load("proj", "second").messages[0].text)
    }

    @Test
    fun separateProjectsDoNotSeeEachOther() {
        files.saveNow("projA", "main", msgs("A"))
        files.saveNow("projB", "main", msgs("B"))
        assertEquals("A", files.load("projA", "main").messages[0].text)
        assertEquals("B", files.load("projB", "main").messages[0].text)
    }

    @Test
    fun aWriteLeavesNoTemporaryFileBehind() {
        files.saveNow("proj", "main", msgs("x"))
        val dir = files.fileFor("proj", "main").parentFile
        assertEquals(0, dir.listFiles()!!.count { it.name.endsWith(".tmp") })
    }

    @Test
    fun aSkippedLineIsReportedWithoutLosingTheRest() {
        files.saveNow("proj", "main", msgs("kept"))
        val target = files.fileFor("proj", "main")
        target.writeText(target.readText() + "broken\tline\n")
        val loaded = files.load("proj", "main")
        assertEquals("kept", loaded.messages[0].text)
        assertNotNull("skipped data must be disclosed", loaded.note)
    }
}
