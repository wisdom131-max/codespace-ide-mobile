package com.codespace.ide.agent.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 2.6: a written workbook must read back as a workbook.
 *
 * The round trip is the whole test: formulas, numbers and text go in, and our own reader must show
 * the same cells at the same references with formulas intact. Anything less and Excel would open a
 * file that only looks right.
 */
class XlsxWriterTest {

    @Test
    fun cellsComeBackAtTheirReferences() {
        val bytes = XlsxWriter.build(
            listOf(XlsxWriter.sheetFromCsv("Data", "name,qty\nWidget,4")),
        )
        val rows = XlsxWorkbook.read(bytes).single().rows
        assertEquals(listOf("A1", "B1"), rows[0].map { it.ref })
        assertEquals("name", rows[0][0].value)
        assertEquals("4", rows[1][1].value)
    }

    @Test
    fun numbersAreNumbersAndTextIsText() {
        val bytes = XlsxWriter.build(listOf(XlsxWriter.Sheet("S", listOf(listOf("12", "word")))))
        val cells = XlsxWorkbook.read(bytes).single().rows.single()
        assertTrue("a numeric cell is a value cell", !cells[0].value.contains("word"))
        assertEquals("12", cells[0].value)
        assertEquals("word", cells[1].value)
    }

    @Test
    fun aCellStartingWithEqualsBecomesAFormula() {
        val bytes = XlsxWriter.build(
            listOf(XlsxWriter.Sheet("S", listOf(listOf("1", "2", "=SUM(A1:B1)")))),
        )
        val sheet = XlsxWorkbook.read(bytes).single()
        assertEquals(1, sheet.formulas())
        assertEquals("SUM(A1:B1)", sheet.rows.single()[2].formula)
    }

    @Test
    fun severalSheetsKeepTheirNamesAndOrder() {
        val bytes = XlsxWriter.build(
            listOf(
                XlsxWriter.sheetFromCsv("Summary", "a\n1"),
                XlsxWriter.sheetFromCsv("Detail", "b\n2"),
            ),
        )
        assertEquals(listOf("Summary", "Detail"), XlsxWorkbook.read(bytes).map { it.name })
    }

    @Test
    fun blankCellsAreSkippedRatherThanWrittenEmpty() {
        val bytes = XlsxWriter.build(listOf(XlsxWriter.sheetFromCsv("S", "a,,c")))
        assertEquals(listOf("A1", "C1"), XlsxWorkbook.read(bytes).single().rows.single().map { it.ref })
    }

    @Test
    fun aSheetNameExcelWouldRejectIsCleanedNotDropped() {
        val bytes = XlsxWriter.build(listOf(XlsxWriter.Sheet("a/b:c", listOf(listOf("x")))))
        val name = XlsxWorkbook.read(bytes).single().name
        assertTrue("name must lose the illegal characters", !name.contains("/") && !name.contains(":"))
        assertTrue(name.isNotBlank())
    }

    @Test
    fun columnsPastZAreNamedProperly() {
        val wide = (1..28).map { "v" + it }
        val bytes = XlsxWriter.build(listOf(XlsxWriter.Sheet("S", listOf(wide))))
        val refs = XlsxWorkbook.read(bytes).single().rows.single().map { it.ref }
        // 26 columns land on Z, so the 27th is AA and the 28th AB.
        assertEquals("AA1", refs[26])
        assertEquals("AB1", refs.last())
    }

    @Test
    fun markupIsEscaped() {
        val bytes = XlsxWriter.build(listOf(XlsxWriter.Sheet("S", listOf(listOf("a < b & c")))))
        assertEquals("a < b & c", XlsxWorkbook.read(bytes).single().rows.single().single().value)
    }
}
