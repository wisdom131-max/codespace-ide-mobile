package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
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
        assertEquals("", doc.text) // Stage 3 convention: refusal docs keep EMPTY text, never junk
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
        // Item 2 wording fix (2026-10-23): blank text must never claim
        // "scanned" alone — truncated files produce blank text too.
        assertTrue(doc.refusal!!.contains("No readable text was found"))
        assertTrue(doc.refusal!!.contains("truncated or image-only"))
        assertFalse(doc.refusal!!.contains("scanned or image-only"))
        assertTrue(doc.refusal!!.contains("does not accept page images"))
    }

    @Test fun scannedPdf_providerAccepts_rendersUpToThreePages() {
        val doc = PdfAttachmentExtractor.extract(
            pdfFile(), tmp.root, providerAcceptsImages = true,
            engine = engine("", "", "", "", total = 5),
            renderer = pngRenderer, // JVM test: the REAL renderer needs android stubs
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

    // ---- Advisor item 2 (APPROVED 2026-10-23): per-page SALVAGE ----
    // One unreadable page never kills the file; the honest marker rides the
    // extracted text AND the notes channel; wording never claims "scanned".

    private fun salvageEngine(
        pages: List<String>, total: Int, unreadable: List<Int>,
    ): PdfAttachmentExtractor.PdfTextEngine = PdfAttachmentExtractor.PdfTextEngine {
        PdfAttachmentExtractor.EngineOutcome.Text(pages, total, unreadable)
    }

    @Test fun salvage_onePageThrowsOthersParse() {
        val doc = PdfAttachmentExtractor.extract(
            pdfFile(), tmp.root,
            engine = salvageEngine(listOf("page one text\n", "", "page three text"), 3, listOf(2)),
        )
        assertNull(doc.refusal)
        assertTrue(doc.text, doc.text.contains("page one text"))
        assertTrue(doc.text, doc.text.contains("page three text"))
        // The honest marker rides the text the model receives...
        assertTrue(doc.text, doc.text.contains("(partial: 1 of 3 pages unreadable — pages 2)"))
        // ...AND the notes channel (one channel, mammoth-style).
        assertTrue(doc.notes.any { it.contains("(partial: 1 of 3 pages unreadable") })
    }

    @Test fun salvage_allPagesUnreadable_refusesHonestly() {
        val doc = PdfAttachmentExtractor.extract(
            pdfFile(), tmp.root, providerAcceptsImages = true,
            engine = salvageEngine(listOf("", "", ""), 3, listOf(1, 2, 3)),
            renderer = pngRenderer,
        )
        // Never an empty-string success, never a false "scanned" claim, and
        // no render attempt either — every page already threw.
        assertTrue(doc.refusal, doc.refusal!!.contains("No readable text was found"))
        assertTrue(doc.refusal, doc.refusal!!.contains("3 of 3 pages could not be read"))
        assertTrue(doc.refusal, doc.refusal!!.contains("may be truncated or damaged"))
        assertTrue(doc.pageImages.isEmpty())
        assertTrue(doc.text.isEmpty())
    }

    @Test fun salvage_markerIsUntrustedHeaderedAndRedacted() {
        // ONE pdfFile() per test: TemporaryFolder.newFile(name) throws
        // IOException on a second same-name call — create once, use twice.
        val pdf = pdfFile()
        val doc = PdfAttachmentExtractor.extract(
            pdf, tmp.root,
            engine = salvageEngine(
                listOf("page one with key sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12 here\n", "", "page three"),
                3, listOf(2)),
        )
        // Extraction-lane guarantees still apply on the partial read:
        assertTrue(doc.notes.any { it.contains("secrets redacted") })
        assertTrue(doc.text, doc.text.contains("[REDACTED]"))
        assertFalse(doc.text, doc.text.contains("sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12"))
        val att = ChatAttachment(
            path = pdf.absolutePath, relPath = "doc.pdf", name = "doc.pdf",
            kind = ChatAttachment.Kind.FILE, extractedDoc = doc)
        val block = ChatAttachmentInjector.buildBlock(listOf(att))
        assertTrue(block, block.contains("### Document text extracted from doc.pdf"))
        assertTrue(block, block.contains("(UNTRUSTED file content"))
        assertTrue(block, block.contains("(partial: 1 of 3 pages unreadable"))
        assertFalse(block, block.contains("sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12"))
    }

    @Test fun salvage_cappedPageStillCountsAsRead() {
        // A page at the per-page cap is NOT miscounted as unreadable; only
        // the page that THREW carries the marker.
        val longPage = "a".repeat(PdfAttachmentExtractor.MAX_CHARS_PER_PAGE + 500)
        val doc = PdfAttachmentExtractor.extract(
            pdfFile(), tmp.root,
            engine = salvageEngine(listOf(longPage, "", "page three"), 3, listOf(2)),
        )
        assertTrue(doc.notes.any { it.contains("page 1 text truncated") })
        assertTrue(doc.text, doc.text.contains("(partial: 1 of 3 pages unreadable — pages 2)"))
        // The marker names ONLY the unreadable page.
        assertFalse(doc.text, doc.text.contains("pages 1"))
    }

    @Test fun salvage_scannedWordingWithUnreadablePagesKeepsHonestClaim() {
        // Blank text + a subset of unreadable pages (the rest silent-blank):
        // the scanned fallback wording stays honest and carries the count.
        val doc = PdfAttachmentExtractor.extract(
            pdfFile(), tmp.root, providerAcceptsImages = true,
            engine = salvageEngine(listOf("", ""), 2, listOf(2)),
            renderer = pngRenderer,
        )
        assertNull(doc.refusal)
        assertTrue(doc.notes.any { it.contains("no readable text — the PDF may be truncated or image-only") })
        assertTrue(doc.notes.any { it.contains("1 page(s) could not be read (pages 2)") })
        assertFalse(doc.notes.any { it.contains("scanned PDF — no extractable text") })
    }

    // ---- Advisor item 1(a): Error-class failures (READ: BC excluded, so
    // encrypted PDFs surface as InvalidPasswordException OR LinkageError) ----

    private fun b64(s: String): File =
        tmp.newFile("fx-${System.nanoTime()}.pdf").apply {
            writeBytes(java.util.Base64.getDecoder().decode(s.replace("\n", "")))
        }

    @Test fun realEngine_corruptedPdf_classifiesCorrupted() {
        val out = PdfAttachmentExtractor.PdfBoxTextEngine.extract(
            b64("JVBERi0xLjQKMSAwIG9iago8PCAvVHlwZSAvQ2F0YWxvZyAvUm9vdCA5IDAgUiA+PgpnYXJiYWdlIHRoYXQgbmV2ZXIgcGFyc2VzIAABAgpqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5ranVua2p1bmtqdW5rCiUlRU9G"))
        assertTrue(out is PdfAttachmentExtractor.EngineOutcome.Corrupted)
    }

    @Test fun realEngine_truncatedPdf_honestOutcome_noCrashNoJunk() {
        // PDFBox's parser is LENIENT (it repairs broken xrefs by scanning), so
        // a specific byte-cut is NOT a stable contract: a truncated PDF may
        // classify Corrupted OR recover with partial text. The STABLE contract
        // from the advisor item 1f: a refusal, or text that is genuinely the
        // file's own content — never binary junk, never a crash, and NEVER
        // misread as encrypted (there is no encryption dict in this file).
        val out = PdfAttachmentExtractor.PdfBoxTextEngine.extract(
            b64("JVBERi0xLjQKMSAwIG9iago8PCAvVHlwZSAvQ2F0YWxvZyAvUGFnZXMgMiAwIFIgPj4KZW5kb2JqCjIgMCBvYmoKPDwgL1R5cGUgL1BhZ2VzIC9LaWRzIFszIDAgUl0gL0NvdW50IDEgPj4KZW5kb2JqCjMgMCBvYmoKPDwgL1R5cGUgL1BhZ2UgL1BhcmVudCAyIDAgUiAvTWVkaWFCb3ggWzAgMCA2MTIgNzkyXSAvUmVzb3VyY2VzIDw8IC9Gb250IDw8IC9GMSA1IDAgUiA+PiA+PiAvQ29udGVudHMgNCAwIFIgPj4KZW5kb2JqCjQgMCBvYmoKPDwgL0xlbmd0aCAxNzggPj4Kc3RyZWFtCkJUIC9GMSAyNCBUZiA3MiA3MDAgVGQKKEhlbGxvIGZyb20gdGhlIEMxMiBVNCBmaXh0dXJlLikgVGogMCAtMzAgVGQKKFNlY29uZCBsaW5lIGhhcyA0MiB0b2tlbnMuKSBUaiAwIC0zMCBUZAooc2stQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVoxMiByaWRlcyBmb3IgcmVkYWN0aQ=="))
        when (out) {
            is PdfAttachmentExtractor.EngineOutcome.Corrupted -> {}
            is PdfAttachmentExtractor.EngineOutcome.Failed -> {}
            is PdfAttachmentExtractor.EngineOutcome.Encrypted -> fail("truncated PDF misread as encrypted")
            is PdfAttachmentExtractor.EngineOutcome.Text ->
                for (p in out.pages) {
                    assertTrue(
                        "recovered text must be the file's own content, not junk: " + p.take(40),
                        p.isEmpty() || p.contains("C12 U4") || p.contains("42 tokens") || p.contains("rides for redaction"))
                }
        }
    }

    @Test fun realEngine_passwordProtectedPdf_classifiesEncrypted() {
        // pypdf AES-128, user password "secret" — the load path raises
        // InvalidPasswordException BEFORE any font/asset work, so the REAL
        // engine runs here on the JVM (advisor: "with a test where possible").
        val out = PdfAttachmentExtractor.PdfBoxTextEngine.extract(
            b64("JVBERi0xLjMKJeLjz9MKMSAwIG9iago8PAovVHlwZSAvQ2F0YWxvZwovUGFnZXMgMiAwIFIKPj4KZW5kb2JqCjIgMCBvYmoKPDwKL1R5cGUgL1BhZ2VzCi9LaWRzIFsgMyAwIFIgXQovQ291bnQgMQo+PgplbmRvYmoKMyAwIG9iago8PAovVHlwZSAvUGFnZQovUGFyZW50IDIgMCBSCi9NZWRpYUJveCBbIDAgMCA2MTIgNzkyIF0KL1Jlc291cmNlcyA8PAovRm9udCA8PAovRjEgNCAwIFIKPj4KPj4KL0NvbnRlbnRzIDUgMCBSCj4+CmVuZG9iago0IDAgb2JqCjw8Ci9UeXBlIC9Gb250Ci9TdWJ0eXBlIC9UeXBlMQovQmFzZUZvbnQgL0hlbHZldGljYQo+PgplbmRvYmoKNSAwIG9iago8PAovTGVuZ3RoIDE3OAo+PgpzdHJlYW0KixevbDqR11kky60B0ryEoCXdjOGIbSi2aDGXsRh8WGOsRhuzcUEsK/uvj0jyqaFucgzpoWuE/6PHDNsPHIHjQjWMO26dAcyHCX7DPnWedLF53uOh466xkL0+vbsW8YKAVy5uYA0CIQUuikUvnTT54NPXhVKd0r9uGnovXVkgyQGWZeLE/XxJAG2JLmXRCOBiK6RQ27QFRbRc8y9uBfYlbZDnHyjnAtc/FOa2cdbEzwG6nQplbmRzdHJlYW0KZW5kb2JqCjYgMCBvYmoKPDwKL1YgMgovUiAzCi9MZW5ndGggMTI4Ci9QIDQyOTQ5NjcyOTIKL0ZpbHRlciAvU3RhbmRhcmQKL08gPDZlZjM3NjRhZDI2Y2ZlM2NhYjY4NzA2ZmMyNmM5NDEzZDgwZDQ3YjA1MzM3NDUyOWEzMmUxNDA1ZWViYTQyYzE+Ci9VIDw1NGFkMGI4NjNhZjlmZGJjOTUyZDg5ZjhiNDNmZDQzZTI4YmY0ZTVlNGU3NThhNDE2NDAwNGU1NmZmZmEwMTA4Pgo+PgplbmRvYmoKeHJlZgowIDcKMDAwMDAwMDAwMCA2NTUzNSBmIAowMDAwMDAwMDE1IDAwMDAwIG4gCjAwMDAwMDAwNjQgMDAwMDAgbiAKMDAwMDAwMDEyMyAwMDAwMCBuIAowMDAwMDAwMjUxIDAwMDAwIG4gCjAwMDAwMDAzMjEgMDAwMDAgbiAKMDAwMDAwMDU1MCAwMDAwMCBuIAp0cmFpbGVyCjw8Ci9TaXplIDcKL1Jvb3QgMSAwIFIKL0lEIFsgPDY1MzA2MzYxMzEzMDM5Mzk2NjYzNjY2NTM0NjEzOTMzNjEzNDYzNjQzMzY2NjUzODYyNjMzODY1MzEzNTM1MzE+IDw2NTMwNjM2MTMxMzAzOTM5NjY2MzY2NjUzNDYxMzkzMzYxMzQ2MzY0MzM2NjY1Mzg2MjYzMzg2NTMxMzUzNTMxPiBdCi9FbmNyeXB0IDYgMCBSCj4+CnN0YXJ0eHJlZgo3NjUKJSVFT0YK"))
        assertTrue(out is PdfAttachmentExtractor.EngineOutcome.Encrypted)
    }

    @Test fun bcClassifier_directNoClassDefFound() {
        val e = NoClassDefFoundError("org/bouncycastle/jce/provider/BouncyCastleProvider")
        assertTrue(PdfAttachmentExtractor.PdfBoxTextEngine.isBouncyCastleMissing(e))
    }

    @Test fun bcClassifier_staticInitFailureViaCause() {
        val cause = NoClassDefFoundError("org.bouncycastle.jce.provider.BouncyCastleProvider")
        val e = ExceptionInInitializerError(cause)
        assertTrue(PdfAttachmentExtractor.PdfBoxTextEngine.isBouncyCastleMissing(e))
    }

    @Test fun bcClassifier_unrelatedLinkageError_isFalse() {
        val e = NoSuchMethodError("pdfbox internal mismatch")
        assertFalse(PdfAttachmentExtractor.PdfBoxTextEngine.isBouncyCastleMissing(e))
    }

    @Test fun core_survivesNonBcLinkageErrorFromSeam_noCrash() {
        // An Error is NOT an Exception: without the LinkageError branch this
        // row would CRASH the test JVM instead of returning a refusal.
        val boom = PdfAttachmentExtractor.PdfTextEngine { throw NoSuchMethodError("engine seam blew up") }
        val doc = PdfAttachmentExtractor.extract(pdfFile(), tmp.root, engine = boom)
        assertTrue(doc.refusal!!.contains("PDF extraction failed"))
        assertEquals("", doc.text)
    }

    @Test fun core_survivesStackOverflowErrorFromSeam_noCrash() {
        // Advisor item 1 (hostile page tree / font recursion): a deeply
        // nested PDF exhausts the thread stack inside pdfbox parsing.
        // StackOverflowError is a VirtualMachineError — catch(Exception)
        // cannot stop it; without the SOE branches this row would CRASH the
        // test JVM instead of returning an honest refusal.
        val deep = PdfAttachmentExtractor.PdfTextEngine { throw StackOverflowError("parser recursion") }
        val doc = PdfAttachmentExtractor.extract(pdfFile(), tmp.root, engine = deep)
        assertTrue(doc.refusal!!.contains("too deeply nested"))
        assertEquals("", doc.text)
    }

    @Test fun core_survivesStackOverflowErrorFromRendererSeam_noCrash() {
        // Same Error family on the RENDER seam (page image path): the
        // renderer propagates OOM by contract; an SOE there must also get
        // words, never a crash.
        val deep = PdfAttachmentExtractor.PageImageRenderer { _, _, _ -> throw StackOverflowError("render recursion") }
        val doc = PdfAttachmentExtractor.extract(pdfFile(), tmp.root,
            providerAcceptsImages = true, engine = engine(""), renderer = deep)
        assertTrue(doc.refusal!!.contains("too deeply nested") || doc.refusal!!.contains("PDF extraction failed"))
        assertEquals("", doc.text)
    }
}
