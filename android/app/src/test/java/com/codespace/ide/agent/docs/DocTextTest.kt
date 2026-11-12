package com.codespace.ide.agent.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** P2: a document arrives as text, or as an honest statement about why it cannot. */
class DocTextTest {

    @Test fun kindsAreRecognisedByExtension() {
        assertEquals(DocText.Kind.PDF, DocText.kindOf("report.PDF"))
        assertEquals(DocText.Kind.DOCX, DocText.kindOf("notes.docx"))
        assertEquals(DocText.Kind.XLSX, DocText.kindOf("sheet.xlsx"))
        assertEquals(DocText.Kind.PPTX, DocText.kindOf("deck.pptx"))
        assertEquals(DocText.Kind.PLAIN, DocText.kindOf("readme.md"))
        assertEquals(DocText.Kind.UNSUPPORTED, DocText.kindOf("photo.jpg"))
    }

    @Test fun plainTextPassesThrough() {
        assertEquals("hello", DocText.extract("a.txt", "hello".toByteArray()))
    }

    @Test fun anUnsupportedTypeIsNamedNotDecoded() {
        val out = DocText.extract("photo.jpg", byteArrayOf(1, 2, 3))
        assertTrue(out.contains("Not a readable document type"))
        assertTrue(out.contains("photo.jpg"))
    }

    @Test fun anEncryptedPdfIsRefusedWithTheReason() {
        val pdf = "%PDF-1.4\n1 0 obj << /Encrypt 5 0 R >> stream\nBT (secret) Tj ET\nendstream"
        val out = DocText.pdf(pdf.toByteArray(Charsets.ISO_8859_1))
        assertTrue(out.contains("encrypted"))
        assertFalse(out.contains("secret"))
    }

    @Test fun aPdfTextLayerIsExtracted() {
        val pdf = "%PDF-1.4\nstream\nBT /F1 12 Tf (Hello from the page) Tj ET\nendstream\n"
        val out = DocText.pdf(pdf.toByteArray(Charsets.ISO_8859_1))
        assertTrue(out.contains("Hello from the page"))
    }

    @Test fun anArrayOfStringsIsExtracted() {
        val pdf = "stream\nBT [(part one ) -20 (part two)] TJ ET\nendstream\n"
        assertTrue(DocText.pdf(pdf.toByteArray(Charsets.ISO_8859_1)).contains("part one"))
    }

    @Test fun aPdfWithNoTextLayerSaysSoInsteadOfReturningNothing() {
        val pdf = "%PDF-1.4\nstream\n0 0 m 100 100 l S\nendstream\n"
        val out = DocText.pdf(pdf.toByteArray(Charsets.ISO_8859_1))
        assertTrue(out.contains("No text layer"))
        assertTrue(out.contains("no OCR"))
    }

    @Test fun docxTextIsExtractedFromTheContainer() {
        val bytes = zipOf("word/document.xml",
            "<?xml version=\"1.0\"?><w:document><w:body><w:p><w:r><w:t>Quarterly </w:t></w:r>" +
                "<w:r><w:t>numbers</w:t></w:r></w:p><w:p><w:r><w:t>Second line</w:t></w:r></w:p>" +
                "</w:body></w:document>")
        val out = DocText.extract("memo.docx", bytes)
        assertTrue(out.contains("Quarterly numbers"))
        assertTrue(out.contains("Second line"))
        assertFalse(out.contains("<w:t>"))
    }

    @Test fun aDocxWithNoDocumentPartSaysSo() {
        val out = DocText.extract("memo.docx", zipOf("other.xml", "<x>nothing</x>"))
        assertTrue(out.contains("no readable text part"))
    }

    @Test fun aBrokenContainerIsReportedNotCrashed() {
        val out = DocText.extract("memo.docx", "not a zip at all".toByteArray())
        assertTrue(out.contains("Could not open") || out.contains("no readable text part"))
    }

    @Test fun markupIsStrippedAndParagraphsSurvive() {
        val text = DocText.stripXml("<w:p><w:r><w:t>one</w:t></w:r></w:p><w:p><w:r><w:t>two</w:t></w:r></w:p>")
        assertTrue(text.contains("one"))
        assertTrue(text.contains("two"))
        assertTrue(text.lines().size >= 2)
    }

    @Test fun oversizedInputIsRefusedByName() {
        val big = ByteArray(DocText.MAX_INPUT_BYTES + 10)
        val out = DocText.extract("huge.pdf", big)
        assertTrue(out.contains("Refused"))
    }

    @Test fun outputIsBounded() {
        val text = "x".repeat(50_000)
        assertTrue(DocText.extract("long.txt", text.toByteArray()).length <= DocText.MAX_OUTPUT_CHARS)
    }

    private fun zipOf(name: String, content: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
