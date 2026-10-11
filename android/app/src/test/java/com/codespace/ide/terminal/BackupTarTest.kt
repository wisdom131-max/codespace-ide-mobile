package com.codespace.ide.terminal

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * E17 device round-trip (2026-10-11, advisor item 1a): reproduce the EXACT
 * archive shape that failed on the owner's device — a backup made by the OLD
 * writer, whose walkTopDown followed the "bin -> usr/bin" directory symlink and
 * emitted bin/* traversal entries ordered AFTER the symlink itself:
 *
 *   ./                       (dir)
 *   bin -> usr/bin           (symlink)
 *   bin/add-apt-repository   (regular file, TRAVERSAL duplicate)
 *   usr/bin/add-apt-repository (regular file, the real one)
 *   usr/bin/bash             (regular file)
 *
 * The old extractor created the bin symlink first, then tried to write
 * bin/add-apt-repository through the (broken — usr/bin did not exist yet)
 * symlink and failed with FileNotFoundException ENOENT, aborting after 3
 * entries ("1 of 3 entries could not be written").
 */
class BackupTarTest {

    private fun tempDir(name: String): File =
        File(createTempRoot(), name).apply { mkdirs() }

    private fun createTempRoot(): File =
        java.nio.file.Files.createTempDirectory("backuptar-test").toFile()

    /** Build a .tar.gz archive from (name, kind, data) triples. */
    private fun buildArchive(entries: List<Triple<String, String, ByteArray>>): File {
        val out = File(createTempRoot(), "archive.tar.gz")
        val bytes = ByteArrayOutputStream()
        GzipCompressorOutputStream(bytes).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
                entries.forEach { (name, kind, data) ->
                    when (kind) {
                        "dir" -> {
                            tar.putArchiveEntry(TarArchiveEntry(name))
                            tar.closeArchiveEntry()
                        }
                        "link" -> {
                            // (kind == "link", data == target)
                            val e = TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK)
                            e.linkName = String(data)
                            tar.putArchiveEntry(e)
                            tar.closeArchiveEntry()
                        }
                        else -> {
                            val e = TarArchiveEntry(name)
                            e.size = data.size.toLong()
                            tar.putArchiveEntry(e)
                            ByteArrayInputStream(data).use { it.copyTo(tar) }
                            tar.closeArchiveEntry()
                        }
                    }
                }
            }
        }
        out.writeBytes(bytes.toByteArray())
        return out
    }

    private fun entryNames(archive: File): List<Pair<String, String>> {
        val names = ArrayList<Pair<String, String>>()
        GzipCompressorInputStream(archive.inputStream()).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var e = tar.nextEntry
                while (e != null) {
                    names.add(Pair(e.name, if (e.isSymbolicLink) "link" else if (e.isDirectory) "dir" else "file"))
                    e = tar.nextEntry
                }
            }
        }
        return names
    }

    /** 1a: the EXACT old-writer shape that failed on device restores cleanly now. */
    @Test
    fun oldWriterShapeRestoresCleanly() {
        val content = "apt script".toByteArray()
        val archive = buildArchive(
            listOf(
                Triple("./", "dir", ByteArray(0)),
                Triple("bin", "link", "usr/bin".toByteArray()),
                Triple("bin/add-apt-repository", "file", content),
                Triple("usr", "dir", ByteArray(0)),
                Triple("usr/bin", "dir", ByteArray(0)),
                Triple("usr/bin/add-apt-repository", "file", content),
                Triple("usr/bin/bash", "file", "#!/bin/bash".toByteArray()),
            )
        )
        val dest = tempDir("restore-dest")
        val scan = BackupTar.scan(archive)
        assertEquals(6, scan.totalEntries, "scan totals (the './' header is rejected, not counted)")
        val result = BackupTar.extract(archive, dest, scan) { }

        assertEquals(0, result.fileFailures, "no file failures — the device bug is fixed")
        assertEquals(1, result.duplicatesSkipped, "the traversal duplicate is skipped, counted, not silent")
        assertTrue(result.bashOk, "usr/bin/bash must exist")
        assertEquals(1, result.symlinksCreated, "bin -> usr/bin symlink created (deferred, after files)")
        // The traversal entry landed at its REAL destination...
        assertEquals(
            "apt script",
            File(dest, "usr/bin/add-apt-repository").readText(),
        )
        // ...and bin is a symlink, NOT a directory stuffed with duplicates.
        assertTrue(Files.isSymbolicLink(File(dest, "bin").toPath()), "bin is a symlink")
        assertTrue(result.verifyLines.any { it.startsWith("bash ") && it.endsWith("\u2713") })
    }

    /** 1b: an entry that traverses an escaping symlink can NEVER write outside the root. */
    @Test
    fun symlinkEscapeCannotWriteOutsideRoot() {
        val archive = buildArchive(
            listOf(
                Triple("x", "dir", ByteArray(0)),
                Triple("x/l", "link", "../../../sdcard".toByteArray()),
                Triple("x/l/evil", "file", "evil".toByteArray()),
                Triple("usr/bin/bash", "file", "#!/bin/bash".toByteArray()),
            )
        )
        val dest = tempDir("escape-dest")
        val scan = BackupTar.scan(archive)
        val result = BackupTar.extract(archive, dest, scan) { }

        assertEquals(0, result.fileFailures)
        assertTrue(result.rejectedEntries >= 1, "the escaping entry is rejected")
        assertFalse(File(dest, "x/l/evil").exists(), "nothing written through the escaping symlink")
        assertTrue(result.bashOk, "the innocent entries still restore")
    }

    /** 1b: guest-ABSOLUTE symlink targets resolve INSIDE the root, never the host root. */
    @Test
    fun absoluteGuestSymlinkResolvesInsideRoot() {
        val archive = buildArchive(
            listOf(
                Triple("lib64", "link", "/usr/lib".toByteArray()),
                Triple("lib64/libz.so.1", "file", "z".toByteArray()),
                Triple("usr/bin/bash", "file", "#!/bin/bash".toByteArray()),
            )
        )
        val dest = tempDir("abs-dest")
        val scan = BackupTar.scan(archive)
        val result = BackupTar.extract(archive, dest, scan) { }

        assertEquals("z", File(dest, "usr/lib/libz.so.1").readText())
        assertTrue(result.bashOk)
        assertEquals(0, result.fileFailures)
    }

    /** 1e: the FIXED writer never emits entries that traverse symlinked directories. */
    @Test
    fun fixedWriterNeverEmitsTraversalEntries() {
        val rootfs = tempDir("fake-rootfs")
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/bash").writeText("#!/bin/bash")
        Files.createSymbolicLink(
            File(rootfs, "bin").toPath(),
            java.nio.file.Paths.get("usr/bin"),
        )

        val bytes = ByteArrayOutputStream()
        GzipCompressorOutputStream(bytes).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                BackupTar.writeRootfs(rootfs, tar, emptyList()) { }
            }
        }
        val archive = File(createTempRoot(), "new-backup.tar.gz")
        archive.writeBytes(bytes.toByteArray())
        val names = entryNames(archive).map { it.first }

        assertTrue(names.none { it.startsWith("bin/") }, "no traversal entries through bin")
        assertTrue(names.contains("bin"), "the bin symlink itself is kept")
        assertTrue(names.contains("usr/bin/bash"))
    }

    /** 1c/1d: failures report REAL totals — never a mid-abort "1 of 3". */
    @Test
    fun failureMessageCarriesRealTotals() {
        val archive = buildArchive(
            listOf(
                Triple("usr/bin/bash", "file", "#!/bin/bash".toByteArray()),
                Triple("usr/bin/other", "file", "x".toByteArray()),
            )
        )
        val dest = tempDir("ro-dest")
        dest.setWritable(false)
        val scan = BackupTar.scan(archive)
        val result = BackupTar.extract(archive, dest, scan) { }
        dest.setWritable(true)

        assertEquals(2, result.fileFailures, "every file failure is counted (no early abort)")
        assertEquals(2, result.totalEntries, "totals come from the scan pass")
        assertEquals(2, result.processed, "processed counts every entry it saw")
        assertFalse(result.bashOk, "bash never landed — the swap must be refused")
        assertTrue(result.firstFailure!!.contains("usr/bin/bash"), "first failure names its entry")
    }

    /** 1c: post-swap verify reports node/python3 as present/absent, bash required. */
    @Test
    fun verifyReportsOptionalTools() {
        val archive = buildArchive(
            listOf(
                Triple("usr/bin/bash", "file", "#!/bin/bash".toByteArray()),
            )
        )
        val dest = tempDir("verify-dest")
        val scan = BackupTar.scan(archive)
        val result = BackupTar.extract(archive, dest, scan) { }
        assertTrue(result.bashOk)
        assertTrue(result.verifyLines.any { it.startsWith("node ") && it.endsWith("\u2014") }, "node reported absent, not fatal")
        assertTrue(result.verifyLines.any { it.startsWith("python3 ") && it.endsWith("\u2014") })
    }
}
