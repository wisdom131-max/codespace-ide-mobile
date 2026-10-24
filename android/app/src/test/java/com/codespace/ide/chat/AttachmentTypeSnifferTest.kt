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

/**
 * A1 (advisor 2026-10-24): dedicated class for the language-source-text
 * decision table — the first pass appended these at top level after the
 * original class, where the class-private zip()/pdf() magic helpers were
 * out of scope (run 3329 RED). Self-contained here.
 */
class AttachmentTypeSnifferA1Test {
    private fun zip() = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00)
    private fun pdf() = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x37)
        private fun utf16LeBom(s: String): ByteArray {
            val b = ArrayList<Byte>()
            b.add(0xFF.toByte()); b.add(0xFE.toByte())
            for (ch in s) { b.add((ch.code and 0xFF).toByte()); b.add(((ch.code shr 8) and 0xFF).toByte()) }
            return b.toByteArray()
        }
        private fun utf16BeBom(s: String): ByteArray {
            val b = ArrayList<Byte>()
            b.add(0xFE.toByte()); b.add(0xFF.toByte())
            for (ch in s) { b.add(((ch.code shr 8) and 0xFF).toByte()); b.add((ch.code and 0xFF).toByte()) }
            return b.toByteArray()
        }
        private fun utf16NoBom(s: String): ByteArray {
            val b = ArrayList<Byte>()
            for (ch in s) { b.add((ch.code and 0xFF).toByte()); b.add(((ch.code shr 8) and 0xFF).toByte()) }
            return b.toByteArray()
        }

        @Test fun a1_java_utf16WithBom_isText() {
            assertEquals(AttachmentTypeKind.TEXT, AttachmentTypeSniffer.sniff("Main.java", utf16LeBom("package a;")).kind)
            assertEquals(AttachmentTypeKind.TEXT, AttachmentTypeSniffer.sniff("Main.java", utf16BeBom("package a;")).kind)
            assertEquals(AttachmentTypeKind.TEXT, AttachmentTypeSniffer.sniff("code.kt", utf16LeBom("fun a")).kind)
        }

        @Test fun a1_java_plainAscii_isText() {
            assertEquals(AttachmentTypeKind.TEXT, AttachmentTypeSniffer.sniff("Main.java", "package a;".toByteArray()).kind)
        }

        @Test fun a1_java_empty_isText() {
            assertEquals(AttachmentTypeKind.TEXT, AttachmentTypeSniffer.sniff("Main.java", ByteArray(0)).kind)
            assertEquals(AttachmentTypeKind.TEXT, AttachmentTypeSniffer.sniff("notes.xyz", ByteArray(0)).kind)
        }

        @Test fun a1_java_crlf_isText() {
            assertEquals(AttachmentTypeKind.TEXT,
                AttachmentTypeSniffer.sniff("Main.java", "package a;\r\n".toByteArray()).kind)
        }

        @Test fun a1_java_nonAsciiUtf8_isText() {
            assertEquals(AttachmentTypeKind.TEXT,
                AttachmentTypeSniffer.sniff("Main.java", "// ñ ü 準 package".toByteArray()).kind)
        }

        @Test fun a1_java_utf16NoBom_isTextByHeuristic() {
            assertEquals(AttachmentTypeKind.TEXT, AttachmentTypeSniffer.sniff("Main.java", utf16NoBom("package")).kind)
        }

        @Test fun a1_realBinaryRenamedJava_isHonestBinary() {
            // ELF header: NULs that do NOT match any UTF-16 pair pattern.
            val elf = byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01, 0x01, 0x00)
            assertEquals(AttachmentTypeKind.OTHER_BINARY, AttachmentTypeSniffer.sniff("Main.java", elf).kind)
            assertEquals(AttachmentExtraction.UNSUPPORTED, AttachmentTypeSniffer.sniff("Main.java", elf).extraction)
            // Random-ish NULs neither alternate nor BOM.
            val junk = byteArrayOf(0x01, 0x00, 0x02, 0x03, 0x00, 0x04, 0x00, 0x09)
            assertEquals(AttachmentTypeKind.OTHER_BINARY, AttachmentTypeSniffer.sniff("App.java", junk).kind)
        }

        @Test fun a1_zipMagicRenamedJava_staysZip() {
            assertEquals(AttachmentTypeKind.ZIP, AttachmentTypeSniffer.sniff("Main.java", zip()).kind)
            assertEquals(AttachmentTypeKind.PDF, AttachmentTypeSniffer.sniff("doc.java", pdf()).kind)
        }

        @Test fun a1_unknownExt_utf16Bom_isText() {
            assertEquals(AttachmentTypeKind.TEXT, AttachmentTypeSniffer.sniff("data.xyz", utf16LeBom("hello")).kind)
        }

        @Test fun a1_unknownExt_nuls_stayBinary() {
            val nuls = byteArrayOf(0x61, 0x62, 0x00, 0x63, 0x64, 0x00, 0x65, 0x00)
            assertEquals(AttachmentTypeKind.OTHER_BINARY, AttachmentTypeSniffer.sniff("file.xyz", nuls).kind)
        }
}
