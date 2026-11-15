package com.codespace.ide.agent.docs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 2.1: headings are structure, and structure must survive the extraction.
 *
 * A flattened document answers "what does it say" but not "how is it organised", which is the
 * question an edit has to answer before it can be placed.
 */
class DocxOutlineTest {

    private fun docx(bodyXml: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(bodyXml.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun para(style: String?, text: String): String {
        val pPr = if (style == null) "" else "<w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>"
        return "<w:p>$pPr<w:r><w:t>$text</w:t></w:r></w:p>"
    }

    @Test
    fun headingsComeOutInOrderWithTheirLevels() {
        val xml = "<w:document><w:body>" +
            para("Heading1", "Overview") + para(null, "Body words.") +
            para("Heading2", "Limits") + para("Heading3", "Build limits") +
            "</w:body></w:document>"
        val lines = DocxOutline.outline(docx(xml))
        assertEquals(listOf(1, 2, 3), lines.map { it.level })
        assertEquals(listOf("Overview", "Limits", "Build limits"), lines.map { it.text })
    }

    @Test
    fun bodyTextIsNotAnOutlineEntry() {
        val xml = "<w:document><w:body>" + para(null, "Just prose.") + "</w:body></w:document>"
        assertTrue(DocxOutline.outline(docx(xml)).isEmpty())
        assertEquals("", DocxOutline.render(docx(xml)))
    }

    @Test
    fun anOutlineLevelCountsWhenTheStyleDoesNotNameOne() {
        val xml = "<w:document><w:body><w:p><w:pPr><w:outlineLvl w:val=\"1\"/></w:pPr>" +
            "<w:r><w:t>Numbered heading</w:t></w:r></w:p></w:body></w:document>"
        val lines = DocxOutline.outline(docx(xml))
        assertEquals(1, lines.size)
        assertEquals(2, lines[0].level)
    }

    @Test
    fun aTitleIsTheTopOfTheDocument() {
        val xml = "<w:document><w:body>" + para("Title", "Release notes") +
            para("Heading1", "Changes") + "</w:body></w:document>"
        val lines = DocxOutline.outline(docx(xml))
        assertEquals(listOf(1, 1), lines.map { it.level })
        assertEquals("Release notes", lines.first().text)
    }

    @Test
    fun indentationShowsNesting() {
        val xml = "<w:document><w:body>" + para("Heading1", "A") + para("Heading2", "B") +
            "</w:body></w:document>"
        assertEquals("H1: A\n  H2: B", DocxOutline.render(docx(xml)))
    }

    @Test
    fun aBrokenFileIsEmptyRatherThanAnException() {
        assertTrue(DocxOutline.outline("not a zip".toByteArray()).isEmpty())
    }
}
