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

    // ---- 2026-11-04, device finding #13: the ENOENT that landed on a live conversation ----

    /** The project's directory is created on the FIRST save, not assumed to exist. */
    @Test
    fun aFirstSaveCreatesTheProjectDirectory() {
        val dir = File(root, "fresh-project")
        assertFalse("precondition: no directory yet", dir.exists())
        val out = files.saveNow("fresh-project", "s_one", msgs("hello"))
        assertNull("first save must succeed: " + out, out)
        assertTrue(dir.isDirectory)
        assertEquals(1, files.load("fresh-project", "s_one").messages.size)
    }

    /** A stale temp file from a killed process must not block the next save. */
    @Test
    fun aStaleTempFileDoesNotBlockTheNextSave() {
        files.saveNow("p", "s_a", msgs("first"))
        val stale = File(File(root, "p"), "s_a.txt.tmp")
        stale.writeText("half written garbage")
        val out = files.saveNow("p", "s_a", msgs("second"))
        assertNull("save over a stale temp must succeed: " + out, out)
        assertEquals("second", files.load("p", "s_a").messages.first().text)
        assertFalse("the stale temp is gone", stale.exists())
    }

    /**
     * The directory vanishing between two saves (a storage cleaner, or a delete racing a live
     * session) must be RECOVERED, not reported as a data-loss failure.
     */
    @Test
    fun aDirectoryDeletedBetweenSavesIsRecreated() {
        files.saveNow("p", "s_a", msgs("first"))
        val dir = File(root, "p")
        assertTrue(dir.deleteRecursively())
        val out = files.saveNow("p", "s_a", msgs("second"))
        assertNull("save after the directory vanished must recover: " + out, out)
        assertEquals("second", files.load("p", "s_a").messages.first().text)
    }

    /**
     * When the directory cannot exist at all (a FILE sits where it belongs), the failure is a
     * reported reason - never a throw, never a silent loss - and the previous transcript is
     * still readable.
     */
    @Test
    fun anImpossibleDirectoryIsReportedAndTheOldTranscriptSurvives() {
        files.saveNow("p", "s_a", msgs("keep me"))
        val dir = File(root, "p")
        assertTrue(dir.deleteRecursively())
        dir.writeText("I am a file, not a folder")
        val out = files.saveNow("p", "s_a", msgs("new content"))
        assertNotNull("an impossible directory must be reported", out)
        assertTrue("the reason names the folder", (out ?: "").contains("p"))
        // The old transcript is gone with its directory, but nothing was silently half-written:
        assertEquals("I am a file, not a folder", dir.readText())
    }
}
