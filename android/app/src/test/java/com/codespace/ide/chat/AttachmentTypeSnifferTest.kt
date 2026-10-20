package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C12 Stage 3 U1 (advisor GO 2026-10-20): decision-table tests for the type
 * sniffer + capability disclosure. Pure JVM; no device needed.
 */
class AttachmentTypeSnifferTest {

    private fun zip() = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00)
    private fun png() = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    private fun pdf() = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x37)
    private fun gzip() = byteArrayOf(0x1f.toByte(), 0x8b.toByte(), 0x08, 0x00, 0x00, 0x00, 0x00, 0x00)

    @Test fun zipMagic_keepsOfficeAndArchiveFamilies() {
        assertEquals(AttachmentTypeKind.DOCX, AttachmentTypeSniffer.sniff("report.docx", zip()).kind)
        assertEquals(AttachmentTypeKind.XLSX, AttachmentTypeSniffer.sniff("budget.xlsx", zip()).kind)
        assertEquals(AttachmentTypeKind.ZIP, AttachmentTypeSniffer.sniff("bundle.zip", zip()).kind)
        assertEquals(AttachmentTypeKind.ZIP, AttachmentTypeSniffer.sniff("unknown.bin", zip()).kind)
        assertEquals(AttachmentTypeKind.ZIP, AttachmentTypeSniffer.sniff("noextension", zip()).kind)
    }

    @Test fun magicOverridesExtension() {
        // A "docx" that is really a PNG is an image — never junk extraction.
        assertEquals(AttachmentTypeKind.IMAGE, AttachmentTypeSniffer.sniff("fake.docx", png()).kind)
        // A "png" that is really a zip follows the zip family.
        assertEquals(AttachmentTypeKind.ZIP, AttachmentTypeSniffer.sniff("fake.png", zip()).kind)
        assertEquals(AttachmentTypeKind.PDF, AttachmentTypeSniffer.sniff("document.pdf", pdf()).kind)
        assertEquals(AttachmentTypeKind.GZIP, AttachmentTypeSniffer.sniff("backup.gz", gzip()).kind)
    }

    @Test fun extensionOnly_noMagic() {
        assertEquals(AttachmentTypeKind.HTML, AttachmentTypeSniffer.sniff("page.html", null).kind)
        assertEquals(AttachmentTypeKind.CSV, AttachmentTypeSniffer.sniff("data.csv", null).kind)
        assertEquals(AttachmentTypeKind.RTF, AttachmentTypeSniffer.sniff("doc.rtf", null).kind)
        assertEquals(AttachmentTypeKind.DOCM, AttachmentTypeSniffer.sniff("macro.docm", zip()).kind)
        assertEquals(AttachmentTypeKind.XLSM, AttachmentTypeSniffer.sniff("macro.xlsm", zip()).kind)
        assertEquals(AttachmentTypeKind.ODT, AttachmentTypeSniffer.sniff("text.odt", zip()).kind)
        assertEquals(AttachmentTypeKind.ODS, AttachmentTypeSniffer.sniff("sheet.ods", zip()).kind)
        assertEquals(AttachmentTypeKind.ODP, AttachmentTypeSniffer.sniff("slides.odp", zip()).kind)
    }

    @Test fun unknownExtension_textyMagic_isText() {
        val texty = "hello".toByteArray()
        assertEquals(AttachmentTypeKind.TEXT, AttachmentTypeSniffer.sniff("notes.xyz", texty).kind)
        assertEquals(AttachmentExtraction.DOCUMENT, AttachmentTypeSniffer.sniff("notes.xyz", texty).extraction)
    }

    @Test fun nulInMagic_isBinary() {
        val bin = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        assertEquals(AttachmentTypeKind.OTHER_BINARY, AttachmentTypeSniffer.sniff("data.dat", bin).kind)
        assertEquals(AttachmentExtraction.UNSUPPORTED, AttachmentTypeSniffer.sniff("data.dat", bin).extraction)
    }

    @Test fun legacyOffice_isHonestUnsupported_neverJunk() {
        for (n in listOf("old.doc", "book.xls", "deck.ppt")) {
            val v = AttachmentTypeSniffer.sniff(n, null)
            assertEquals(AttachmentExtraction.UNSUPPORTED, v.extraction)
            assertTrue(v.reason.contains("not supported"))
        }
    }

    @Test fun laterPhases_areLabeledNotExtracted() {
        // U4 (2026-10-20): PDF moved to the DOCUMENT path; image/audio/video
        // stay later-phase (U5).
        for (n in listOf("photo.png", "song.mp3", "clip.mp4")) {
            val v = AttachmentTypeSniffer.sniff(n, null)
            assertEquals(AttachmentExtraction.LATER_PHASE, v.extraction)
        }
    }

    @Test fun pdf_isNowExtracted() {
        // U4: text PDFs extract; scanned PDFs fall back to page images when
        // the provider accepts them (see PdfAttachmentExtractorTest).
        val v = AttachmentTypeSniffer.sniff("paper.pdf", "%PDF-1.4".toByteArray())
        assertEquals(AttachmentExtraction.DOCUMENT, v.extraction)
        assertEquals(AttachmentTypeKind.PDF, v.kind)
    }

    @Test fun macroFormats_labeledNeverExecuted() {
        val v = AttachmentTypeSniffer.sniff("macro.docm", zip())
        assertTrue(v.reason.contains("macros are NEVER executed"))
        assertEquals(AttachmentExtraction.DOCUMENT, v.extraction)
    }

    @Test fun capabilityLine_isLabelPlusReason() {
        val v = AttachmentTypeSniffer.sniff("report.docx", zip())
        assertEquals("Word document — Document text will be extracted and sent to the model",
            AttachmentTypeSniffer.capabilityLine(v))
    }
}
