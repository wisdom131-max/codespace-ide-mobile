package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * C12-U6-b fix 4 (advisor item 4, 2026-10-23): what does the REAL PDFBox
 * parser do with the EXACT truncated fixture (the first 60% of the delivered
 * gen.py text.pdf bytes)? Answer, locked in CI:
 *
 *  - A mid-file truncation does NOT throw. PDFBox 2.0.27's parser is LENIENT
 *    by default (org.apache.pdfbox.pdfparser.COSParser: `isLenient = true`).
 *    Our fixture is cut past the catalog/pages/page objects but before the
 *    xref/trailer/startxref, so COSParser.retrieveTrailer() finds no
 *    "startxref" and falls into rebuildTrailer() — a brute-force scan for
 *    "N M obj" headers (bfSearchForObjects) that rebuilds the cross-reference
 *    table from whatever objects survived the cut.
 *  - The document then LOADS (1 page) and PDFTextStripper returns EMPTY text
 *    (the content stream and the font object were cut). On the shipped app
 *    path that maps to EngineOutcome.Text([""], 1) -> finishText() ->
 *    blank-text == scanned-PDF classification -> renderScanned(): page
 *    images if the provider accepts them and PdfRenderer can render the cut
 *    file, else the honest refusal "Scanned PDF - no extractable text and no
 *    page could be rendered" (mapping covered by PdfAttachmentExtractorTest
 *    scannedPdf_*; renderer-null branch at its line 154). The model sees
 *    honest refusal prose under the UNTRUSTED extracted-content header —
 *    never raw bytes, never fabricated text. Wording gap (reported, no
 *    code): the refusal says "scanned or image-only PDF" for a file whose
 *    real problem is truncation — a future batch can distinguish the two.
 *  - In contrast, the delivered CORRUPTED fixture (valid header + junk body
 *    pointing /Root at object 9 which never exists) DOES throw: IOException
 *    "Page tree root must be a dictionary" -> EngineOutcome.Corrupted ->
 *    "Not a readable PDF (...) - showing file info only."
 *
 * ABOUT THE PARSER UNDER TEST: the app pins the Android port
 * com.tom-roush:pdfbox-android:2.0.27.0, whose classes live under
 * com.tom_roush.pdfbox.* and can only run on-device (font resources are
 * Android assets). The port is a fork of THIS upstream 2.0.27 with Android
 * font/rendering changes; the parsing classes tested here (COSParser,
 * PDDocument, PDFTextStripper) are the same code line. Different package
 * namespace -> no classpath collision with the app artifact.
 */
class PdfTruncatedFixtureUpstreamTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixtureBytes(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("u6b/$name")
            ?: throw AssertionError("fixture resource missing: u6b/$name")
        return stream.use { it.readBytes() }
    }

    private fun write(name: String, bytes: ByteArray): File {
        val f = tmp.newFile(name)
        f.writeBytes(bytes)
        return f
    }

    /** The delivered corrupted fixture from the U4 test sheet (fixture 6). */
    private fun corruptedFixtureBytes(): ByteArray {
        val head = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Root 9 0 R >>\n" +
            "garbage that never parses \u0000\u0001\u0002\n"
        return head.toByteArray(Charsets.ISO_8859_1) +
            "junk".repeat(100).toByteArray(Charsets.ISO_8859_1) +
            "\n%%EOF".toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun truncatedFixtureIsExactlySixtyPercentOfTheFullOne() {
        val full = fixtureBytes("text-fixture.pdf")
        val trunc = fixtureBytes("truncated-fixture.pdf")
        // The delivered sheet: data[: int(len(data) * 0.6)] -> 433 of 722 bytes.
        assertEquals(full.size, 722)
        assertEquals(433, trunc.size)
        assertTrue(trunc.contentEquals(full.copyOf(433)))
    }

    @Test
    fun realParserExtractsAllThreeLinesFromTheFullFixture() {
        val f = write("text.pdf", fixtureBytes("text-fixture.pdf"))
        val doc = org.apache.pdfbox.pdmodel.PDDocument.load(f)
        try {
            assertEquals(1, doc.numberOfPages)
            val text = org.apache.pdfbox.text.PDFTextStripper().getText(doc).trim()
            assertTrue(text, text.contains("Hello from the C12 U4 fixture."))
            assertTrue(text, text.contains("Second line has 42 tokens."))
            // Upstream extracts the secret; OUR extractor then redacts it at the
            // attachment tier (see RawSendPathRedactionTest.extractedBranchRedactsByContrast
            // and PdfAttachmentExtractor redaction tests).
            assertTrue(text, text.contains("sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12"))
        } finally {
            doc.close()
        }
    }

    @Test
    fun realParserLoadsTheTruncatedFixtureSilentlyWithBlankText() {
        val f = write("truncated.pdf", fixtureBytes("truncated-fixture.pdf"))
        // THE core finding: no exception. The lenient parser rebuilds the xref
        // from the surviving objects (COSParser.rebuildTrailer ->
        // bfSearchForObjects) and hands back a 1-page document.
        val doc = org.apache.pdfbox.pdmodel.PDDocument.load(
            f, org.apache.pdfbox.io.MemoryUsageSetting.setupMainMemoryOnly())
        try {
            assertEquals(1, doc.numberOfPages)
            val text = org.apache.pdfbox.text.PDFTextStripper().getText(doc)
            // The content stream and font were cut -> zero glyphs, no throw.
            assertTrue("truncated fixture must yield blank text, got: $text", text.isBlank())
        } finally {
            doc.close()
        }
    }

    @Test
    fun realParserThrowsOnTheCorruptedFixture() {
        val f = write("corrupted.pdf", corruptedFixtureBytes())
        try {
            val doc = org.apache.pdfbox.pdmodel.PDDocument.load(f)
            doc.close()
            throw AssertionError("corrupted fixture must fail to load")
        } catch (e: java.io.IOException) {
            // The /Root points at object 9 which never exists -> page tree
            // lookup dies BEFORE any lenient recovery can rebuild it.
            val m = e.message ?: ""
            assertTrue("unexpected IOException: $m", m.contains("Page tree root must be a dictionary"))
        }
    }
}
