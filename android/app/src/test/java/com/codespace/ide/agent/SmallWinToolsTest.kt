package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** S1: the six new capabilities, each asserted where it actually fails. */
class SmallWinToolsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun checksumMatchesTheDigestOfTheContent() {
        val f = tmp.newFile("a.txt")
        f.writeText("hello")
        // Known vector: sha256("hello")
        assertTrue(FileInsightTools.checksum(f.path)
            .contains("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"))
        assertTrue(FileInsightTools.checksum(f.path, "md5").startsWith("MD5"))
    }

    @Test
    fun checksumReportsAMissingFileInsteadOfCrashing() {
        assertTrue(FileInsightTools.checksum("/nope/missing.bin").startsWith("No such file"))
    }

    @Test
    fun checksumRejectsAnUnknownAlgorithm() {
        val f = tmp.newFile("b.txt"); f.writeText("x")
        assertTrue(FileInsightTools.checksum(f.path, "crc32").startsWith("Unknown algorithm"))
    }

    @Test
    fun diffFindsAddedAndRemovedLines() {
        val r = FileInsightTools.diff("a\nb\nc\n", "a\nB\nc\n")
        assertFalse(r.identical)
        assertEquals(1, r.added)
        assertEquals(1, r.removed)
        assertTrue(r.render("a", "b").contains("-b"))
        assertTrue(r.render("a", "b").contains("+B"))
    }

    @Test
    fun identicalFilesSaySo() {
        val r = FileInsightTools.diff("same\n", "same\n")
        assertTrue(r.identical)
        assertTrue(r.render("a", "b").contains("identical"))
    }

    @Test
    fun diffHandlesOneSideEmpty() {
        val r = FileInsightTools.diff("", "one\ntwo\n")
        assertTrue(r.added >= 1)
        assertFalse(r.identical)
    }

    @Test
    fun moveRefusesToOverwrite() {
        val a = tmp.newFile("a.txt").apply { writeText("a") }
        val b = tmp.newFile("b.txt").apply { writeText("b") }
        assertTrue(FileInsightTools.move(a.path, b.path).startsWith("Refused"))
        assertEquals("b", b.readText())
    }

    @Test
    fun moveRenamesWhenAllowed() {
        val a = tmp.newFile("c.txt").apply { writeText("payload") }
        val target = File(tmp.root, "c-moved.txt")
        assertTrue(FileInsightTools.move(a.path, target.path).startsWith("Moved"))
        assertTrue(target.exists())
        assertFalse(a.exists())
    }

    @Test
    fun movingAMissingPathIsReported() {
        assertTrue(FileInsightTools.move("/nope/x", File(tmp.root, "y").path).startsWith("No such path"))
    }

    @Test
    fun journalRefusesToStoreSecrets() {
        val f = File(tmp.root, "j/base.md")
        assertTrue(FileInsightTools.journalWrite(f, "used sk-abcdefghijklmnop1234 to call the api", "2026-11-11 10:00")
            .startsWith("Noted"))
        val text = f.readText()
        assertFalse(text.contains("sk-abcdefghijklmnop1234"))
        assertTrue(text.contains("[redacted]"))
    }

    @Test
    fun journalAppendsWithATimestamp() {
        val f = File(tmp.root, "j/notes.md")
        FileInsightTools.journalWrite(f, "first", "2026-11-11 10:00")
        FileInsightTools.journalWrite(f, "second", "2026-11-11 10:05")
        assertEquals(2, f.readLines().size)
        assertTrue(f.readLines()[1].startsWith("[2026-11-11 10:05]"))
    }

    @Test
    fun anEmptyJournalNoteIsNotWritten() {
        val f = File(tmp.root, "j/e.md")
        assertTrue(FileInsightTools.journalWrite(f, "   ", "2026-11-11 10:00").contains("empty"))
        assertFalse(f.exists())
    }

    // ── git ────────────────────────────────────────────────────────────────

    private class FakeGit {
        val calls = ArrayList<List<String>>()
        var lastOutput = ""
        val runner = GitExtraTools.Runner { args -> calls.add(args); lastOutput }
    }

    @Test
    fun stashPushPassesTheMessage() {
        val git = FakeGit()
        GitExtraTools.stash("push", "wip: parser", File("/repo"), git.runner)
        assertEquals(listOf("stash", "push", "-m", "wip: parser"), git.calls.single())
    }

    @Test
    fun stashListIsReportedAndEmptyIsStated() {
        val git = FakeGit()
        assertTrue(GitExtraTools.stash("list", "", File("/repo"), git.runner).contains("No stashes"))
        git.lastOutput = "stash@{0}: wip"
        assertTrue(GitExtraTools.stash("list", "", File("/repo"), git.runner).contains("stash@{0}"))
    }

    @Test
    fun anUnknownStashActionIsRefusedWithTheOptions() {
        val git = FakeGit()
        assertTrue(GitExtraTools.stash("obliterate", "", File("/repo"), git.runner).contains("Unknown stash action"))
        assertTrue(git.calls.isEmpty())
    }

    @Test
    fun tagCreateUsesAnnotatedFormWhenAMessageExists() {
        val git = FakeGit()
        GitExtraTools.tag("create", "v1.2.0", "release", File("/repo"), git.runner)
        assertEquals(listOf("tag", "-a", "v1.2.0", "-m", "release"), git.calls.single())
    }

    @Test
    fun tagNamesThatGitWouldMisreadAreRefused() {
        val git = FakeGit()
        assertTrue(GitExtraTools.tag("create", "-delete", "", File("/repo"), git.runner).startsWith("Refused"))
        assertTrue(GitExtraTools.tag("create", "bad name", "", File("/repo"), git.runner).startsWith("Refused"))
        assertTrue(GitExtraTools.tag("create", "a..b", "", File("/repo"), git.runner).startsWith("Refused"))
        assertTrue(git.calls.isEmpty())
    }

    @Test
    fun aTagNeedsAName() {
        val git = FakeGit()
        assertTrue(GitExtraTools.tag("create", "", "", File("/repo"), git.runner).contains("needs a name"))
    }
}
