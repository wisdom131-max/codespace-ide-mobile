package com.codespace.ide.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PROJECT 3 (documents & media): selective extraction.
 *
 * The acceptance criterion is "listing, selective extraction and full extraction, with the existing
 * security checks intact". Selection happens before validation and before any write, so it must
 * never become a way past the traversal and symlink checks. These fixtures are real zip files on a
 * temp directory.
 */
class SelectiveExtractionTest {

    private val dir: File = Files.createTempDirectory("vncode-selective").toFile()

    private fun zip(vararg entries: Pair<String, String>): File {
        val f = File(dir, "sample.zip")
        ZipOutputStream(f.outputStream()).use { out ->
            for ((name, body) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return f
    }

    private fun dest(): File = File(dir, "out").also { it.mkdirs() }

    @Test fun `selecting one file extracts only that file`() {
        val z = zip("a.txt" to "A", "b.txt" to "B", "docs/c.txt" to "C")
        val out = ZipEngine.extract(z, dest(), only = setOf("b.txt"))
        assertEquals(1, out.files)
        assertTrue(File(dir, "out/b.txt").isFile)
        assertFalse(File(dir, "out/a.txt").exists())
        assertFalse(File(dir, "out/docs/c.txt").exists())
    }

    @Test fun `selecting a directory prefix takes everything under it`() {
        val z = zip("a.txt" to "A", "docs/c.txt" to "C", "docs/d.txt" to "D")
        val out = ZipEngine.extract(z, dest(), only = setOf("docs/"))
        assertEquals(2, out.files)
        assertTrue(File(dir, "out/docs/c.txt").isFile)
        assertTrue(File(dir, "out/docs/d.txt").isFile)
        assertFalse(File(dir, "out/a.txt").exists())
    }

    @Test fun `no selection extracts everything`() {
        val z = zip("a.txt" to "A", "docs/c.txt" to "C")
        val out = ZipEngine.extract(z, dest(), only = null)
        assertEquals(2, out.files)
    }

    @Test fun `a traversal entry cannot be extracted by naming it explicitly`() {
        val z = zip("../escape.txt" to "x", "safe.txt" to "S")
        val out = ZipEngine.extract(z, dest(), only = setOf("../escape.txt", "safe.txt"))
        assertFalse("nothing escapes the destination", File(dir, "escape.txt").exists())
        assertEquals(1, out.files)
        assertTrue(out.skipped.any { it.entry.contains("escape") })
    }

    @Test fun `isSelected matches an exact name and a directory prefix only`() {
        assertTrue(ZipEngine.isSelected("a.txt", setOf("a.txt")))
        assertTrue(ZipEngine.isSelected("docs/c.txt", setOf("docs/")))
        assertFalse(ZipEngine.isSelected("docs/c.txt", setOf("docs")))
        assertFalse(ZipEngine.isSelected("other/c.txt", setOf("docs/")))
    }

    @Test fun `a corrupt archive is refused rather than reported as extracted`() {
        val bad = File(dir, "bad.zip").also { it.writeText("this is not a zip") }
        val out = ZipEngine.extract(bad, dest())
        assertEquals(0, out.files)
        assertTrue("the reason is stated", out.failed.isNotEmpty())
    }
}
