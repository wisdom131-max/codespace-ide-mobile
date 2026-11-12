package com.codespace.ide.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ZipToolsTest {

    private fun tmp(): File = Files.createTempDirectory("ziptools").toFile()

    private fun write(f: File, s: String): File {
        f.parentFile?.mkdirs(); f.writeText(s); return f
    }

    @Test
    fun pathsOutsideTheWorkspaceAreRefusedBeforeAnyWork() {
        val root = tmp()
        val outside = tmp()
        assertNull(ZipTools.resolveInside(root, outside.path))
        assertNull(ZipTools.resolveInside(root, "../escape.txt"))
        assertNotNull(ZipTools.resolveInside(root, "src/a.txt"))
        val msg = ZipTools.create(root, listOf("../x"), outside.path + "/a.zip", overwrite = false)
        assertTrue(msg.startsWith("Refused: the destination is outside the workspace"))
        val src = ZipTools.extract(root, outside.path + "/a.zip", null, overwrite = false)
        assertTrue(src.startsWith("Refused: the archive path is outside the workspace"))
        val lst = ZipTools.list(root, outside.path + "/a.zip", 0, 10)
        assertTrue(lst.startsWith("Refused: the archive path is outside the workspace"))
    }

    @Test
    fun theDestinationMustBeAZip() {
        val root = tmp()
        write(File(root, "a.txt"), "alpha")
        val msg = ZipTools.create(root, listOf("a.txt"), "out.tar", overwrite = false)
        assertTrue(msg.contains("must end in .zip"))
    }

    @Test
    fun createReportsFilesDirectoriesSizeAndSkipped() {
        val root = tmp()
        write(File(root, "a.txt"), "alpha")
        write(File(root, "src/b.txt"), "beta")
        val msg = ZipTools.create(root, listOf("a.txt", "src"), "out.zip", overwrite = false)
        assertTrue(msg, msg.startsWith("Created "))
        assertTrue(msg.contains("Files: 2"))
        assertTrue(msg.contains("Directories: 1"))
        assertTrue(msg.contains("uncompressed"))
    }

    @Test
    fun createRefusesToOverwriteWithoutTheFlagAndSaysWhy() {
        val root = tmp()
        write(File(root, "a.txt"), "alpha")
        write(File(root, "out.zip"), "existing")
        val msg = ZipTools.create(root, listOf("a.txt"), "out.zip", overwrite = false)
        assertTrue(msg.contains("already exists"))
        assertEquals("existing", File(root, "out.zip").readText())
        val ok = ZipTools.create(root, listOf("a.txt"), "out.zip", overwrite = true)
        assertTrue(ok.startsWith("Created "))
    }

    @Test
    fun aMissingSourceIsAnErrorNotAnEmptyArchive() {
        val root = tmp()
        val msg = ZipTools.create(root, listOf("nope.txt"), "out.zip", overwrite = false)
        assertTrue(msg.contains("does not exist"))
        assertFalse(File(root, "out.zip").exists())
    }

    @Test
    fun extractDefaultsTheDestinationToTheArchivesFolder() {
        val root = tmp()
        write(File(root, "a.txt"), "alpha")
        ZipTools.create(root, listOf("a.txt"), "in.zip", overwrite = false)
        File(root, "a.txt").delete()
        val msg = ZipTools.extract(root, "in.zip", null, overwrite = false)
        assertTrue(msg, msg.startsWith("Extracted "))
        assertTrue(msg.contains("Files: 1"))
        assertTrue(File(root, "a.txt").exists())
    }

    @Test
    fun extractSaysPartiallyAndNamesWhatWasSkipped() {
        val root = tmp()
        write(File(root, "a.txt"), "alpha")
        ZipTools.create(root, listOf("a.txt"), "in.zip", overwrite = false)
        // a.txt now exists in the destination, so an extraction without overwrite must skip it and
        // SAY so rather than claiming a clean extraction of nothing.
        write(File(root, "a.txt"), "already here")
        val msg = ZipTools.extract(root, "in.zip", null, overwrite = false)
        assertTrue(msg, msg.startsWith("Partially extracted "))
        assertTrue(msg.contains("Skipped: 1"))
        assertTrue(msg.contains("already exists"))
        assertTrue(msg.contains("Not every entry was written"))
    }

    @Test
    fun listShowsTheSummaryAndPagesWithAnOffsetHint() {
        val root = tmp()
        write(File(root, "a.txt"), "alpha")
        write(File(root, "b.txt"), "beta")
        ZipTools.create(root, listOf("a.txt", "b.txt"), "in.zip", overwrite = false)
        val page = ZipTools.list(root, "in.zip", 0, 1)
        assertTrue(page.contains("2 entries"))
        assertTrue(page.contains("offset=1"))
        assertTrue(page.contains("zip_list with offset="))
        val second = ZipTools.list(root, "in.zip", 1, 1)
        assertFalse(second.contains("offset=2"))
    }

    @Test
    fun listOutputStaysBoundedForALargeArchive() {
        val root = tmp()
        val many = (1..300).map { "file_number_$it.txt" }
        many.forEach { write(File(root, it), "x") }
        ZipTools.create(root, many, "big.zip", overwrite = false)
        val page = ZipTools.list(root, "big.zip", 0, 500)
        assertTrue("listing must be bounded", page.length <= ZipTools.MAX_RESULT_CHARS + 80)
        assertTrue(page.contains("300 entries"))
    }

    @Test
    fun aMalformedArchiveIsReportedInWords() {
        val root = tmp()
        write(File(root, "bad.zip"), "not a zip")
        val msg = ZipTools.list(root, "bad.zip", 0, 10)
        assertTrue(msg.contains("Not a readable zip"))
    }

    private fun assertEquals(expected: Any?, actual: Any?) =
        org.junit.Assert.assertEquals(expected, actual)
}
