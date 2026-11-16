package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Stage 4.7 workstreams B and C: fresh repository and destination state, in temporary fixtures. */
class Stage47FreshStateTest {

    private fun tempDir(): File = Files.createTempDirectory("vncode-fresh").toFile()

    private fun git(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return out
    }

    private fun repo(): File {
        val dir = tempDir()
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "test@local")
        git(dir, "config", "user.name", "Test")
        File(dir, "Main.kt").writeText("fun main() {}")
        git(dir, "add", "Main.kt")
        git(dir, "commit", "-q", "-m", "first commit")
        return dir
    }

    @Test
    fun aCleanRepositoryIsReadFromDisk() {
        val dir = repo()
        val snapshot = GitFreshVerifier.read(dir.absolutePath)
        assertTrue(snapshot.readable)
        assertNotNull(snapshot.head)
        assertTrue(snapshot.state!!.clean)
        assertTrue(GitFreshVerifier.verifyClean(dir.absolutePath).ok)
    }

    @Test
    fun stagedUnstagedAndUntrackedAreDistinguished() {
        val dir = repo()
        File(dir, "Main.kt").writeText("fun main() { println(1) }")
        git(dir, "add", "Main.kt")
        File(dir, "Other.kt").writeText("val x = 1")
        File(dir, "notes.md").writeText("todo")
        val state = GitFreshVerifier.read(dir.absolutePath).state!!
        assertTrue(state.staged.contains("Main.kt"))
        assertTrue(state.untracked.contains("notes.md"))
        assertFalse(state.clean)
    }

    @Test
    fun aCommitIsVerifiedAgainstRealHistory() {
        val dir = repo()
        val outcome = GitFreshVerifier.verifyCommit(dir.absolutePath, "first commit")
        assertTrue(outcome.ok)
        val wrong = GitFreshVerifier.verifyCommit(dir.absolutePath, "a commit that never happened")
        assertEquals(GitStateVerifier.Result.REFUTED, wrong.result)
    }

    @Test
    fun aWrongHeadIsRefuted() {
        val dir = repo()
        val outcome = GitFreshVerifier.verifyCommit(dir.absolutePath, null, expectedHead = "0123456789abcdef")
        assertEquals(GitStateVerifier.Result.REFUTED, outcome.result)
    }

    @Test
    fun aCheckoutIsConfirmedFromTheRepository() {
        val dir = repo()
        val branch = GitFreshVerifier.read(dir.absolutePath).state!!.branch
        assertNotNull(branch)
        assertTrue(GitFreshVerifier.verifyCheckout(dir.absolutePath, branch!!).ok)
        assertEquals(GitStateVerifier.Result.REFUTED, GitFreshVerifier.verifyCheckout(dir.absolutePath, "release").result)
    }

    @Test
    fun stagingIsCheckedAgainstTheIndex() {
        val dir = repo()
        File(dir, "Main.kt").writeText("changed")
        git(dir, "add", "Main.kt")
        assertTrue(GitFreshVerifier.verifyStaging(dir.absolutePath, listOf("Main.kt")).ok)
        File(dir, "Other.kt").writeText("new")
        val missing = GitFreshVerifier.verifyStaging(dir.absolutePath, listOf("Other.kt"))
        assertEquals(GitStateVerifier.Result.REFUTED, missing.result)
    }

    @Test
    fun aConflictIsSeenInFreshState() {
        val dir = repo()
        git(dir, "checkout", "-q", "-b", "side")
        File(dir, "Main.kt").writeText("side version")
        git(dir, "commit", "-q", "-am", "side")
        git(dir, "checkout", "-q", "master")
        File(dir, "Main.kt").writeText("main version")
        git(dir, "commit", "-q", "-am", "main")
        git(dir, "merge", "side")
        val state = GitFreshVerifier.read(dir.absolutePath).state!!
        assertTrue("a merge leaves a conflicted path", state.conflicted.isNotEmpty() || state.staged.isNotEmpty())
        assertFalse(GitFreshVerifier.verifyClean(dir.absolutePath).ok)
    }

    @Test
    fun aDirectoryThatIsNotARepositoryIsUnknownNotClean() {
        val plain = tempDir()
        val outcome = GitFreshVerifier.verifyClean(plain.absolutePath)
        assertEquals(GitStateVerifier.Result.NOT_CHECKABLE, outcome.result)
        assertFalse(outcome.ok)
    }

    @Test
    fun aMissingDirectoryIsUnknownNotVerified() {
        val outcome = GitFreshVerifier.verifyCommit("/definitely/not/here", "anything")
        assertEquals(GitStateVerifier.Result.NOT_CHECKABLE, outcome.result)
    }

    @Test
    fun stateChangingAfterTheOperationIsSeenByTheFreshRead() {
        val dir = repo()
        assertTrue(GitFreshVerifier.verifyClean(dir.absolutePath).ok)
        File(dir, "Main.kt").writeText("changed after the fact")
        val after = GitFreshVerifier.verifyClean(dir.absolutePath)
        assertEquals(GitStateVerifier.Result.REFUTED, after.result)
    }

    // ---------- archives ----------

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = File(tempDir(), "bundle.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun aCompleteExtractionIsVerifiedFromDisk() {
        val archive = zip("a.txt" to "hello", "b.txt" to "world!")
        val dest = tempDir()
        File(dest, "a.txt").writeText("hello")
        File(dest, "b.txt").writeText("world!")
        val outcome = ArchiveDestinationVerifier.verify(archive.absolutePath, dest.absolutePath)
        assertEquals(ArchiveDestinationVerifier.Result.VERIFIED, outcome.result)
    }

    @Test
    fun aMissingEntryIsIncompleteNotSuccess() {
        val archive = zip("a.txt" to "hello", "b.txt" to "world!")
        val dest = tempDir()
        File(dest, "a.txt").writeText("hello")
        val outcome = ArchiveDestinationVerifier.verify(archive.absolutePath, dest.absolutePath)
        assertEquals(ArchiveDestinationVerifier.Result.INCONCLUSIVE, outcome.result)
        assertTrue(outcome.note.contains("partial extraction"))
    }

    @Test
    fun nothingExtractedIsRefuted() {
        val archive = zip("a.txt" to "hello")
        val outcome = ArchiveDestinationVerifier.verify(archive.absolutePath, tempDir().absolutePath)
        assertEquals(ArchiveDestinationVerifier.Result.REFUTED, outcome.result)
    }

    @Test
    fun aShortFileIsRefuted() {
        val archive = zip("a.txt" to "0123456789")
        val dest = tempDir()
        File(dest, "a.txt").writeText("012")
        val outcome = ArchiveDestinationVerifier.verify(archive.absolutePath, dest.absolutePath)
        assertEquals(ArchiveDestinationVerifier.Result.REFUTED, outcome.result)
        assertTrue(outcome.note.contains("extracted short"))
    }

    @Test
    fun anUnexpectedFileIsReportedWithoutFailingTheExtraction() {
        val archive = zip("a.txt" to "hello")
        val dest = tempDir()
        File(dest, "a.txt").writeText("hello")
        File(dest, "stray.txt").writeText("who put this here")
        val outcome = ArchiveDestinationVerifier.verify(archive.absolutePath, dest.absolutePath)
        assertTrue(outcome.ok)
        assertTrue(outcome.note.contains("also present"))
    }

    @Test
    fun aDirectoryWhereAFileShouldBeIsTheWrongType() {
        val archive = zip("a.txt" to "hello")
        val dest = tempDir()
        File(dest, "a.txt").mkdirs()
        val outcome = ArchiveDestinationVerifier.verify(archive.absolutePath, dest.absolutePath)
        assertEquals(ArchiveDestinationVerifier.Result.REFUTED, outcome.result)
    }

    @Test
    fun aCorruptArchiveIsRefutedAndAnUnknownFormatIsNot() {
        val bad = File(tempDir(), "bad.zip")
        bad.writeText("this is not a zip at all")
        assertEquals(ArchiveDestinationVerifier.Result.REFUTED,
            ArchiveDestinationVerifier.verify(bad.absolutePath, tempDir().absolutePath).result)
        val unsupported = File(tempDir(), "thing.rar")
        unsupported.writeText("rar bytes")
        assertEquals(ArchiveDestinationVerifier.Result.NOT_CHECKABLE,
            ArchiveDestinationVerifier.verify(unsupported.absolutePath, tempDir().absolutePath).result)
    }

    @Test
    fun aPathTraversalEntryIsRefused() {
        val archive = zip("../../escape.txt" to "nope")
        val outcome = ArchiveDestinationVerifier.verify(archive.absolutePath, tempDir().absolutePath)
        assertEquals(ArchiveDestinationVerifier.Result.REFUTED, outcome.result)
        assertTrue(outcome.note.contains("unsafe paths"))
    }

    @Test
    fun nestedEntriesAreMatchedRelativeToTheDestination() {
        val archive = File(tempDir(), "nested.zip")
        ZipOutputStream(archive.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("src/deep/Main.kt"))
            out.write("fun main() {}".toByteArray())
            out.closeEntry()
        }
        val dest = tempDir()
        File(dest, "src/deep").mkdirs()
        File(dest, "src/deep/Main.kt").writeText("fun main() {}")
        assertEquals(ArchiveDestinationVerifier.Result.VERIFIED,
            ArchiveDestinationVerifier.verify(archive.absolutePath, dest.absolutePath).result)
    }

    @Test
    fun anUnreadableDestinationIsUnknownNotVerified() {
        val archive = zip("a.txt" to "hello")
        assertEquals(ArchiveDestinationVerifier.Result.NOT_CHECKABLE,
            ArchiveDestinationVerifier.verify(archive.absolutePath, "/definitely/not/here").result)
        assertEquals(ArchiveDestinationVerifier.Result.NOT_CHECKABLE,
            ArchiveDestinationVerifier.verify(archive.absolutePath, null).result)
    }
}
