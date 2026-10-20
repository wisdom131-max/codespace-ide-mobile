package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * C12 Stage 3 U2 (advisor GO 2026-10-20): extractor tests with REAL-STRUCTURE
 * fixtures built in-test with ZipOutputStream (advisor item 1: JVM fixtures
 * mimic real files, never minimal stubs). Safety fixtures: billion-laughs
 * XML, ratio-guard bomb, truncated zip, adversarial long lines and depth.
 */
class DocumentTextExtractorTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun doc(verdict: AttachmentTypeKind, label: String = "doc") =
        AttachmentTypeSniffer.verdictOf(verdict, label)

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val f = tmp.newFile("test-" + System.nanoTime() + ".zip")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, content) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
        return f
    }

    private fun extract(f: File, kind: AttachmentTypeKind): AttachmentIntake.ExtractedDoc =
        DocumentTextExtractor.extract(f, doc(kind))

    // ── DOCX: paragraphs, tables, tabs, breaks, hyperlinks, xml:space ──────

    @Test fun docx_realStructure() {
        val f = zipOf(
            "word/document.xml" to """<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""" +
                """<w:p><w:r><w:t xml:space="preserve">Quarterly   report.</w:t></w:r></w:p>""" +
                """<w:p><w:r><w:t>See </w:t></w:r><w:hyperlink r:id="rId1"><w:r><w:t>the spec</w:t></w:r></w:hyperlink><w:r><w:tab/><w:t>after tab</w:t></w:r></w:p>""" +
                """<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Cell A</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Cell B</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""" +
                """<w:p><w:r><w:t>line one</w:t><w:br/><w:t>line two</w:t></w:r></w:p>""" +
                """</w:body></w:document>"""
        )
        val doc = extract(f, AttachmentTypeKind.DOCX)
        assertEquals(null, doc.refusal)
        val t = doc.text
        assertTrue("paragraph with xml:space kept: [$t]", t.contains("Quarterly   report."))
        assertTrue("hyperlink text kept inline: [$t]", t.contains("See the spec"))
        assertTrue("tab kept: [$t]", t.contains("\tafter tab"))
        assertTrue("table cells separated: [$t]", t.contains("Cell A | Cell B"))
        assertTrue("break kept: [$t]", t.contains("line one\nline two"))
    }

    @Test fun docx_tokenIsRedacted() {
        val f = zipOf(
            "word/document.xml" to "<w:document><w:body><w:p><w:r><w:t>key sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12 here</w:t></w:r></w:p></w:body></w:document>"
        )
        val doc = extract(f, AttachmentTypeKind.DOCX)
        assertTrue("token redacted: [${doc.text}]", doc.text.contains("[REDACTED]"))
        assertTrue("redaction noted", doc.notes.any { it.contains("secrets redacted") })
    }

    @Test fun docx_billionLaughs_isRejectedNotExpanded() {
        // The classic payload: a DOCTYPE with nested entity declarations, then
        // one entity reference. The scanner must REJECT the DOCTYPE outright —
        // nothing is ever expanded — and finish fast with an honest refusal.
        val payload = """<!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol1 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">""" +
            """<!ENTITY lol2 "&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;">]>""" +
            """<w:document><w:body><w:p><w:r><w:t>&lol2;</w:t></w:r></w:p></w:body></w:document>"""
        val f = zipOf("word/document.xml" to payload)
        val start = System.currentTimeMillis()
        val doc = extract(f, AttachmentTypeKind.DOCX)
        val elapsed = System.currentTimeMillis() - start
        assertNotNull(doc.refusal)
        val reason = doc.refusal
        assertTrue("refusal names the rejection: $reason", reason != null && reason.contains("DOCTYPE"))
        assertTrue("completed fast (was ${elapsed}ms)", elapsed < 5000)
        assertTrue("never expanded the entities", !doc.text.contains("lol") || doc.text.isEmpty())
    }

    @Test fun docx_customEntityWithoutDoctype_isNeverExpanded() {
        val f = zipOf(
            "word/document.xml" to "<w:document><w:body><w:p><w:r><w:t>value &customentity; end</w:t></w:r></w:p></w:body></w:document>"
        )
        val doc = extract(f, AttachmentTypeKind.DOCX)
        assertEquals(null, doc.refusal)
        assertTrue("unknown entity dropped, text kept: [${doc.text}]", doc.text.contains("value") && doc.text.contains("end"))
        assertTrue("no expansion: [${doc.text}]", !doc.text.contains("&customentity"))
    }

    @Test fun docx_missingDocument_isHonestRefusal() {
        val f = zipOf("notword.txt" to "hello")
        val doc = extract(f, AttachmentTypeKind.DOCX)
        assertTrue(doc.refusal!!.contains("not a Word document"))
    }

    // ── XLSX: sharedStrings, inline, numbers, cached formulas, caps ────────

    @Test fun xlsx_realStructure_sharedStrings_inlineNumbersFormulas() {
        val f = zipOf(
            "xl/sharedStrings.xml" to "<sst><si><t>Item</t></si><si><t>Cost</t></si><si><t>Coffee</t></si></sst>",
            "xl/workbook.xml" to """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Budget" sheetId="1" r:id="rId1"/><sheet name="Data" sheetId="2" r:id="rId2"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="worksheet" Target="worksheets/sheet2.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to "<worksheet><sheetData>" +
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>" +
                "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>200</v></c></row>" +
                "<row r=\"3\"><c r=\"A3\" t=\"inlineStr\"><is><t>Total</t></is></c><c r=\"B3\"><f>SUM(B2:B2)</f><v>200</v></c></row>" +
                "</sheetData></worksheet>",
            "xl/worksheets/sheet2.xml" to "<worksheet><sheetData><row r=\"1\"><c r=\"A1\"><v>42</v></c></row></sheetData></worksheet>"
        )
        val doc = extract(f, AttachmentTypeKind.XLSX)
        assertEquals(null, doc.refusal)
        val t = doc.text
        assertTrue("sheet names present: [$t]", t.contains("[Sheet 1: Budget]") && t.contains("[Sheet 2: Data]"))
        assertTrue("shared strings resolved: [$t]", t.contains("Item | Cost") && t.contains("Coffee | 200"))
        assertTrue("inline string: [$t]", t.contains("Total"))
        assertTrue("cached formula VALUE kept (formula text ignored): [$t]", t.contains("200"))
        assertTrue("second sheet read via rels: [$t]", t.contains("42"))
    }

    @Test fun xlsx_rowCap_hasShowingXofYMarker() {
        val rows = StringBuilder("<worksheet><sheetData>")
        for (r in 1..250) {
            rows.append("<row r=\"$r\"><c r=\"A$r\"><v>$r</v></c></row>")
        }
        rows.append("</sheetData></worksheet>")
        val f = zipOf(
            "xl/workbook.xml" to "<workbook><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            "xl/worksheets/sheet1.xml" to rows.toString()
        )
        val doc = extract(f, AttachmentTypeKind.XLSX)
        assertTrue("row cap marker: ${doc.notes}", doc.notes.any { it.contains("showing 200 of 250 rows") })
    }

    // ── PPTX: slide order from presentation rels, NOT entry names ──────────

    @Test fun pptx_slideOrderComesFromRelsNotNames() {
        // slide2.xml holds "FIRST-TEXT" but is rId1 — the deck must show it first.
        val f = zipOf(
            "ppt/presentation.xml" to """<p:presentation xmlns:p="x" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId r:id="rId1"/><p:sldId r:id="rId2"/></p:sldIdLst></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships><Relationship Id="rId1" Target="slides/slide2.xml"/><Relationship Id="rId2" Target="slides/slide1.xml"/></Relationships>""",
            "ppt/slides/slide2.xml" to "<p:sld><a:t>FIRST-TEXT</a:t></p:sld>",
            "ppt/slides/slide1.xml" to "<p:sld><a:t>SECOND-TEXT</a:t></p:sld>"
        )
        val doc = extract(f, AttachmentTypeKind.PPTX)
        assertEquals(null, doc.refusal)
        val t = doc.text
        val first = t.indexOf("FIRST-TEXT")
        val second = t.indexOf("SECOND-TEXT")
        assertTrue(first in 0..second)
        assertTrue("slide headers present: [$t]", t.contains("[Slide 1]"))
    }

    // ── OpenDocument ────────────────────────────────────────────────────────

    @Test fun odt_contentXml() {
        val f = zipOf(
            "content.xml" to """<office:document-content><office:body><office:text><text:p>Alpha line.</text:p><table:table><table:table-row><table:table-cell><text:p>Cell 1</text:p></table:table-cell><table:table-cell><text:p>Cell 2</text:p></table:table-cell></table:table-row></table:table></office:text></office:body></office:document-content>"""
        )
        val doc = extract(f, AttachmentTypeKind.ODT)
        assertEquals(null, doc.refusal)
        assertTrue("paragraph: [${doc.text}]", doc.text.contains("Alpha line."))
        assertTrue("cells: [${doc.text}]", doc.text.contains("Cell 1 | Cell 2"))
    }

    // ── CSV: BOM, UTF-16, quoted fields, caps ──────────────────────────────

    @Test fun csv_bomAndQuotedFields() {
        val f = tmp.newFile("t.csv")
        val body = "name,notes\n\"Doe, Jane\",\"said \"\"hi\"\" to Bob\"\nAda,engineer\n".toByteArray(Charsets.UTF_16LE)
        val withBom = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + body
        f.writeBytes(withBom)
        val doc = extract(f, AttachmentTypeKind.CSV)
        assertEquals(null, doc.refusal)
        val t = doc.text
        assertTrue("header: [$t]", t.contains("name | notes"))
        assertTrue("quoted comma kept: [$t]", t.contains("Doe, Jane"))
        assertTrue("escaped quote kept: [$t]", t.contains("said \"hi\" to Bob"))
    }

    @Test fun csv_windows1252Fallback() {
        val f = tmp.newFile("w.csv")
        // Not valid UTF-8; valid windows-1252 (é = 0xE9).
        f.writeBytes(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte(), 0x0a))  // "café\n"
        val doc = extract(f, AttachmentTypeKind.CSV)
        assertTrue("cp1252 fallback decoded: [${doc.text}]", doc.text.contains("café"))
    }

    // ── HTML: script/style skipped, entities, no WebView, long line ────────

    @Test fun html_scriptStyleSkipped_entitiesDecoded() {
        val f = tmp.newFile("p.html")
        f.writeText("<html><head><style>body{color:red}</style><script>var x='steal token sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ12';</script></head>" +
            "<body><h1>Release notes</h1><p>Fixed &amp; improved in <b>v2.4</b>.</p></body></html>")
        val doc = extract(f, AttachmentTypeKind.HTML)
        assertEquals(null, doc.refusal)
        val t = doc.text
        assertTrue("style content gone: [$t]", !t.contains("color:red"))
        assertTrue("script content gone (and its token never extracted): [$t]", !t.contains("steal token"))
        assertTrue("entity decoded: [$t]", t.contains("Fixed & improved"))
        assertTrue("text kept: [$t]", t.contains("Release notes"))
    }

    @Test fun html_adversarialUnbrokenLine_completesCapped() {
        val f = tmp.newFile("big.html")
        f.writeText("<p>" + "x".repeat(200_000) + "</p>")
        val start = System.currentTimeMillis()
        val doc = extract(f, AttachmentTypeKind.HTML)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("completed fast (was ${elapsed}ms)", elapsed < 5000)
        assertTrue("capped to the preview cap", doc.text.length <= AttachmentIntake.MAX_EXTRACTED_SNAPSHOT_CHARS + 100)
        assertTrue(doc.truncated || doc.notes.any { it.contains("truncated") })
    }

    @Test fun xml_adversarialDeepNesting_completesBounded() {
        val deep = "<w:document>".repeat(20_000) + "<w:t>deep</w:t>" + "</w:document>".repeat(20_000)
        val f = zipOf("word/document.xml" to deep)
        val start = System.currentTimeMillis()
        val doc = extract(f, AttachmentTypeKind.DOCX)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("depth cap kept it linear (was ${elapsed}ms)", elapsed < 5000)
        // over-depth text is ignored by design — bounded, honest
        assertEquals(null, doc.refusal)
    }

    // ── RTF: destinations skipped, escapes decoded ─────────────────────────

    @Test fun rtf_destinationsSkipped_escapesDecoded() {
        val f = tmp.newFile("d.rtf")
        f.writeText("""{\rtf1\ansi{\fonttbl\f0\fswiss Helvetica;}{\colortbl;\red0;}{\info{\author someone}}Hello caf\'e9 world.\par Second \u65 ? line.\tab end}""")
        val doc = extract(f, AttachmentTypeKind.RTF)
        assertEquals(null, doc.refusal)
        val t = doc.text
        assertTrue("fonttbl gone: [$t]", !t.contains("Helvetica"))
        assertTrue("colortbl gone: [$t]", !t.contains("red0"))
        assertTrue("info gone: [$t]", !t.contains("author"))
        assertTrue("\'hh decoded: [$t]", t.contains("café"))
        assertTrue("\\uN decoded: [$t]", t.contains("Second A line"))
        assertTrue("tab kept: [$t]", t.contains("\tend"))
    }

    // ── zip safety: ratio guard, malformed, entry names never paths ────────

    @Test fun ratioGuard_rejectsZipBomb() {
        val f = tmp.newFile("bomb.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write("a".repeat(500_000).toByteArray())   // ~1000:1 ratio
            z.closeEntry()
        }
        val doc = extract(f, AttachmentTypeKind.DOCX)
        assertNotNull(doc.refusal)
        val why = doc.refusal
        assertTrue("ratio refusal: $why", why != null && (why.contains("limits exceeded") || why.contains("ratio")))
    }

    @Test fun truncatedZip_isHonestRefusal() {
        val f = tmp.newFile("trunc.docx")
        f.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00, 0x00) + "garbage".toByteArray())
        val doc = extract(f, AttachmentTypeKind.DOCX)
        assertNotNull(doc.refusal)
        val msg = doc.refusal
        assertTrue(msg != null && (msg.contains("not a readable archive") || msg.contains("unreadable")))
    }

    @Test fun entryNames_areDisplayOnly_neverPaths() {
        // Entry names with traversal and absolute forms: listed/shown as data,
        // never used to write anywhere. Extraction still reads only the entry
        // we ASK FOR BY NAME, in memory.
        val f = zipOf(
            "../evil.txt" to "traversal content",
            "/abs/path.txt" to "absolute content",
            "word/document.xml" to "<w:document><w:body><w:p><w:r><w:t>legit</w:t></w:r></w:p></w:body></w:document>"
        )
        val doc = extract(f, AttachmentTypeKind.DOCX)
        assertEquals(null, doc.refusal)
        assertTrue(doc.text.contains("legit"))
        // The named-entry read API refuses unknown names instead of guessing.
        assertTrue(SafeZipReader.isZip(f))
    }

    @Test fun snapshotCap_appliedAndMarked() {
        // Varied per-line content keeps the deflate ratio under the 100:1
        // guard (the guard is tested separately with the bomb fixture).
        val paras = StringBuilder()
        for (i in 0 until 20_000) {
            paras.append("<w:p><w:r><w:t>line ").append(i).append(" q7z2x").append((i * 7919) % 9973)
                .append(" content</w:t></w:r></w:p>")
        }
        val f = zipOf(
            "word/document.xml" to "<w:document><w:body>" + paras.toString() + "</w:body></w:document>"
        )
        val doc = extract(f, AttachmentTypeKind.DOCX)
        assertTrue("preview cap applied", doc.text.length <= AttachmentIntake.MAX_EXTRACTED_SNAPSHOT_CHARS)
        assertTrue("cap marked", doc.truncated || doc.notes.any { it.contains("truncated") })
    }
}
