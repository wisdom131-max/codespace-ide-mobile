package com.codespace.ide.agent.docs

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * PROJECT 2.2 (2026-11-15): an Excel workbook is cells, not text.
 *
 * The old path read `xl/worksheets/sheet1.xml` through the generic tag stripper. The prompt is
 * explicit that XLSX must not be treated as a ZIP of text, and the old path could not answer the
 * questions that matter: which worksheets exist, what is in column C of row 40, and which cells are
 * FORMULAS rather than their results. A formula is the thing a spreadsheet has that text cannot
 * express, so it is read from `f` and reported beside the value it produces.
 *
 * Bounded on purpose: a phone must not build a huge matrix to answer a question.
 */
object XlsxWorkbook {

    /** One cell. [formula] is the raw `f` text without a leading "=", null for a plain value. */
    data class Cell(val ref: String, val value: String, val formula: String?)

    data class Sheet(val name: String, val rows: List<List<Cell>>) {
        fun columns(): Int = rows.maxOfOrNull { it.size } ?: 0
        fun formulas(): Int = rows.sumOf { row -> row.count { it.formula != null } }
    }

    fun read(bytes: ByteArray, maxSheets: Int = 6, maxRows: Int = 200): List<Sheet> {
        val entries = entriesOf(bytes) ?: return emptyList()
        val shared = sharedStrings(entries["xl/sharedStrings.xml"])
        val rels = sheetTargets(entries["xl/_rels/workbook.xml.rels"])
        val named = sheetNames(entries["xl/workbook.xml"])
        val out = ArrayList<Sheet>()
        for ((id, name) in named) {
            if (out.size >= maxSheets) break
            val target = rels[id] ?: continue
            val path = if (target.startsWith("xl/")) target else "xl/" + target.trimStart('/')
            val xml = entries[path] ?: continue
            out.add(Sheet(name = name, rows = rowsOf(xml, shared, maxRows)))
        }
        // A workbook whose relationship list is unreadable still has worksheets on disk; take them
        // in file order rather than reporting an empty workbook.
        if (out.isEmpty()) {
            entries.keys
                .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                .sorted()
                .take(maxSheets)
                .forEach { path ->
                    out.add(
                        Sheet(
                            path.substringAfterLast('/').removeSuffix(".xml"),
                            rowsOf(entries[path]!!, shared, maxRows),
                        ),
                    )
                }
        }
        return out
    }

    /** Rendered as a small grid, formulas shown beside their values. */
    fun render(bytes: ByteArray, maxSheets: Int = 6, maxRows: Int = 200): String {
        val sheets = read(bytes, maxSheets, maxRows)
        if (sheets.isEmpty()) return ""
        val sb = StringBuilder()
        for (sheet in sheets) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("[Sheet] ").append(sheet.name)
                .append(" — ").append(sheet.rows.size).append(" row(s), ")
                .append(sheet.columns()).append(" column(s)")
            val f = sheet.formulas()
            if (f > 0) sb.append(", ").append(f).append(" formula(s)")
            for (row in sheet.rows) {
                sb.append("\n")
                sb.append(
                    row.joinToString(" | ") { c ->
                        if (c.formula != null) c.ref + " " + c.value + " =" + c.formula
                        else c.ref + " " + c.value
                    },
                )
            }
        }
        return sb.toString()
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun rowsOf(xml: String, shared: List<String>, maxRows: Int): List<List<Cell>> {
        val byRow = sortedMapOf<Int, MutableList<Cell>>()
        for (m in CELL.findAll(xml)) {
            val ref = m.groupValues[1]
            val attrs = m.value.substringBefore('>')
            val inner = m.groupValues[2]
            val row = ref.dropWhile { !it.isDigit() }.toIntOrNull() ?: continue
            if (row > maxRows) continue
            val type = TYPE.find(attrs)?.groupValues?.get(1) ?: ""
            val formula = FORMULA.find(inner)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            val rawValue = VALUE.find(inner)?.groupValues?.get(1) ?: ""
            val text = when {
                type == "s" -> rawValue.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                type == "inlineStr" -> INLINE.find(inner)?.groupValues?.get(1) ?: ""
                else -> rawValue
            }.let { unescape(it).trim() }
            if (text.isEmpty() && formula == null) continue
            byRow.getOrPut(row) { ArrayList() }.add(Cell(ref, text, formula))
        }
        return byRow.map { (_, cells) -> cells.sortedBy { columnIndex(it.ref) } }
    }

    private fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break
        }
        return n
    }

    private fun sharedStrings(xml: String?): List<String> {
        if (xml == null) return emptyList()
        return SI.findAll(xml).map { m ->
            unescape(TEXT.findAll(m.value).joinToString("") { it.groupValues[1] })
        }.toList()
    }

    /** rId -> worksheet path, from the workbook's relationships. */
    private fun sheetTargets(xml: String?): Map<String, String> {
        if (xml == null) return emptyMap()
        return REL.findAll(xml).mapNotNull { m ->
            val id = m.groupValues[1]
            val target = m.groupValues[2]
            if (id.isBlank() || target.isBlank()) null else id to target
        }.toMap()
    }

    /** (rId, sheet name) in the order the workbook declares them. */
    private fun sheetNames(xml: String?): List<Pair<String, String>> {
        if (xml == null) return emptyList()
        return SHEET.findAll(xml).mapNotNull { m ->
            val name = m.groupValues[1]
            val id = m.groupValues[2]
            if (name.isBlank()) null else id to unescape(name)
        }.toList()
    }

    private fun entriesOf(bytes: ByteArray): Map<String, String>? = try {
        val map = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            var total = 0L
            while (e != null) {
                if (e.name in WANTED || e.name.startsWith("xl/worksheets/")) {
                    val data = zip.readBytes()
                    total += data.size
                    if (total <= MAX_UNZIPPED_BYTES) map[e.name] = data.toString(Charsets.UTF_8)
                }
                e = zip.nextEntry
            }
        }
        map
    } catch (_: Throwable) {
        null
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private const val MAX_UNZIPPED_BYTES = 24L * 1024L * 1024L

    private val WANTED = setOf(
        "xl/workbook.xml",
        "xl/_rels/workbook.xml.rels",
        "xl/sharedStrings.xml",
    )

    /** Group 1 = cell ref, group 2 = the element's inner XML. */
    private val CELL = Regex(
        "<c[^>]*\\br=\"([A-Z]+[0-9]+)\"[^>]*>(.*?)</c>",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val TYPE = Regex("\\bt=\"([^\"]+)\"")
    private val FORMULA = Regex("<f[^>]*>(.*?)</f>", RegexOption.DOT_MATCHES_ALL)
    private val VALUE = Regex("<v[^>]*>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
    private val INLINE = Regex("<is>.*?<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
    private val SI = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
    private val TEXT = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
    private val REL = Regex(
        "<Relationship[^>]*\\bId=\"([^\"]+)\"[^>]*\\bTarget=\"([^\"]+)\"",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val SHEET = Regex(
        "<sheet[^>]*\\bname=\"([^\"]+)\"[^>]*\\br:id=\"([^\"]+)\"",
        RegexOption.DOT_MATCHES_ALL,
    )
}
