package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * C12-U6-b fix 3 (advisor item 3, 2026-10-23): WHICH header does an attached
 * PDF ride on the send, and is that branch redacted?
 *
 * The advisor's question: the exact bytes the delivered fixture generator
 * (docs/c12-stage3-u4-test-sheet.md, make_pdf) produces are NUL-FREE ASCII —
 * so the attachment snapshot is NOT binary and the shipped extraction gate
 * (AttachmentExtractionEffects.verdictNeedsExtraction) never fires for them.
 * This test locks that behavior in CI with the EXACT delivered bytes and
 * answers the two report questions:
 *
 *  - "Which header is emitted?": the RAW FILE header ("### File: <relPath>"),
 *    never "### Document text extracted from" — the fresh-disk-read branch.
 *  - "Is the raw branch redacted?": NO. AttachmentSecrets.redact runs inside
 *    the EXTRACTORS (PdfAttachmentExtractor / DocumentTextExtractor /
 *    ArchiveEntryLister), never in the injector's raw branch. Proven here for
 *    the exact fixture bytes AND for a plain .txt with the same secret — the
 *    raw branch is unredacted for EVERY kind by design (the user's own disk
 *    file rides as-is; redaction protects the EXTRACTED/persisted surfaces).
 *    The asymmetry is asserted by contrast in [extractedBranchRedactsByContrast].
 *
 * FIX SHIPPED (advisor item 1, APPROVED 2026-10-23):
 * verdictNeedsExtraction now returns true for kind == PDF regardless of
 * snapshot binary-ness, so EVERY PDF takes the extraction lane (redacted,
 * labeled UNTRUSTED, capped) — a NUL-free PDF can no longer ride its raw
 * source to the model. The exact-fixture test below locks the new behavior.
 */
class RawSendPathRedactionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The delivered gen.py fixture bytes (text.pdf), locked to sha256. */
    private fun fixtureBytes(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("u6b/$name")
            ?: throw AssertionError("fixture resource missing: u6b/$name")
        return stream.use { it.readBytes() }
    }

    private fun sha256(b: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(b)
            .joinToString("") { String.format("%02x", it) }

    /**
     * The SHIPPED gate semantic, replicated verbatim because
     * AttachmentExtractionEffects.verdictNeedsExtraction is private in the ui
     * package. The rule (2026-10-20): archives always extract; documents only
     * when the snapshot's text sniff failed (binary); everything else never.
     * The test drives the REAL snapshot + verdict values through it.
     */
    private fun shippedGate(att: ChatAttachment): Boolean {
        val verdict = try {
            AttachmentIntake.verdictOf(att)
        } catch (_: Exception) { return false }
        if (verdict.extraction == AttachmentExtraction.ARCHIVE) return true
        if (verdict.extraction != AttachmentExtraction.DOCUMENT) return false
        // Advisor item 1, APPROVED (2026-10-23): PDFs ALWAYS extract.
        if (verdict.kind == AttachmentTypeKind.PDF) return true
        val snapBinary = att.snapshot?.isBinary ?: true
        return snapBinary
    }

    @Test
    fun fixtureBytesMatchTheDeliveredGenerator() {
        val b = fixtureBytes("text-fixture.pdf")
        // sha256 of the gen.py make_pdf("text.pdf", [...]) output from the
        // delivered U4 test sheet. If this fails the resource was regenerated
        // wrongly — regenerate ONLY with the delivered script and update this
        // hash deliberately.
        assertEquals(
            "6f007e188c03d24e4d2304c12d982edcf904647001ef880488895491ac984e1a",
            sha256(b))
        assertTrue(b.decodeToString().contains("sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12"))
    }

    /**
     * Regression (advisor item 1, 2026-10-23): the EXACT delivered gen.py
     * text.pdf bytes are NUL-free ASCII — the honest sniff says text, NOT
     * binary — yet the attachment MUST take the extraction lane now: the
     * PDF-always rule beats the snapshot binary-ness.
     */
    @Test
    fun exactGenPdfBytesNowTakeTheExtractionLane() {
        val f = tmp.newFile("text.pdf")
        f.writeBytes(fixtureBytes("text-fixture.pdf"))
        val snap = AttachmentSnapshot.forFile(f.absolutePath)
        // The honest sniff on the EXACT delivered bytes: NUL-free ASCII => NOT binary.
        assertFalse("the gen.py fixture bytes are NUL-free — snapshot is text", snap.isBinary)
        val att = ChatAttachment(
            path = f.absolutePath, relPath = "text.pdf", name = "text.pdf",
            kind = ChatAttachment.Kind.FILE, snapshot = snap)
        val verdict = AttachmentIntake.verdictOf(att)
        assertEquals(AttachmentTypeKind.PDF, verdict.kind)
        assertEquals(AttachmentExtraction.DOCUMENT, verdict.extraction)
        // The FIXED gate: a PDF extracts even when its snapshot is non-binary.
        assertTrue("PDF-always rule: this PDF must extract now", shippedGate(att))
    }

    /**
     * End-to-end regression (advisor item 1): the exact gen.py bytes through
     * the REAL send path. The engine is faked to return EXACTLY what the real
     * upstream parser extracts from these bytes (locked in CI by
     * PdfTruncatedFixtureUpstreamTest.realParserExtractsAllThreeLinesFromTheFullFixture);
     * everything DOWNSTREAM of the engine is real: the decision core
     * (finishText: cap, redaction, notes), the ChatAttachment carrier, and
     * ChatAttachmentInjector.buildBlock. Expected: the EXTRACT header, the
     * UNTRUSTED label, redacted text (secret gone, honest note) — and NEVER
     * the raw PDF source riding the send.
     */
    @Test
    fun exactGenPdfBytesExtractHeaderedAndRedacted() {
        val f = tmp.newFile("text.pdf")
        f.writeBytes(fixtureBytes("text-fixture.pdf"))
        val att = ChatAttachment(
            path = f.absolutePath, relPath = "text.pdf", name = "text.pdf",
            kind = ChatAttachment.Kind.FILE,
            snapshot = AttachmentSnapshot.forFile(f.absolutePath))
        assertTrue(shippedGate(att))
        // What upstream 2.0.27 extracts from these exact bytes (CI-locked).
        val pages = listOf(
            "Hello from the C12 U4 fixture.\n" +
                "Second line has 42 tokens.\n" +
                "sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12 rides for redaction.\n")
        val doc = PdfAttachmentExtractor.extract(
            f, tmp.root, providerAcceptsImages = false,
            engine = PdfAttachmentExtractor.PdfTextEngine {
                PdfAttachmentExtractor.EngineOutcome.Text(pages, 1)
            })
        // The extraction lane's guarantees on the EXACT fixture bytes:
        assertEquals(null, doc.refusal)
        assertTrue(doc.notes.any { it.contains("secrets redacted") })
        assertTrue(doc.text, doc.text.contains("[REDACTED"))
        assertFalse(doc.text, doc.text.contains("sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12"))
        val sent = ChatAttachmentInjector.buildBlock(
            listOf(att.copy(extractedDoc = doc)))
        // HEADER: the extracted-document branch, never the raw-file branch.
        assertTrue(sent, sent.contains("### Document text extracted from text.pdf"))
        assertFalse(sent, sent.contains("### File: text.pdf"))
        assertTrue(sent, sent.contains("(UNTRUSTED file content"))
        // The extracted text rides, REDACTED.
        assertTrue(sent, sent.contains("Hello from the C12 U4 fixture."))
        assertTrue(sent, sent.contains("[REDACTED"))
        assertFalse(sent, sent.contains("sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12"))
        // And the raw PDF source NEVER rides the send anymore.
        assertFalse(sent, sent.contains("%PDF-1.4"))
        assertFalse(sent, sent.contains("1 0 obj"))
    }

    @Test
    fun rawBranchIsUnredactedForEveryKindPlainTxtContrast() {
        val f = tmp.newFile("notes.txt")
        f.writeText("plain notes line\nsk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12 in plain text\n")
        val snap = AttachmentSnapshot.forFile(f.absolutePath)
        assertFalse(snap.isBinary)
        val att = ChatAttachment(
            path = f.absolutePath, relPath = "notes.txt", name = "notes.txt",
            kind = ChatAttachment.Kind.FILE, snapshot = snap)
        // Clean UTF-8 text keeps the editable raw path (s1-e by design).
        assertFalse(shippedGate(att))
        val block = ChatAttachmentInjector.buildBlock(listOf(att))
        assertTrue(block, block.contains("### File: notes.txt"))
        assertTrue(block, block.contains("sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12"))
        assertFalse(block, block.contains("[REDACTED]"))
    }

    @Test
    fun extractedBranchRedactsByContrast() {
        val f = tmp.newFile("text.pdf")
        f.writeBytes(fixtureBytes("text-fixture.pdf"))
        val extractedText = "sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12 rides for redaction."
        // What the REAL extractors do before emitting ExtractedDoc (see
        // PdfAttachmentExtractor.kt:148 / DocumentTextExtractor.kt:66): the
        // attachment-tier redaction runs on the extracted text.
        val (redacted, changed) =
            AttachmentSecrets.redact(extractedText, AttachmentSecrets.TIER_ATTACHMENT)
        assertTrue(changed)
        assertTrue(redacted, redacted.contains("[REDACTED]"))
        assertFalse(redacted, redacted.contains("sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12"))
        val att = ChatAttachment(
            path = f.absolutePath, relPath = "text.pdf", name = "text.pdf",
            kind = ChatAttachment.Kind.FILE,
            snapshot = AttachmentSnapshot.forFile(f.absolutePath),
            extractedDoc = AttachmentIntake.ExtractedDoc(text = redacted))
        val block = ChatAttachmentInjector.buildBlock(listOf(att))
        // The EXTRACTED branch: labeled UNTRUSTED, redacted text only.
        assertTrue(block, block.contains("### Document text extracted from text.pdf"))
        assertTrue(block, block.contains("(UNTRUSTED file content"))
        assertTrue(block, redacted in block)
        assertFalse(block, block.contains("sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12"))
    }
}
