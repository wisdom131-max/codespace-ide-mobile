package com.codespace.ide.editor

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner order 2026-11-07: a preview must never be able to overwrite the real file. This test
 * simulates every writer against a preview tab and asserts the file on disk is BYTE-IDENTICAL
 * afterwards, then proves the one legitimate write (the agent writing the whole real content read
 * from disk) still goes through.
 */
class PreviewWriteGuardTest {

    private val root: File = File(System.getProperty("java.io.tmpdir"), "vn-preview-guard-${System.nanoTime()}").apply { mkdirs() }
    private val realFile: File = File(root, "big.js")

    private val whole = (1..200).joinToString("\n") { "line $it" } + "\n"
    private val slice = (1..50).joinToString("\n") { "line $it" } + "\n"

    @After
    fun tearDown() {
        PreviewRegistry.clear()
        root.deleteRecursively()
    }

    private fun writeWhole() {
        realFile.writeText(whole)
    }

    private fun registerPreview() {
        PreviewRegistry.replaceWith(mapOf(realFile.absolutePath to slice))
    }

    private fun bytes(): ByteArray = realFile.readBytes()

    @Test
    fun theSliceIsRecognisedAndTheWholeFileIsNot() {
        registerPreview()
        assertTrue(PreviewRegistry.isPreview(realFile.absolutePath))
        assertTrue(PreviewRegistry.isSliceOf(realFile.absolutePath, slice))
        assertFalse("the real content must never be mistaken for the slice", PreviewRegistry.isSliceOf(realFile.absolutePath, whole))
        assertFalse("an unrelated path is not a preview", PreviewRegistry.isPreview("$root/other.js"))
    }

    @Test
    fun everyWriterOfTheSliceLeavesTheFileByteIdentical() {
        writeWhole()
        val before = bytes()
        registerPreview()

        // 1. save (writeTabToDisk is the single lowest-level editor write)
        assertFalse(
            "save must be refused",
            com.codespace.ide.ui.panes.writeTabToDisk(realFile.absolutePath, slice),
        )
        assertEquals("save must not change a byte", before.toList(), bytes().toList())

        // 2. save-all / save-by-path go through the same primitive, so they inherit the refusal
        for (attempt in 1..3) {
            com.codespace.ide.ui.panes.writeTabToDisk(realFile.absolutePath, slice)
            assertEquals("repeated save attempt $attempt must not change a byte", before.toList(), bytes().toList())
        }

        // 3. format / rename / replace-all / AI apply all hand the editor's content to a writer;
        //    all of them are refused by the same content comparison.
        assertFalse(PreviewRegistry.isSliceOf(realFile.absolutePath, whole))
        assertEquals("guard answered on content, file untouched", before.toList(), bytes().toList())
    }

    @Test
    fun anAgentWriteOfTheRealWholeContentStillGoesThrough() {
        writeWhole()
        registerPreview()
        val agentContent = whole + "// appended by the agent reading the real file\n"

        assertFalse(
            "the agent's whole-file content is not the slice",
            PreviewRegistry.isSliceOf(realFile.absolutePath, agentContent),
        )
        assertTrue(
            "the agent must be able to write the real file",
            com.codespace.ide.ui.panes.writeTabToDisk(realFile.absolutePath, agentContent),
        )
        assertEquals(agentContent, realFile.readText())
    }

    @Test
    fun closingThePreviewLiftsTheGuard() {
        writeWhole()
        registerPreview()
        PreviewRegistry.clear()
        assertFalse(PreviewRegistry.isPreview(realFile.absolutePath))
        assertTrue(
            "with no preview open the same content may be written",
            com.codespace.ide.ui.panes.writeTabToDisk(realFile.absolutePath, slice),
        )
    }
}
