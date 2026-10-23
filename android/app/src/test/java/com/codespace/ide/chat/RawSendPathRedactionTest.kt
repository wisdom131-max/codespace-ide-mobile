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
 * CONSEQUENCE (the known open item, reported to the advisor 2026-10-23): a
 * NUL-free PDF attached as a file rides its RAW SOURCE to the model instead
 * of the extracted text. Real-world PDFs almost always contain NULs in
 * compressed streams and take the extraction lane properly; only hand-built
 * NUL-free fixtures like the gen.py text.pdf hit the raw ride. Minimal
 * proposed fix (NOT shipped in this commit — the advisor said report before
 * coding): verdictNeedsExtraction returns true for kind == PDF regardless of
 * snapshot binary-ness, so every PDF takes the extraction lane (redacted,
 * labeled UNTRUSTED, capped).
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

    @Test
    fun exactGenPdfBytesRideRawUnredacted() {
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
        // The shipped gate: DOCUMENT verdict + NON-binary snapshot => NO extraction
        // (extractedDoc stays null on the real attach path).
        assertFalse("shipped verdictNeedsExtraction semantic: this PDF never extracts", shippedGate(att))
        val block = ChatAttachmentInjector.buildBlock(listOf(att))
        // HEADER: the raw-file branch, never the extracted-document branch.
        assertTrue(block, block.contains("### File: text.pdf"))
        assertFalse(block, block.contains("### Document text extracted from"))
        // The RAW PDF SOURCE rides the send.
        assertTrue(block, block.contains("%PDF-1.4"))
        assertTrue(block, block.contains("1 0 obj"))
        // And the secret rides UNREDACTED — the raw branch has no redaction call.
        assertTrue(block, block.contains("sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12"))
        assertFalse(block, block.contains("[REDACTED]"))
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
