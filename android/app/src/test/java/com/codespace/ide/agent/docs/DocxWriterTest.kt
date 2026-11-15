package com.codespace.ide.agent.docs

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 2.4: a created document must be a document, not a text file with .docx on the end.
 *
 * The test that decides this is the round trip: what the writer produced must be readable by the
 * reader in this same app, structure included. If the outline does not come back, the headings were
 * only bold text and Word would agree.
 */
class DocxWriterTest {

    private fun entries(bytes: ByteArray): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                map[e.name] = z.readBytes().toString(Charsets.UTF_8)
                e = z.nextEntry
            }
        }
        return map
    }

    @Test
    fun theFourPartsWordNeedsArePresent() {
        val names = entries(DocxWriter.build("T", emptyList())).keys
        assertTrue(names.contains("[Content_Types].xml"))
        assertTrue(names.contains("_rels/.rels"))
        assertTrue(names.contains("word/document.xml"))
        assertTrue(names.contains("word/styles.xml"))
    }

    @Test
    fun headingsSurviveTheRoundTripThroughOurOwnReader() {
        val bytes = DocxWriter.build(
            title = "Report",
            blocks = DocxWriter.blocksOf("# Summary\nAll green.\n## Details\n- one\n- two"),
        )
        val outline = DocxOutline.outline(bytes)
        // The document title is its own top-level heading, then the content's own levels.
        assertEquals(listOf(1, 1, 2), outline.map { it.level })
        assertEquals(listOf("Report", "Summary", "Details"), outline.map { it.text })
    }

    @Test
    fun withoutATitleTheContentStartsTheOutline() {
        val bytes = DocxWriter.build(
            title = "",
            blocks = DocxWriter.blocksOf("# Summary\n## Details"),
        )
        val outline = DocxOutline.outline(bytes)
        assertEquals(listOf(1, 2), outline.map { it.level })
        assertEquals(listOf("Summary", "Details"), outline.map { it.text })
    }

    @Test
    fun theTitleIsTheTopOfTheDocument() {
        val outline = DocxOutline.outline(DocxWriter.build("Release notes", emptyList()))
        assertEquals("Release notes", outline.single().text)
        assertEquals(1, outline.single().level)
    }

    @Test
    fun bodyTextIsWrittenNotDropped() {
        val xml = entries(DocxWriter.build("", DocxWriter.blocksOf("plain words here"))).getValue("word/document.xml")
        assertTrue(xml.contains("plain words here"))
        assertTrue("a document with no headings has no outline", DocxOutline.outline(DocxWriter.build("", DocxWriter.blocksOf("plain words here"))).isEmpty())
    }

    @Test
    fun bulletsAreMarkedAndNotMistakenForHeadings() {
        val blocks = DocxWriter.blocksOf("- first\n- second")
        assertTrue(blocks.all { it is DocxWriter.Block.Bullet })
        val xml = entries(DocxWriter.build("", blocks)).getValue("word/document.xml")
        assertTrue(xml.contains("ListParagraph"))
    }

    @Test
    fun markupCharactersAreEscaped() {
        val xml = entries(DocxWriter.build("", DocxWriter.blocksOf("a < b & c > d"))).getValue("word/document.xml")
        assertTrue(xml.contains("a &lt; b &amp; c &gt; d"))
    }
}
