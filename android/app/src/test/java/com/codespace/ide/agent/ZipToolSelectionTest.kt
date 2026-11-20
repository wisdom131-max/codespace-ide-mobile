package com.codespace.ide.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PROJECT 3: the tool layer of selective extraction. `zip_extract` gains an optional `entries`
 * list; these drive `ZipTools.extract` itself (no Context needed) so the pass-through, the honest
 * "none of the requested entries" answer and the refusal paths are proven, not assumed.
 */
class ZipToolSelectionTest {

    private val root: File = Files.createTempDirectory("vncode-zipselection").toFile()

    private fun zip(): File {
        val f = File(root, "sample.zip")
        ZipOutputStream(f.outputStream()).use { out ->
            for ((name, body) in listOf("a.txt" to "A", "b.txt" to "B")) {
                out.putNextEntry(ZipEntry(name)); out.write(body.toByteArray()); out.closeEntry()
            }
        }
        return f
    }

    @Test fun `selecting one entry reports a selection and extracts it`() {
        zip()
        val out = ZipTools.extract(root, "sample.zip", null, false, null, listOf("b.txt"))
        assertTrue(out, out.contains("selected 1 requested entries"))
        assertTrue(File(root, "b.txt").isFile)
        assertTrue(!File(root, "a.txt").exists())
    }

    @Test fun `a selection that matches nothing says so instead of claiming success`() {
        zip()
        val out = ZipTools.extract(root, "sample.zip", null, false, null, listOf("nope.txt"))
        assertTrue(out, out.startsWith("None of the 1 requested entries"))
    }

    @Test fun `no selection still extracts the whole archive`() {
        zip()
        val out = ZipTools.extract(root, "sample.zip", null, false, null, null)
        assertTrue(out, out.contains("Extracted sample.zip"))
        assertTrue(File(root, "a.txt").isFile && File(root, "b.txt").isFile)
    }

    @Test fun `an archive outside the workspace is still refused`() {
        val out = ZipTools.extract(root, "../outside.zip", null, false, null, listOf("a.txt"))
        assertTrue(out, out.startsWith("Refused"))
    }
}
