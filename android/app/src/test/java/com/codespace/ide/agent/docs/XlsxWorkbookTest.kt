package com.codespace.ide.agent.docs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 2.2: the prompt says do not treat XLSX as a ZIP of text.
 *
 * The three things the old reader could not do are the three tests that matter: name the sheets,
 * place a value at its cell reference, and report a formula rather than only its result.
 */
class XlsxWorkbookTest {

    private fun xlsx(
        sheets: List<Pair<String, String>>,
        shared: String? = null,
        sheetNames: String = "",
        rels: String = "",
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(xml.toByteArray()); zip.closeEntry()
            }
            put("xl/workbook.xml", "<workbook><sheets>$sheetNames</sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", "<Relationships>$rels</Relationships>")
            if (shared != null) put("xl/sharedStrings.xml", "<sst>$shared</sst>")
            sheets.forEach { (path, xml) -> put("xl/worksheets/$path", "<worksheet><sheetData>$xml</sheetData></worksheet>") }
        }
        return out.toByteArray()
    }

    @Test
    fun sheetsAreNamedAndOrderedAsTheWorkbookDeclares() {
        val bytes = xlsx(
            sheets = listOf(
                "sheet1.xml" to "<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>",
                "sheet2.xml" to "<row r=\"1\"><c r=\"A1\"><v>2</v></c></row>",
            ),
            sheetNames = "<sheet name=\"Summary\" sheetId=\"1\" r:id=\"rId1\"/>" +
                "<sheet name=\"Data\" sheetId=\"2\" r:id=\"rId2\"/>",
            rels = "<Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/>" +
                "<Relationship Id=\"rId2\" Target=\"worksheets/sheet2.xml\"/>",
        )
        assertEquals(listOf("Summary", "Data"), XlsxWorkbook.read(bytes).map { it.name })
    }

    @Test
    fun sharedStringsResolveAndCellsKeepTheirReference() {
        val bytes = xlsx(
            sheets = listOf(
                "sheet1.xml" to "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>0</v></c>" +
                    "<c r=\"C2\"><v>42</v></c></row>",
            ),
            shared = "<si><t>Revenue</t></si>",
            sheetNames = "<sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/>",
            rels = "<Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/>",
        )
        val cells = XlsxWorkbook.read(bytes).single().rows.single()
        assertEquals(listOf("A2", "C2"), cells.map { it.ref })
        assertEquals("Revenue", cells[0].value)
        assertEquals("42", cells[1].value)
        assertNull(cells[1].formula)
    }

    @Test
    fun aFormulaIsReportedBesideItsResult() {
        val bytes = xlsx(
            sheets = listOf(
                "sheet1.xml" to "<row r=\"3\"><c r=\"A3\"><v>6</v></c>" +
                    "<c r=\"B3\"><f>SUM(A1:A3)</f><v>6</v></c></row>",
            ),
            sheetNames = "<sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/>",
            rels = "<Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/>",
        )
        val sheet = XlsxWorkbook.read(bytes).single()
        assertEquals(1, sheet.formulas())
        assertEquals("SUM(A1:A3)", sheet.rows.single()[1].formula)
        val rendered = XlsxWorkbook.render(bytes)
        assertTrue(rendered.contains("B3 6 =SUM(A1:A3)"))
        assertTrue(rendered.contains("1 formula(s)"))
    }

    @Test
    fun inlineStringsAreRead() {
        val bytes = xlsx(
            sheets = listOf(
                "sheet1.xml" to "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Inline text</t></is></c></row>",
            ),
            sheetNames = "<sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/>",
            rels = "<Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/>",
        )
        assertEquals("Inline text", XlsxWorkbook.read(bytes).single().rows.single().single().value)
    }

    @Test
    fun rowOrderFollowsTheRowNumberNotTheFile() {
        val bytes = xlsx(
            sheets = listOf(
                "sheet1.xml" to "<row r=\"2\"><c r=\"A2\"><v>second</v></c></row>" +
                    "<row r=\"1\"><c r=\"A1\"><v>first</v></c></row>",
            ),
            sheetNames = "<sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/>",
            rels = "<Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/>",
        )
        val rows = XlsxWorkbook.read(bytes).single().rows
        assertEquals("first", rows[0][0].value)
        assertEquals("second", rows[1][0].value)
    }

    @Test
    fun anUnreadableFileIsEmptyRatherThanAnException() {
        assertTrue(XlsxWorkbook.read("nonsense".toByteArray()).isEmpty())
        assertEquals("", XlsxWorkbook.render("nonsense".toByteArray()))
    }
}
