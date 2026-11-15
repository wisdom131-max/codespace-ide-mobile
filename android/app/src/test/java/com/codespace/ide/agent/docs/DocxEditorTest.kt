package com.codespace.ide.agent.docs

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 2.5: an edit must not cost the document everything the editor does not understand.
 *
 * The test that matters is the byte comparison of the untouched parts: if styles.xml comes back
 * different from what went in, "preserve formatting" is a claim rather than a behaviour.
 */
class DocxEditorTest {

    private fun parts(bytes: ByteArray): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                map[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        return map
    }

    private fun original(): ByteArray = DocxWriter.build(
        title = "Report",
        blocks = DocxWriter.blocksOf("# Summary\nFirst paragraph."),
    )

    @Test
    fun theExistingTextIsStillThereAfterAnAppend() {
        val updated = DocxEditor.append(original(), DocxWriter.blocksOf("## Added\nNew words."))
        val xml = parts(updated).getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue(xml.contains("Summary"))
        assertTrue(xml.contains("First paragraph."))
        assertTrue(xml.contains("Added"))
        assertTrue(xml.contains("New words."))
    }

    @Test
    fun newHeadingsReachTheOutline() {
        val updated = DocxEditor.append(original(), DocxWriter.blocksOf("## Added"))
        assertEquals(listOf(1, 1, 2), DocxOutline.outline(updated).map { it.level })
    }

    @Test
    fun everyOtherPartIsCopiedThroughUnchanged() {
        val before = parts(original())
        val after = parts(DocxEditor.append(original(), DocxWriter.blocksOf("text")))
        assertEquals(before.keys, after.keys)
        before.filterKeys { it != "word/document.xml" }.forEach { (name, bytes) ->
            assertTrue(name + " must be byte-identical", bytes.contentEquals(after.getValue(name)))
        }
    }

    @Test
    fun theNewParagraphsLandBeforeTheSectionProperties() {
        val xml = parts(DocxEditor.append(original(), DocxWriter.blocksOf("appended")))
            .getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue(xml.indexOf("appended") < xml.indexOf("<w:sectPr"))
        assertTrue(xml.endsWith("</w:document>"))
    }

    @Test
    fun aNonWordFileIsRefused() {
        assertFalse(DocxEditor.isDocx("plain text".toByteArray()))
        assertTrue(DocxEditor.isDocx(original()))
    }
}
