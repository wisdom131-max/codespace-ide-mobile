package com.codespace.ide.agent.docs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PROJECT 2.6 (2026-11-15): a workbook can be CREATED, formulas included.
 *
 * The brief asks Excel support to create and edit formulas, not only read them. Producing a valid
 * .xlsx is a small package: content types, root rels, workbook, workbook rels, one part per sheet.
 * Strings are written inline rather than through a shared-string table, which keeps this writer to
 * one concern and is still a file Excel opens normally. Numbers are written as numbers, and a cell
 * whose text starts with "=" becomes a real FORMULA, with `fullCalcOnLoad` set so the workbook
 * calculates when it is opened instead of showing an empty cell until someone edits it.
 */
object XlsxWriter {

    data class Sheet(val name: String, val rows: List<List<String>>)

    /** "Sheet1,A,B\n1,2,3" into a single sheet, blank cells preserved. */
    fun sheetFromCsv(name: String, csv: String): Sheet {
        val rows = csv.lines()
            .filter { it.isNotBlank() }
            .map { line -> line.split(',').map { it.trim() } }
        return Sheet(name.ifBlank { "Sheet1" }, rows)
    }

    fun build(sheets: List<Sheet>): ByteArray {
        val safe = sheets.ifEmpty { listOf(Sheet("Sheet1", emptyList())) }.take(MAX_SHEETS)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes(safe.size))
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", workbook(safe))
            put("xl/_rels/workbook.xml.rels", workbookRels(safe.size))
            safe.forEachIndexed { i, sheet ->
                put("xl/worksheets/sheet" + (i + 1) + ".xml", worksheet(sheet))
            }
        }
        return out.toByteArray()
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun worksheet(sheet: Sheet): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        sheet.rows.forEachIndexed { r, row ->
            val rowNumber = r + 1
            val cells = StringBuilder()
            row.forEachIndexed { c, raw ->
                val text = raw.trim()
                if (text.isEmpty()) return@forEachIndexed
                val ref = columnName(c) + rowNumber
                cells.append(cell(ref, text))
            }
            if (cells.isNotEmpty()) {
                sb.append("<row r=\"").append(rowNumber).append("\">").append(cells).append("</row>")
            }
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun cell(ref: String, text: String): String {
        if (text.startsWith("=")) {
            val formula = text.substring(1).trim()
            if (formula.isEmpty()) return ""
            return "<c r=\"" + ref + "\"><f>" + escape(formula) + "</f></c>"
        }
        if (NUMBER.matches(text)) return "<c r=\"" + ref + "\"><v>" + text + "</v></c>"
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" +
            escape(text) + "</t></is></c>"
    }

    private fun columnName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun workbook(sheets: List<Sheet>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
        sb.append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        sb.append("<sheets>")
        sheets.forEachIndexed { i, sheet ->
            sb.append("<sheet name=\"").append(escape(nameOf(sheet.name, i)))
                .append("\" sheetId=\"").append(i + 1)
                .append("\" r:id=\"rId").append(i + 1).append("\"/>")
        }
        sb.append("</sheets>")
        // Without this a formula shows nothing until the first recalculation.
        sb.append("<calcPr fullCalcOnLoad=\"1\"/>")
        sb.append("</workbook>")
        return sb.toString()
    }

    /** Excel rejects these characters in a sheet name, and rejects a duplicate too. */
    private fun nameOf(name: String, index: Int): String {
        val cleaned = name.replace(Regex("[\\\\/*?\\[\\]:]"), " ").trim().take(31)
        return cleaned.ifBlank { "Sheet" + (index + 1) }
    }

    private fun workbookRels(count: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in 1..count) {
            sb.append("<Relationship Id=\"rId").append(i)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet")
                .append("\" Target=\"worksheets/sheet").append(i).append(".xml\"/>")
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun contentTypes(sheetCount: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        sb.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        for (i in 1..sheetCount) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        sb.append("</Types>")
        return sb.toString()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val MAX_SHEETS = 12

    private val NUMBER = Regex("^-?\\d+(\\.\\d+)?$")

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
}
