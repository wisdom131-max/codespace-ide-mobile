package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * C12 Stage 3 U4 (advisor GO 2026-10-20): decision-core tests for PDF
 * attachments. The REAL engines (pdfbox + Android PdfRenderer) are device-only
 * — pdfbox's font resources are Android ASSETS (PDFBoxResourceLoader needs a
 * Context), so the real extraction path is exercised with the on-device
 * fixture sheet (docs/c12-stage3-u4-test-sheet.md). Here the seams are faked
 * and every DECISION is pinned: caps, honest markers, encrypted/corrupted
 * refusals, scanned fallback + provider gate, OOM safety, redaction.
 */
class PdfAttachmentExtractorTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun pdfFile(bytes: String = "%PDF-1.4 stub"): File =
        tmp.newFile("doc.pdf").apply { writeText(bytes) }

    private fun engine(vararg pages: String, total: Int = pages.size): PdfAttachmentExtractor.PdfTextEngine =
        PdfAttachmentExtractor.PdfTextEngine { PdfAttachmentExtractor.EngineOutcome.Text(pages.toList(), total) }

    private val pngRenderer = PdfAttachmentExtractor.PageImageRenderer { _, _, _ ->
        byteArrayOf(0x89.toByte(), 'P'.toByte(), 'N'.toByte(), 'G'.toByte())
    }

    @Test fun textPdf_extractsAllPages() {
        val doc = PdfAttachmentExtractor.extract(
            pdfFile(), tmp.root, providerAcceptsImages = true,
            engine = engine("Hello alpha 42", "Second page beta"),
        )
        assertNull(doc.refusal)
        assertTrue(doc.text.contains("Hello alpha 42"))
        assertTrue(doc.text.contains("Second page beta"))
        assertTrue(doc.pageImages.isEmpty())
        assertFalse(doc.truncated)
    }

    @Test fun pageCap_honestShowingMarker() {
        val pages = List(50) { "page $it" }
        val doc = PdfAttachmentExtractor.extract(
            pdfFile(), tmp.root, engine = engine(*pages.toTypedArray(), total = 200),
        )
        assertNull(doc.refusal)
        assertTrue(doc.notes.any { it == "showing pages 1-50 of 200" })
        // only the parsed pages' text rides the send
        assertTrue(doc.text.contains("page 49"))
        assertFalse(doc.text.contains("page 199"))
    }

    @Test fun perPageCharCap_holdsAgainstOneHugePage() {
        val doc = PdfAttachmentExtractor.extract(
            pdfFile(), tmp.root, engine = engine("A".repeat(20_000), total = 1),
        )
        assertTrue(doc.text.length <= PdfAttachmentExtractor.MAX_CHARS_PER_PAGE)
        assertTrue(doc.notes.any { it.contains("page 1 text truncated") })
    }

    @Test fun snapshotCap_truncatesAndMarks() {
        val doc = PdfAttachmentExtractor.extract(
            pdfFile(), tmp.root,
            engine = engine(*(1..7).map { "B".repeat(PdfAttachmentExtractor.MAX_CHARS_PER_PAGE) }.toTypedArray()),
        )
        assertTrue(doc.text.length <= AttachmentIntake.MAX_EXTRACTED_SNAPSHOT_CHARS)
        assertTrue(doc.truncated)
        assertTrue(doc.notes.any { it.contains("truncated at the snapshot cap") })
    }

    @Test fun encryptedPdf_honestRefusalNeverJunk() {
        val doc = PdfAttachmentExtractor.extract(
            pdfFile(), tmp.root,
            engine = PdfAttachmentExtractor.PdfTextEngine { PdfAttachmentExtractor.EngineOutcome.Encrypted },
        )
        assertNull(doc.text)
        assertTrue(doc.refusal!!.contains("Encrypted or password-protected"))
        assertTrue(doc.refusal!!.contains("file info only"))
    }

    @Test fun corruptedPdf_honestRefusal() {
        val doc = PdfAttachmentExtractor.extract(
            pdfFile(), tmp.root,
            engine = PdfAttachmentExtractor.PdfTextEngine { PdfAttachmentExtractor.EngineOutcome.Corrupted("bad xref") },
        )
        assertTrue(doc.refusal!!.contains("Not a readable PDF"))
    }

    @Test fun secretsGoThroughAttachmentTierRedaction() {
        val doc = PdfAttachmentExtractor.extract(
            pdfFile(), tmp.root,
            engine = engine("key sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12 here"),
        )
        assertTrue(doc.text.contains("[REDACTED]"))
        assertTrue(doc.notes.any { it.contains("secrets redacted") })
    }

    @Test fun scannedPdf_providerRefusesImages_metaFallbackWithReason() {
        val doc = PdfAttachmentExtractor.extract(
            pdfFile(), tmp.root, providerAcceptsImages = false,
            engine = engine(" ", " ", total = 2),
        )
        assertTrue(doc.pageImages.isEmpty())
        assertTrue(doc.refusal!!.contains("scanned or image-only"))
        assertTrue(doc.refusal!!.contains("does not accept page images"))
    }

    @Test fun scannedPdf_providerAccepts_rendersUpToThreePages() {
        val doc = PdfAttachmentExtractor.extract(
            pdfFile(), tmp.root, providerAcceptsImages = true,
            engine = engine("", "", "", "", total = 5),
        )
        assertNull(doc.refusal)
        assertEquals("", doc.text)
        // cap: only the first 3 of 5 pages render
        assertEquals(3, doc.pageImages.size)
        for (p in doc.pageImages) assertTrue(File(p).isFile)
        assertTrue(doc.notes.any { it.contains("3 page image(s) rendered for the model") })
    }

    @Test fun scannedPdf_rendererOom_keepsAlreadyRenderedPages() {
        var calls = 0
        val oomRenderer = PdfAttachmentExtractor.PageImageRenderer { _, _, _ ->
            calls++
            if (calls == 1) byteArrayOf(1, 2, 3)
            else throw OutOfMemoryError("simulated page render oom")
        }
        val doc = PdfAttachmentExtractor.extract(
            pdfFile(), tmp.root, providerAcceptsImages = true,
            engine = engine("", "", "", total = 3),
            renderer = oomRenderer,
        )
        // no crash: partial result with the honest oom note
        assertEquals(1, doc.pageImages.size)
        assertTrue(doc.notes.any { it.contains("page rendering stopped early (out of memory)") })
    }

    @Test fun scannedPdf_allRendersFail_noEmptyPromise() {
        val nullRenderer = PdfAttachmentExtractor.PageImageRenderer { _, _, _ -> null }
        val doc = PdfAttachmentExtractor.extract(
            pdfFile(), tmp.root, providerAcceptsImages = true,
            engine = engine("", "", total = 2),
            renderer = nullRenderer,
        )
        assertTrue(doc.pageImages.isEmpty())
        assertTrue(doc.refusal!!.contains("no page could be rendered"))
    }
}
