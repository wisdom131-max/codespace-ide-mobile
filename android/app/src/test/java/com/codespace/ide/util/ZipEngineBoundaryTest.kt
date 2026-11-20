package com.codespace.ide.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P8.6: the extraction boundary. Each case is a way an archive tries to write somewhere it was not
 * asked to, or to cost more than it should. The rule under test is always the same: nothing outside
 * the destination, and an honest report instead of a silent success.
 */
class ZipEngineBoundaryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun zip(file: File, entries: List<Pair<String, String>>): File {
        ZipOutputStream(file.outputStream()).use { z ->
            entries.forEach { (n, c) ->
                z.putNextEntry(ZipEntry(n))
                if (c.isNotEmpty()) z.write(c.toByteArray())
                z.closeEntry()
            }
        }
        return file
    }

    @Test fun `an absolute path entry is refused`() {
        val root = tmp.newFolder()
        val dest = File(root, "out").apply { mkdirs() }
        val archive = zip(File(root, "a.zip"), listOf("/etc/passwd" to "x"))
        val r = ZipEngine.extract(archive, dest)
        assertEquals(0, r.files)
        assertFalse(File("/etc/passwd").let { it.exists() && it.readText() == "x" })
        assertTrue(r.skipped.isNotEmpty())
    }

    @Test fun `a dot dot entry is refused`() {
        val root = tmp.newFolder()
        val dest = File(root, "out").apply { mkdirs() }
        val archive = zip(File(root, "b.zip"), listOf("../escaped.txt" to "bad"))
        val r = ZipEngine.extract(archive, dest)
        assertEquals(0, r.files)
        assertFalse(File(root, "escaped.txt").exists())
    }

    @Test fun `a windows drive path is refused`() {
        val root = tmp.newFolder()
        val dest = File(root, "out").apply { mkdirs() }
        val archive = zip(File(root, "c.zip"), listOf("C:/Windows/evil.dll" to "bad"))
        val r = ZipEngine.extract(archive, dest)
        assertEquals(0, r.files)
    }

    @Test fun `the zip format itself refuses a duplicate entry name`() {
        // A duplicate cannot reach the extractor: ZipOutputStream rejects the second entry, so
        // there is no archive in which two entries claim one path. Pinned here so a future switch
        // to a raw writer has to answer for duplicate names explicitly.
        val root = tmp.newFolder()
        val f = File(root, "dup.zip")
        val thrown = runCatching {
            ZipOutputStream(f.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("same.txt")); z.write("one".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("same.txt")); z.write("two".toByteArray()); z.closeEntry()
            }
        }.exceptionOrNull()
        assertTrue(thrown is java.util.zip.ZipException)
    }

    @Test fun `a nested archive is written as a file and not auto-extracted`() {
        val root = tmp.newFolder()
        val dest = File(root, "out").apply { mkdirs() }
        val inner = zip(File(root, "inner.zip"), listOf("payload.txt" to "deep"))
        val outer = File(root, "outer.zip")
        ZipOutputStream(outer.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("inner.zip"))
            z.write(inner.readBytes())
            z.closeEntry()
        }
        val r = ZipEngine.extract(outer, dest)
        assertEquals(1, r.files)
        assertTrue(File(dest, "inner.zip").isFile)
        assertFalse(File(dest, "payload.txt").exists())
    }

    @Test fun `an entry over the per-entry limit is skipped`() {
        val root = tmp.newFolder()
        val dest = File(root, "out").apply { mkdirs() }
        val archive = zip(File(root, "e.zip"), listOf("big.txt" to "x".repeat(5000)))
        val r = ZipEngine.extract(archive, dest, maxEntryBytes = 1000)
        assertEquals(0, r.files)
        assertTrue(r.skipped.isNotEmpty())
    }

    @Test fun `too many entries stops with a partial report`() {
        val root = tmp.newFolder()
        val dest = File(root, "out").apply { mkdirs() }
        val archive = zip(File(root, "f.zip"), (1..20).map { "f$it.txt" to "x" })
        val r = ZipEngine.extract(archive, dest, maxEntries = 5)
        assertTrue(r.files <= 5)
        assertTrue(r.failed.isNotEmpty() || r.partial)
    }

    @Test fun `a malformed archive is reported not thrown`() {
        val root = tmp.newFolder()
        val dest = File(root, "out").apply { mkdirs() }
        val bad = File(root, "bad.zip").apply { writeText("this is not a zip") }
        val r = ZipEngine.extract(bad, dest)
        assertEquals(0, r.files)
        assertTrue(r.failed.isNotEmpty())
    }

    @Test fun `a destination symlink cannot be used to escape`() {
        val root = tmp.newFolder()
        val dest = File(root, "out").apply { mkdirs() }
        val outside = File(root, "outside").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(dest, "link").toPath(), outside.toPath())
        val archive = zip(File(root, "g.zip"), listOf("link/x.txt" to "bad"))
        val r = ZipEngine.extract(archive, dest)
        assertEquals(0, r.files)
        assertFalse(File(outside, "x.txt").exists())
    }

    @Test fun `a legitimate archive still extracts`() {
        val root = tmp.newFolder()
        val dest = File(root, "out").apply { mkdirs() }
        val archive = zip(File(root, "ok.zip"), listOf("a.txt" to "alpha", "d/" to "", "d/b.txt" to "beta"))
        val r = ZipEngine.extract(archive, dest)
        assertEquals(2, r.files)
        assertEquals("alpha", File(dest, "a.txt").readText())
        assertEquals("beta", File(dest, "d/b.txt").readText())
    }
}
