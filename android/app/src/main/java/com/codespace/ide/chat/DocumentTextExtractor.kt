package com.codespace.ide.chat

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * C12 Stage 3 U2 (advisor GO 2026-10-20): DOCUMENT TEXT EXTRACTION for
 * untrusted files. Everything here is a LINEAR state machine — no regex with
 * nested quantifiers (BATCH7 lesson), no entity expansion, no WebView, no
 * network, nothing executed.
 *
 *  - XML (DOCX/XLSX/PPTX/ODF): a hand-rolled LINEAR scanner. DOCTYPE and ENTITY
 *    declarations are REJECTED outright (billion-laughs is structurally
 *    impossible: no DTD is ever parsed, no entity is ever expanded); the five
 *    predefined XML entities and numeric refs decode for readability, unknown
 *    named entities are dropped. Depth is capped; tag/attribute buffers are
 *    bounded. This is deliberately NOT XmlPullParser: entity behavior then
 *    rests on parser defaults, while here it rests on the code + a fixture
 *    test (advisor item 2: READ-first — the guard is the parser).
 *  - HTML: linear scanner; script/style content SKIPPED; entities decoded;
 *    block tags become line breaks; never a WebView.
 *  - RTF: linear scanner; destination groups (fonttbl, colortbl, stylesheet,
 *    info, pict, {\*...) SKIPPED; \'hh and \uN escapes decoded; \par \line \tab
 *    kept as breaks/tabs.
 *  - CSV: BOM detection (UTF-8 / UTF-16LE / UTF-16BE), UTF-8 strict, then a
 *    windows-1252 fallback; quoted fields handled linearly.
 *  - OOXML zip reading goes through SafeZipReader ONLY (bombs, ratio, caps).
 *  - .docm/.xlsm are read as TEXT exactly like their macro-free siblings:
 *    the vbaProject stream is never touched, macros are never executed.
 *  - Legacy binary .doc/.xls/.ppt and encrypted OOXML are HONEST refusals.
 *
 * Row/column caps are stated in every "showing X of Y" note (advisor item 1).
 */
object DocumentTextExtractor {

    const val MAX_ROWS = 200
    const val MAX_COLS = 40
    private const val MAX_HTML_BYTES = 2L * 1024 * 1024
    private const val MAX_RTF_BYTES = 2L * 1024 * 1024
    private const val MAX_TEXT_BYTES = 2L * 1024 * 1024
    private const val MAX_CSV_BYTES = 2L * 1024 * 1024

    /** Entry from AttachmentIntake.extractBlocking. Redaction happens HERE. */
    fun extract(file: File, verdict: TypeVerdict): AttachmentIntake.ExtractedDoc {
        val raw = when (verdict.kind) {
            AttachmentTypeKind.HTML -> extractHtml(file)
            AttachmentTypeKind.CSV -> extractCsv(file)
            AttachmentTypeKind.RTF -> extractRtf(file)
            AttachmentTypeKind.DOCX, AttachmentTypeKind.DOCM -> extractOoxmlText(file)
            AttachmentTypeKind.XLSX, AttachmentTypeKind.XLSM -> extractOoxmlSheets(file)
            AttachmentTypeKind.PPTX -> extractOoxmlSlides(file)
            AttachmentTypeKind.ODT, AttachmentTypeKind.ODS, AttachmentTypeKind.ODP -> extractOdf(file)
            else -> return AttachmentIntake.ExtractedDoc(refusal = verdict.reason)
        }
        // Advisor item 5: attachment-tier redaction on ALL extracted text.
        val (redacted, changed) = AttachmentSecrets.redact(raw.text, AttachmentSecrets.TIER_ATTACHMENT)
        val notes = raw.notes.toMutableList()
        if (changed) notes.add("secrets redacted")
        return AttachmentIntake.ExtractedDoc(
            text = redacted,
            notes = notes,
            truncated = raw.truncated,
        )
    }

    private data class RawExtract(val text: String, val notes: List<String> = emptyList(), val truncated: Boolean = false)

    // ── OOXML: DOCX ────────────────────────────────────────────────────────

    private fun extractOoxmlText(file: File): AttachmentIntake.ExtractedDoc = zipWrap(file) { zip ->
        val (bytes, hitCap) = entry(zip, "word/document.xml") ?: return@zipWrap refusalWrap("not a Word document (document.xml missing)")
        val sb = CapSink()
        var paragraph = StringBuilder()
        var cellBuf: StringBuilder? = null
        var rowHasCells = false
        parseXml(bytes, object : XmlHandler {
            override fun open(name: String, attrs: Map<String, String>) {
                when (name) {
                    "w:tc" -> { cellBuf = StringBuilder(); rowHasCells = true }
                }
            }
            override fun text(t: String) {
                val dst = cellBuf ?: paragraph
                if (dst.length < 100_000) dst.append(t)
            }
            override fun close(name: String) {
                when (name) {
                    "w:tab" -> text("\t")
                    "w:br", "w:cr" -> text("\n")
                    "w:tc" -> {
                        val c = cellBuf ?: return
                        sb.appendCell(c.toString())
                        cellBuf = null
                    }
                    "w:tr" -> { if (rowHasCells) { sb.newline(); rowHasCells = false } }
                    "w:p" -> {
                        val p = paragraph.toString().trim()
                        paragraph = StringBuilder()
                        if (p.isNotEmpty()) sb.line(p)
                    }
                }
            }
        })
        finish(RawExtract(text = sb.result(), truncated = hitCap || sb.hitCap), "paragraphs")
    }

    // ── OOXML: XLSX ────────────────────────────────────────────────────────

    private fun extractOoxmlSheets(file: File): AttachmentIntake.ExtractedDoc = zipWrap(file) { zip ->
        // 1. shared strings
        val shared = ArrayList<String>()
        entry(zip, "xl/sharedStrings.xml")?.let { (bytes, _) ->
            parseXml(bytes, object : XmlHandler {
                var inSi = false
                override fun open(name: String, attrs: Map<String, String>) { if (name == "si") inSi = true }
                override fun text(t: String) { if (inSi && shared.size <= MAX_ROWS * MAX_COLS * 2) shared.add(t) }
                override fun close(name: String) { if (name == "si") inSi = false }
            })
        }
        // 2. sheet names + rIds from workbook.xml, targets from rels
        val sheets = ArrayList<Pair<String, String>>()   // name -> rId
        entry(zip, "xl/workbook.xml")?.let { (bytes, _) ->
            parseXml(bytes, object : XmlHandler {
                override fun open(name: String, attrs: Map<String, String>) {
                    if (name == "sheet" && sheets.size < 20) {
                        sheets.add((attrs["name"] ?: "Sheet") to (attrs["r:id"] ?: ""))
                    }
                }
                override fun text(t: String) {}
                override fun close(name: String) {}
            })
        }
        val targets = HashMap<String, String>()
        entry(zip, "xl/_rels/workbook.xml.rels")?.let { (bytes, _) ->
            parseXml(bytes, object : XmlHandler {
                override fun open(name: String, attrs: Map<String, String>) {
                    val id = attrs["Id"] ?: return
                    if (targets.size < 40) targets[id] = attrs["Target"] ?: return
                }
                override fun text(t: String) {}
                override fun close(name: String) {}
            })
        }
        val sb = CapSink()
        val notes = ArrayList<String>()
        if (sheets.isEmpty()) {
            // Old/simple workbooks sometimes omit rels ordering: fall back to
            // every xl/worksheets/sheetN.xml found (bounded by the entry cap).
            val names = SafeZipReader.listEntries(file).map { it.name }
                .filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
            for (n in names.take(20)) {
                val (bytes, _) = entry(zip, n) ?: continue
                sb.line("[Sheet ${n.substringAfterLast('/').removeSuffix(".xml")}]")
                sheetCells(bytes, shared, sb, notes)
            }
        } else {
            for ((idx, pair) in sheets.withIndex()) {
                val target = targets[pair.second] ?: continue
                val name = "xl/" + target.removePrefix("/").removePrefix("xl/")
                val (bytes, _) = entry(zip, name) ?: continue
                sb.line("[Sheet ${idx + 1}: ${pair.first}]")
                sheetCells(bytes, shared, sb, notes)
            }
        }
        finish(RawExtract(sb.result(), notes, sb.hitCap), "rows")
    }

    private fun sheetCells(bytes: ByteArray, shared: List<String>, sb: CapSink, notes: MutableList<String>) {
        var rowCells = 0
        var rowsEmitted = 0
        var totalRows = 0
        var cellText: StringBuilder? = null
        var cellIsShared = false
        // collect text ONLY inside <v>/<t>: <f> (formula SOURCE) is never
        // collected — only its cached <v> result is, per the advisor spec.
        var collecting = false
        parseXml(bytes, object : XmlHandler {
            override fun open(name: String, attrs: Map<String, String>) {
                when (name) {
                    "row" -> { totalRows++; rowCells = 0 }
                    "c" -> {
                        cellIsShared = attrs["t"] == "s"
                        collecting = attrs["t"] == "inlineStr"
                        cellText = StringBuilder()
                    }
                    "v", "t" -> { collecting = true }
                    "f" -> { collecting = false }
                }
            }
            override fun text(t: String) {
                val c = cellText ?: return
                if (collecting && c.length < 10_000) c.append(t)
            }
            override fun close(name: String) {
                when (name) {
                    "c" -> {
                        val c = cellText ?: return
                        val v = when {
                            cellIsShared -> shared.getOrNull(c.toString().trim().toIntOrNull() ?: -1) ?: ""
                            else -> c.toString().trim()
                        }
                        if (rowsEmitted < MAX_ROWS) {
                            if (rowCells < MAX_COLS) sb.appendCell(v)
                            rowCells++
                        }
                        cellText = null
                    }
                    "row" -> {
                        if (rowsEmitted < MAX_ROWS) { sb.newline(); rowsEmitted++ }
                    }
                }
            }
        })
        if (totalRows > rowsEmitted) {
            notes.add("showing $rowsEmitted of $totalRows rows (row cap $MAX_ROWS, column cap $MAX_COLS)")
        }
    }

    // ── OOXML: PPTX ─────────────────────────────────────────────────────────

    private fun extractOoxmlSlides(file: File): AttachmentIntake.ExtractedDoc = zipWrap(file) { zip ->
        // slide ORDER comes from presentation.xml (sldId r:id list) resolved
        // through presentation.xml.rels — never from entry-name sorting.
        val slideRIds = ArrayList<String>()
        entry(zip, "ppt/presentation.xml")?.let { (bytes, _) ->
            parseXml(bytes, object : XmlHandler {
                override fun open(name: String, attrs: Map<String, String>) {
                    if (name == "p:sldId" && slideRIds.size < 50) slideRIds.add(attrs["r:id"] ?: return)
                }
                override fun text(t: String) {}
                override fun close(name: String) {}
            })
        }
        val targets = HashMap<String, String>()
        entry(zip, "ppt/_rels/presentation.xml.rels")?.let { (bytes, _) ->
            parseXml(bytes, object : XmlHandler {
                override fun open(name: String, attrs: Map<String, String>) {
                    val id = attrs["Id"] ?: return
                    if (targets.size < 60) targets[id] = attrs["Target"] ?: return
                }
                override fun text(t: String) {}
                override fun close(name: String) {}
            })
        }
        val sb = CapSink()
        var emitted = 0
        if (slideRIds.isEmpty() && targets.isEmpty()) return@zipWrap refusalWrap("not a PowerPoint deck (presentation.xml missing)")
        for (rid in slideRIds) {
            val target = targets[rid] ?: continue
            val name = "ppt/" + target.removePrefix("/").removePrefix("ppt/")
            val (bytes, _) = entry(zip, name) ?: continue
            emitted++
            sb.line("[Slide $emitted]")
            val texts = ArrayList<String>()
            parseXml(bytes, object : XmlHandler {
                override fun open(name: String, attrs: Map<String, String>) {}
                override fun text(t: String) { if (texts.size < 500) texts.add(t) }
                override fun close(name: String) { if (name == "a:p") texts.add("\n") }
            })
            for (t in texts) if (t == "\n") sb.newline() else sb.appendText(t)
            sb.newline()
        }
        if (emitted == 0) refusalWrap("no readable slides found")
        else finish(RawExtract(sb.result(), truncated = sb.hitCap), "slides")
    }

    // ── OpenDocument ────────────────────────────────────────────────────────

    private fun extractOdf(file: File): AttachmentIntake.ExtractedDoc = zipWrap(file) { zip ->
        val (bytes, hitCap) = entry(zip, "content.xml") ?: return@zipWrap refusalWrap("not an OpenDocument file (content.xml missing)")
        val sb = CapSink()
        var para = StringBuilder()
        var cell: StringBuilder? = null
        parseXml(bytes, object : XmlHandler {
            override fun open(name: String, attrs: Map<String, String>) {
                if (name == "table:table-cell") cell = StringBuilder()
            }
            override fun text(t: String) {
                val dst = cell ?: para
                if (dst.length < 100_000) dst.append(t)
            }
            override fun close(name: String) {
                when (name) {
                    "table:table-cell" -> {
                        val c = cell ?: return
                        sb.appendCell(c.toString())
                        cell = null
                    }
                    "table:table-row" -> sb.newline()
                    "text:p", "text:h" -> {
                        val p = para.toString().trim()
                        para = StringBuilder()
                        if (p.isNotEmpty()) sb.line(p)
                    }
                }
            }
        })
        finish(RawExtract(sb.result(), truncated = hitCap || sb.hitCap), "paragraphs")
    }

    // ── HTML (linear; no WebView; script/style skipped) ─────────────────────

    fun extractHtml(file: File): AttachmentIntake.ExtractedDoc {
        val bytes = readCapped(file, MAX_HTML_BYTES) ?: return refusalDoc("could not read the file.")
        val src = decodeText(bytes)
        val sb = CapSink()
        var i = 0
        val n = src.length
        var inScript = false
        var inStyle = false
        val BLOCK_TAGS = setOf("p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "section", "article")
        while (i < n) {
            val c = src[i]
            when {
                c == '<' -> {
                    val close = src.indexOf('>', i)
                    if (close < 0) break
                    val inner = src.substring(i + 1, close).trim()
                    val lower = inner.lowercase()
                    if (!inScript && !inStyle && lower.startsWith("script")) inScript = true
                    else if (!inScript && !inStyle && lower.startsWith("style")) inStyle = true
                    else if (inScript && lower.startsWith("/script")) inScript = false
                    else if (inStyle && lower.startsWith("/style")) inStyle = false
                    else if (!inScript && !inStyle) {
                        val tag = lower.trimStart('/').takeWhile { it.isLetterOrDigit() }
                        if (tag in BLOCK_TAGS) sb.newline()
                        if (tag == "td" || tag == "th") sb.appendText(" | ")
                    }
                    i = close + 1
                }
                !inScript && !inStyle && c == '&' -> {
                    val semi = src.indexOf(';', i)
                    if (semi in i + 1..i + 10) {
                        sb.appendText(decodeEntity(src.substring(i + 1, semi)))
                        i = semi + 1
                    } else { sb.appendText("&"); i++ }
                }
                !inScript && !inStyle -> { sb.appendText(c.toString()); i++ }
                else -> i++
            }
        }
        return finish(RawExtract(sb.result(), emptyList(), sb.hitCap), "text")
    }

    // ── CSV (BOM/UTF-16/1252; quoted fields) ────────────────────────────────

    fun extractCsv(file: File): AttachmentIntake.ExtractedDoc {
        val bytes = readCapped(file, MAX_CSV_BYTES) ?: return refusalDoc("could not read the file.")
        val src = decodeText(bytes)
        val sb = CapSink()
        val notes = ArrayList<String>()
        var totalRows = 0
        var shownRows = 0
        var cols = 0
        var i = 0
        val n = src.length
        while (i < n && shownRows < MAX_ROWS) {
            // parse one row
            val row = StringBuilder()
            var field = StringBuilder()
            var inQuotes = false
            var rowEnded = false
            while (i < n) {
                val c = src[i]
                if (inQuotes) {
                    if (c == '"') {
                        if (i + 1 < n && src[i + 1] == '"') { field.append('"'); i += 2; continue }
                        inQuotes = false; i++
                    } else { field.append(c); i++ }
                } else when (c) {
                    '"' -> { inQuotes = true; i++ }
                    ',' -> { appendField(row, field, cols); field = StringBuilder(); cols++; i++ }
                    '\r' -> i++
                    '\n' -> { i++; rowEnded = true }
                    else -> { field.append(c); i++ }
                }
                if (rowEnded) break
            }
            appendField(row, field, cols)
            val line = row.toString().trim()
            if (line.isNotEmpty()) { sb.line(line); shownRows++ }
            totalRows++
            cols = 0
            if (!rowEnded && i >= n) break
            if (i >= n) break
        }
        // count remaining rows cheaply
        if (i < n) totalRows += src.substring(i).count { it == '\n' }
        if (totalRows > shownRows) notes.add("showing $shownRows of $totalRows rows (row cap $MAX_ROWS, column cap $MAX_COLS)")
        return finish(RawExtract(sb.result(), notes, sb.hitCap), "rows")
    }

    // ── RTF (destination groups skipped; escapes decoded) ────────────────────

    private val RTF_SKIP_DESTS = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "themedata", "colorschememapping", "listtable", "listoverridetable")

    fun extractRtf(file: File): AttachmentIntake.ExtractedDoc {
        val bytes = readCapped(file, MAX_RTF_BYTES) ?: return refusalDoc("could not read the file.")
        val src = decodeText(bytes)
        val sb = CapSink()
        var i = 0
        val n = src.length
        var skipDepth = -1   // -1 = not skipping
        var depth = 0
        val cp1252 = Charset.forName("windows-1252")
        val byteBuf = java.io.ByteArrayOutputStream()
        fun flushBytes() {
            if (byteBuf.size() > 0) {
                sb.appendText(String(byteBuf.toByteArray(), cp1252))
                byteBuf.reset()
            }
        }
        while (i < n) {
            val c = src[i]
            when {
                skipDepth >= 0 -> {
                    when (c) {
                        '{' -> depth++
                        '}' -> { depth--; if (depth <= skipDepth) skipDepth = -1 }
                    }
                    i++
                }
                c == '{' -> { depth++; flushBytes(); i++ }
                c == '}' -> { flushBytes(); depth--; i++ }
                c == '\\' -> {
                    flushBytes()
                    if (i + 1 >= n) break
                    val d = src[i + 1]
                    when {
                        d == '\\' -> { sb.appendText("\\"); i += 2 }
                        d == '{' -> { sb.appendText("{"); i += 2 }
                        d == '}' -> { sb.appendText("}"); i += 2 }
                        d == '\'' -> {
                            if (i + 3 < n) {
                                val hex = src.substring(i + 2, i + 4)
                                val b = hex.toIntOrNull(16)
                                if (b != null) byteBuf.write(b) else sb.appendText("?")
                                i += 4
                            } else i = n
                        }
                        d == 'u' && i + 2 < n && src[i + 2].isDigit() -> {
                            var j = i + 2
                            var num = 0
                            var neg = false
                            if (j < n && src[j] == '-') { neg = true; j++ }
                            while (j < n && src[j].isDigit()) { num = num * 10 + (src[j] - '0'); j++ }
                            var code = if (neg) -num else num
                            if (code < 0) code += 65536
                            sb.appendText(code.toChar().toString())
                            // skip the backup char (\uN nextchar)
                            if (j < n && src[j] == '?') j++
                            i = j
                        }
                        else -> {
                            // control word: letters, optional numeric param
                            var j = i + 1
                            val word = StringBuilder()
                            while (j < n && src[j].isLetter() && word.length < 32) { word.append(src[j]); j++ }
                            while (j < n && (src[j].isDigit() || src[j] == '-')) j++
                            if (j < n && src[j] == ' ') j++
                            when (word.toString()) {
                                "par", "line" -> sb.newline()
                                "tab" -> sb.appendText("\t")
                            }
                            // Destination groups are skipped ENTIRELY: the group
                            // opened by the PRECEDING brace (depth D already
                            // incremented) is closed when depth drops below D —
                            // skipDepth = D - 1, brace NOT consumed here.
                            if (word.toString() in RTF_SKIP_DESTS) {
                                skipDepth = depth - 1
                            } else if (word.toString() == "*") {
                                // {\*\generator ...} — an ignorable destination
                                skipDepth = depth - 1
                            }
                            i = j
                        }
                    }
                }
                c == '\r' || c == '\n' -> { flushBytes(); i++ }
                else -> { sb.appendText(c.toString()); i++ }
            }
        }
        flushBytes()
        return finish(RawExtract(sb.result(), emptyList(), sb.hitCap), "text")
    }

    // ── shared helpers ──────────────────────────────────────────────────────

    private fun appendField(row: StringBuilder, field: StringBuilder, cols: Int) {
        if (cols >= MAX_COLS) return
        if (row.isNotEmpty()) row.append(" | ")
        row.append(field.toString().trim())
    }

    private fun decodeEntity(e: String): String = when (e) {
        "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
        "nbsp" -> " "; "hellip" -> "…"; "mdash" -> "—"; "ndash" -> "–"; "rsquo" -> "’"; "lsquo" -> "‘"; "ldquo" -> "“"; "rdquo" -> "”"
        else -> {
            if (e.startsWith("#")) {
                val code = if (e.startsWith("#x") || e.startsWith("#X")) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                code?.toChar()?.toString() ?: " "
            } else " "   // unknown named entity — never a loop, never an expansion
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        // BOM detection first, then UTF-8 strict, then windows-1252 fallback.
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    private fun readCapped(file: File, cap: Long): ByteArray? = try {
        file.inputStream().use { ins ->
            val size = minOf(file.length(), cap).toInt()
            val buf = ByteArray(size)
            var read = 0
            while (read < size) {
                val k = ins.read(buf, read, size - read)
                if (k < 0) break
                read += k
            }
            buf.copyOf(read)
        }
    } catch (_: Exception) { null }

    /** Redaction + snapshot cap + refusal carrier, one place. */
    private fun finish(raw: RawExtract, what: String): AttachmentIntake.ExtractedDoc {
        var text = raw.text
        var truncated = raw.truncated
        if (text.length > AttachmentIntake.MAX_EXTRACTED_SNAPSHOT_CHARS) {
            text = text.take(AttachmentIntake.MAX_EXTRACTED_SNAPSHOT_CHARS)
            truncated = true
        }
        val (red, changed) = AttachmentSecrets.redact(text, AttachmentSecrets.TIER_ATTACHMENT)
        val notes = raw.notes.toMutableList()
        if (changed) notes.add("secrets redacted")
        if (truncated) notes.add("extracted text truncated to fit the preview cap")
        return AttachmentIntake.ExtractedDoc(text = red, notes = notes, truncated = truncated)
    }

    private fun refusalDoc(reason: String) = AttachmentIntake.ExtractedDoc(refusal = reason)

    // Small zip-plumbing wrappers -------------------------------------------

    private class ZipOut<T>(val value: T)

    private inline fun <T> zipWrap(file: File, body: (ZipFile) -> T): AttachmentIntake.ExtractedDoc {
        return try {
            val zip = ZipFile(file)
            try {
                when (val r = body(zip)) {
                    is AttachmentIntake.ExtractedDoc -> r
                    is RefusalWrap -> refusalDoc(r.reason)
                    else -> refusalDoc("unreadable document")
                }
            } finally {
                try { zip.close() } catch (_: Exception) {}
            }
        } catch (e: SafeZipReader.ZipLimitException) {
            refusalDoc("archive limits exceeded (${e.what}) — showing file info only")
        } catch (e: SafeZipReader.ZipEncryptedException) {
            refusalDoc("this file is password-encrypted — not supported; showing file info only")
        } catch (e: SafeZipReader.ZipMalformedException) {
            refusalDoc("not a readable archive (${e.message}) — showing file info only")
        } catch (e: XmlReject) {
            refusalDoc("rejected XML (${e.reason}) — showing file info only")
        } catch (e: Exception) {
            refusalDoc("extraction failed (${e.message ?: "unreadable"}) — showing file info only")
        }
    }

    private class RefusalWrap(val reason: String)

    private fun refusalWrap(reason: String) = RefusalWrap(reason)

    private fun entry(zip: ZipFile, name: String): Pair<ByteArray, Boolean>? {
        val e = zip.getEntry(name) ?: return null
        return SafeZipReader.readEntryStream(zip, e)
    }

    // ── CapSink: the single output-bounding point ───────────────────────────

    /** Every extractor writes through this — the preview cap in ONE place. */
    class CapSink {
        private val sb = StringBuilder()
        var hitCap = false
            private set
        private val cap = AttachmentIntake.MAX_EXTRACTED_SNAPSHOT_CHARS + 4096

        fun appendText(t: String) {
            if (sb.length >= cap) { hitCap = true; return }
            sb.append(t)
            if (sb.length >= cap) hitCap = true
        }
        fun appendCell(t: String) {
            val hasContent = sb.isNotEmpty() && sb.last() != '\n'
            if (hasContent) appendText(" | ")
            appendText(t)
        }
        fun newline() {
            if (sb.length >= 3 && sb.endsWith(" | ")) sb.setLength(sb.length - 3)
            if (sb.isNotEmpty() && sb.last() != '\n') appendText("\n")
        }
        fun line(t: String) { if (sb.isNotEmpty()) appendText("\n"); appendText(t) }
        fun result(): String {
            var s = sb.toString()
            if (s.length > AttachmentIntake.MAX_EXTRACTED_SNAPSHOT_CHARS) {
                s = s.take(AttachmentIntake.MAX_EXTRACTED_SNAPSHOT_CHARS)
                hitCap = true
            }
            return s.trim()
        }
    }

    // ── LINEAR XML SCANNER (no entity expansion; DOCTYPE rejected) ───────────

    class XmlReject(val reason: String) : Exception(reason)

    interface XmlHandler {
        fun open(name: String, attrs: Map<String, String>)
        fun text(t: String)
        fun close(name: String)
    }

    private const val MAX_DEPTH = 256
    private const val MAX_TAG_CHARS = 128
    private const val MAX_ATTR_CHARS = 1024
    private const val MAX_ATTRS = 16

    fun parseXml(bytes: ByteArray, h: XmlHandler) {
        val src = decodeText(bytes)
        var i = 0
        val n = src.length
        val stack = ArrayList<String>(64)
        val textBuf = StringBuilder()
        fun flushText() {
            if (textBuf.isNotEmpty()) {
                var t = textBuf.toString()
                if (t.length > 100_000) { t = t.take(100_000) }
                h.text(t)
                textBuf.setLength(0)
            }
        }
        while (i < n) {
            val c = src[i]
            if (c == '<') {
                if (src.startsWith("<!--", i)) {
                    flushText()
                    val end = src.indexOf("-->", i + 4)
                    i = if (end < 0) n else end + 3
                } else if (src.startsWith("<![CDATA[", i)) {
                    val end = src.indexOf("]]>", i + 9)
                    textBuf.append(if (end < 0) src.substring(i + 9) else src.substring(i + 9, end))
                    i = if (end < 0) n else end + 3
                } else if (src.startsWith("<?", i)) {
                    flushText()
                    val end = src.indexOf("?>", i)
                    i = if (end < 0) n else end + 2
                } else if (src.startsWith("<!", i)) {
                    // DOCTYPE and every other markup declaration: REJECTED —
                    // no DTD is ever parsed, so no entity is ever expanded.
                    throw XmlReject("markup declaration (DOCTYPE/ENTITY) not allowed")
                } else {
                    val close = src.indexOf('>', i)
                    if (close < 0) break
                    var inner = src.substring(i + 1, close).trim()
                    if (inner.endsWith("/>")) {
                        flushText()
                        // self-closing: report open+close for empty elements
                        val nm = tagName(inner.removeSuffix("/>"))
                        h.open(nm.first, nm.second)
                        h.close(nm.first)
                        i = close + 1
                        continue
                    }
                    if (inner.startsWith("/")) {
                        flushText()
                        val nm = inner.removePrefix("/").trim()
                        if (stack.isNotEmpty() && stack.last() == nm) {
                            stack.removeAt(stack.size - 1)
                            h.close(nm)
                        } else {
                            h.close(nm) // malformed nesting — linear, honest
                        }
                        i = close + 1
                    } else {
                        flushText()
                        val nm = tagName(inner)
                        if (stack.size < MAX_DEPTH) {
                            stack.add(nm.first)
                            h.open(nm.first, nm.second)
                        } // over-depth: ignored (bounded memory; no quadratic walk)
                        i = close + 1
                    }
                }
            } else if (c == '&') {
                val semi = src.indexOf(';', i)
                if (semi in i + 1..i + 10) {
                    textBuf.append(decodeEntity(src.substring(i + 1, semi)))
                    i = semi + 1
                } else { textBuf.append('&'); i++ }
            } else {
                if (textBuf.length > 200_000) flushText()
                textBuf.append(c)
                i++
            }
        }
        flushText()
    }

    /** Split "<w:p w:x=y" into name + bounded attr map — linear, no regex. */
    private fun tagName(inner: String): Pair<String, Map<String, String>> {
        var k = 0
        val n = inner.length
        while (k < n && !Character.isWhitespace(inner[k])) k++
        val name = inner.substring(0, minOf(k, MAX_TAG_CHARS))
        val attrs = HashMap<String, String>(4)
        var j = k
        while (j < n && attrs.size < MAX_ATTRS) {
            while (j < n && Character.isWhitespace(inner[j])) j++
            if (j >= n) break
            val eq = inner.indexOf('=', j)
            if (eq < 0 || eq > j + MAX_TAG_CHARS) break
            val key = inner.substring(j, eq).trim()
            val q = eq + 1
            if (q >= n) break
            val quote = inner[q]
            if (quote != '"' && quote != '\'') break
            val end = inner.indexOf(quote, q + 1)
            if (end < 0) break
            attrs[key] = inner.substring(q + 1, minOf(end, q + 1 + MAX_ATTR_CHARS))
            j = end + 1
        }
        return name to attrs
    }
}
