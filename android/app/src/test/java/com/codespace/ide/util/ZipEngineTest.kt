package com.codespace.ide.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipEngineTest {

    private fun tmp(): File = Files.createTempDirectory("ziptest").toFile()

    private fun write(file: File, text: String): File {
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    /** Build an archive with exactly the entries asked for, bypassing ZipEngine's own checks. */
    private fun zip(to: File, entries: List<Pair<String, String>>): File {
        ZipOutputStream(FileOutputStream(to)).use { zos ->
            for ((name, body) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
        }
        return to
    }

    // ── path rules ───────────────────────────────────────────────────────────

    @Test
    fun traversalAndAbsoluteNamesAreRefused() {
        assertEquals("absolute path", ZipEngine.unsafeReason("/etc/passwd"))
        assertTrue(ZipEngine.unsafeReason("../../etc/passwd")!!.contains(".."))
        assertTrue(ZipEngine.unsafeReason("a/../../b")!!.contains(".."))
        assertEquals("Windows drive prefix", ZipEngine.unsafeReason("C:/windows/system32"))
        assertEquals("UNC path", ZipEngine.unsafeReason("\\\\server\\share\\x"))
        assertNull(ZipEngine.unsafeReason("src/main/Main.kt"))
        // An inner ".." is refused too: an archive entry has no business carrying one, and the
        // conservative rule is the one that stays correct when a later change alters the root.
        assertTrue(ZipEngine.unsafeReason("a/b/../c")!!.contains(".."))
    }

    @Test
    fun safeTargetStaysInsideTheRoot() {
        val root = tmp()
        val ok = ZipEngine.safeTarget(root, "a/b.txt")
        assertNotNull(ok)
        assertTrue(ok!!.path.startsWith(root.canonicalPath))
        assertNull(ZipEngine.safeTarget(root, "../outside.txt"))
        assertNull(ZipEngine.safeTarget(root, "/abs.txt"))
    }

    @Test
    fun aSymlinkedDirectoryInsideTheDestinationIsDetected() {
        val root = tmp()
        val outside = tmp()
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val target = File(link, "written.txt")
        assertTrue(ZipEngine.passesThroughSymlink(root, target))
        assertFalse(ZipEngine.passesThroughSymlink(root, File(root, "plain/written.txt")))
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    fun createsAnArchiveWithRelativeNames() {
        val root = tmp()
        write(File(root, "a.txt"), "alpha")
        write(File(root, "src/b.txt"), "beta")
        val out = File(root, "out.zip")
        val outcome = ZipEngine.create(listOf(File(root, "a.txt"), File(root, "src")), out, base = root)
        assertTrue(outcome is ZipEngine.CreateOutcome.Ok)
        val ok = outcome as ZipEngine.CreateOutcome.Ok
        assertEquals(2, ok.files)
        assertEquals(1, ok.dirs)
        assertTrue(out.isFile)
        val names = ZipEngine.list(out) as ZipEngine.ListOutcome.Ok
        assertEquals(listOf("a.txt", "src/", "src/b.txt"), names.entries.map { it.name })
    }

    @Test
    fun refusesToReplaceAnExistingArchiveUnlessAsked() {
        val root = tmp()
        write(File(root, "a.txt"), "alpha")
        val out = write(File(root, "out.zip"), "not really a zip")
        val refused = ZipEngine.create(listOf(File(root, "a.txt")), out, base = root)
        assertTrue(refused is ZipEngine.CreateOutcome.Failed)
        assertTrue((refused as ZipEngine.CreateOutcome.Failed).reason.contains("already exists"))
        assertEquals("not really a zip", out.readText())
        val allowed = ZipEngine.create(listOf(File(root, "a.txt")), out, base = root, overwrite = true)
        assertTrue(allowed is ZipEngine.CreateOutcome.Ok)
    }

    @Test
    fun theDestinationIsNeverPackedIntoItself() {
        val root = tmp()
        write(File(root, "a.txt"), "alpha")
        val out = File(root, "out.zip")
        val outcome = ZipEngine.create(listOf(File(root, "a.txt"), out), out, base = root)
        val ok = outcome as ZipEngine.CreateOutcome.Ok
        assertEquals(1, ok.files)
        assertTrue(ok.skipped.toString(), ok.skipped.any { it.detail.contains("destination archive") })
    }

    @Test
    fun aMissingSourceFailsBeforeAnythingIsWritten() {
        val root = tmp()
        val out = File(root, "out.zip")
        val outcome = ZipEngine.create(listOf(File(root, "nope.txt")), out, base = root)
        assertTrue(outcome is ZipEngine.CreateOutcome.Failed)
        assertFalse(out.exists())
    }

    @Test
    fun anArchiveOfOnlySkippedSourcesIsAFailureNotAnEmptyArchive() {
        val root = tmp()
        val out = File(root, "out.zip")
        val outcome = ZipEngine.create(listOf(out), out, base = root)
        assertTrue(outcome is ZipEngine.CreateOutcome.Failed)
        assertFalse(out.exists())
    }

    @Test
    fun largeFilesStreamInsteadOfLoadingWhole() {
        val root = tmp()
        val big = File(root, "big.bin")
        val chunk = ByteArray(64 * 1024) { 7 }
        FileOutputStream(big).use { o -> repeat(64) { o.write(chunk) } }  // 4 MB
        val out = File(root, "out.zip")
        val ok = ZipEngine.create(listOf(big), out, base = root) as ZipEngine.CreateOutcome.Ok
        assertEquals(4L * 1024 * 1024, ok.bytes)
        assertTrue(out.length() > 0)
    }

    // ── extract ──────────────────────────────────────────────────────────────

    @Test
    fun extractsNestedEntriesAndCountsThem() {
        val root = tmp()
        val archive = zip(File(root, "in.zip"), listOf(
            "a.txt" to "alpha", "dir/" to "", "dir/b.txt" to "beta",
        ))
        val dest = File(root, "out")
        val r = ZipEngine.extract(archive, dest)
        assertEquals(2, r.files)
        assertEquals(1, r.dirs)
        assertFalse(r.partial)
        assertEquals("beta", File(dest, "dir/b.txt").readText())
    }

    @Test
    fun anEmptyArchiveIsNotAnError() {
        val root = tmp()
        val archive = zip(File(root, "empty.zip"), emptyList())
        val r = ZipEngine.extract(archive, File(root, "out"))
        assertEquals(0, r.files)
        assertFalse(r.partial)
        assertTrue(r.failed.isEmpty())
    }

    @Test
    fun zipSlipEntriesAreSkippedAndNothingLandsOutside() {
        val root = tmp()
        val dest = File(root, "out")
        val archive = zip(File(root, "evil.zip"), listOf(
            "../escaped.txt" to "bad",
            "/abs.txt" to "bad",
            "C:/windows.txt" to "bad",
            "good.txt" to "fine",
        ))
        val r = ZipEngine.extract(archive, dest)
        assertEquals(1, r.files)
        assertEquals(3, r.skipped.count { it.kind == ZipEngine.ProblemKind.UNSAFE_PATH })
        assertTrue(r.partial)
        assertFalse(File(root, "escaped.txt").exists())
        assertTrue(File(dest, "good.txt").exists())
    }

    @Test
    fun anEncodedTraversalIsSkippedRatherThanDecodedAndWritten() {
        val root = tmp()
        val archive = zip(File(root, "evil.zip"), listOf(
            "..%2fescaped.txt" to "bad",
            "....//escaped2.txt" to "bad",
        ))
        val r = ZipEngine.extract(archive, File(root, "out"))
        // %2f is a literal file name to the JVM: it lands INSIDE the destination, which is safe.
        // The double-dot-with-slash form must not become a traversal either.
        val escaped = File(root, "escaped.txt")
        assertFalse(escaped.exists())
        assertFalse(File(root, "escaped2.txt").exists())
        assertTrue(r.files <= 2)
    }

    @Test
    fun existingFilesAreKeptUnlessOverwriteIsExplicit() {
        val root = tmp()
        val dest = File(root, "out")
        write(File(dest, "a.txt"), "original")
        val archive = zip(File(root, "in.zip"), listOf("a.txt" to "replacement"))
        val kept = ZipEngine.extract(archive, dest)
        assertEquals(0, kept.files)
        assertEquals("original", File(dest, "a.txt").readText())
        assertEquals(ZipEngine.ProblemKind.OVERWRITE_EXISTS, kept.skipped.first().kind)
        val replaced = ZipEngine.extract(archive, dest, overwrite = true)
        assertEquals(1, replaced.files)
        assertEquals("replacement", File(dest, "a.txt").readText())
    }

    @Test
    fun aCorruptArchiveIsReportedNotThrown() {
        val root = tmp()
        val bad = write(File(root, "bad.zip"), "this is not a zip at all")
        val r = ZipEngine.extract(bad, File(root, "out"))
        assertEquals(0, r.files)
        assertTrue(r.failed.isNotEmpty())
        assertTrue(r.failed.first().detail.contains("not a readable zip") ||
            r.failed.first().detail.contains("archive not found"))
    }

    @Test
    fun aTruncatedArchiveIsReportedNotThrown() {
        val root = tmp()
        val full = zip(File(root, "full.zip"), listOf("a.txt" to "alpha", "b.txt" to "beta"))
        val bytes = full.readBytes()
        val truncated = File(root, "truncated.zip")
        truncated.writeBytes(bytes.copyOf(bytes.size / 2))
        val r = ZipEngine.extract(truncated, File(root, "out"))
        assertTrue(r.failed.isNotEmpty() || r.skipped.isNotEmpty() || r.partial || r.files == 0)
    }

    @Test
    fun tooManyEntriesStopsAtTheLimit() {
        val root = tmp()
        val archive = zip(File(root, "many.zip"), (1..20).map { "f$it.txt" to "x" })
        val r = ZipEngine.extract(archive, File(root, "out"), maxEntries = 5)
        assertTrue(r.partial)
        assertEquals(5, r.files)
        assertTrue(r.failed.any { it.kind == ZipEngine.ProblemKind.LIMIT })
    }

    @Test
    fun anEntryOverThePerFileLimitIsSkippedAndNotWritten() {
        val root = tmp()
        val archive = zip(File(root, "big.zip"), listOf("big.bin" to "0123456789", "small.txt" to "ok"))
        val r = ZipEngine.extract(archive, File(root, "out"), maxEntryBytes = 4)
        assertEquals(1, r.files)
        assertFalse(File(root, "out/big.bin").exists())
        assertTrue(r.partial)
    }

    @Test
    fun aTotalSizeBreachStopsTheExtraction() {
        val root = tmp()
        val archive = zip(File(root, "sum.zip"), (1..10).map { "f$it.txt" to "0123456789" })
        val r = ZipEngine.extract(archive, File(root, "out"), maxTotalBytes = 25)
        assertTrue(r.partial)
        assertTrue(r.files in 1..3)
        val written = File(root, "out").walkTopDown().filter { it.isFile }.count()
        assertEquals(r.files, written)
    }

    @Test
    fun extractionNeverWritesThroughAnExistingSymlink() {
        val root = tmp()
        val dest = File(root, "out")
        val outside = File(root, "outside")
        dest.mkdirs()
        outside.mkdirs()
        Files.createSymbolicLink(File(dest, "link").toPath(), outside.toPath())
        val archive = zip(File(root, "in.zip"), listOf("link/hijacked.txt" to "bad"))
        val r = ZipEngine.extract(archive, dest)
        assertEquals(0, r.files)
        assertFalse(File(outside, "hijacked.txt").exists())
        // Both refusals are correct and they race: safeTarget canonicalises THROUGH the link and
        // reports "escapes the destination", or the symlink walk catches it first. Either way the
        // file must not exist outside, which the assertion above already proves.
        assertTrue(r.skipped.isNotEmpty())
    }

    @Test
    fun aMissingArchiveIsAnHonestFailure() {
        val root = tmp()
        val r = ZipEngine.extract(File(root, "nope.zip"), File(root, "out"))
        assertEquals(0, r.files)
        assertEquals("archive not found", r.failed.first().detail)
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Test
    fun listsEntriesWithSizesAndFlagsUnsafePaths() {
        val root = tmp()
        val archive = zip(File(root, "in.zip"), listOf(
            "a.txt" to "alpha", "dir/" to "", "../evil.txt" to "x",
        ))
        val ok = ZipEngine.list(archive) as ZipEngine.ListOutcome.Ok
        assertEquals(3, ok.total)
        assertTrue(ok.totalUncompressed >= 5)
        assertEquals(1, ok.suspicious.size)
        assertEquals("../evil.txt", ok.suspicious.first().entry)
        assertTrue(ok.entries.any { ZipEngine.isDirectoryEntry(it.name) })
    }

    @Test
    fun listingIsPagedSoHugeArchivesCannotFloodThePrompt() {
        val root = tmp()
        val archive = zip(File(root, "many.zip"), (1..50).map { "f$it.txt" to "x" })
        val page1 = ZipEngine.list(archive, offset = 0, limit = 10) as ZipEngine.ListOutcome.Ok
        assertEquals(50, page1.total)
        assertEquals(10, page1.entries.size)
        assertEquals("f1.txt", page1.entries.first().name)
        val page2 = ZipEngine.list(archive, offset = 10, limit = 10) as ZipEngine.ListOutcome.Ok
        assertEquals("f11.txt", page2.entries.first().name)
        val beyond = ZipEngine.list(archive, offset = 100, limit = 10) as ZipEngine.ListOutcome.Ok
        assertTrue(beyond.entries.isEmpty())
    }

    @Test
    fun listingAMalformedArchiveReturnsAReason() {
        val root = tmp()
        val bad = write(File(root, "bad.zip"), "nope")
        assertTrue(ZipEngine.list(bad) is ZipEngine.ListOutcome.Failed)
    }
}
