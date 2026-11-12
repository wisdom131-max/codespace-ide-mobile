package com.codespace.ide.agent.docs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * P2 - READING A DOCUMENT THE MODEL CAN ACTUALLY USE.
 *
 * `read_file` returned `readText()` for everything, so a PDF or a DOCX arrived as mojibake or as a
 * refusal. These extractors return PLAIN TEXT, bounded, and say what they could not do rather than
 * pretending. No OCR, no rendering, no third-party library: PDF via its own content streams, Office
 * formats via the ZIP container they already are.
 *
 * Every limit here exists for a phone: bytes in, characters out, and a hard stop.
 */
object DocText {

    const val MAX_INPUT_BYTES = 6_000_000
    const val MAX_OUTPUT_CHARS = 16_000

    enum class Kind { PDF, DOCX, XLSX, PPTX, PLAIN, UNSUPPORTED }

    fun kindOf(name: String): Kind = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> Kind.PDF
        "docx" -> Kind.DOCX
        "xlsx" -> Kind.XLSX
        "pptx" -> Kind.PPTX
        "txt", "md", "markdown", "csv", "tsv", "json", "log", "kt", "java", "py", "js", "ts",
        "xml", "yaml", "yml", "html", "sh" -> Kind.PLAIN
        else -> Kind.UNSUPPORTED
    }

    /**
     * Extract text, or explain why not. The note is part of the answer, not a failure: a scanned PDF
     * legitimately has no text layer, and telling the model that is more useful than an empty string.
     */
    fun extract(name: String, bytes: ByteArray): String {
        if (bytes.isEmpty()) return "Empty file: " + name
        if (bytes.size > MAX_INPUT_BYTES) {
            return "Refused: " + name + " is " + bytes.size + " bytes; the document reader is bounded to " +
                MAX_INPUT_BYTES + ". Use run_command with pdftotext or unzip for a larger file."
        }
        return when (kindOf(name)) {
            Kind.PLAIN -> String(bytes, Charsets.UTF_8).take(MAX_OUTPUT_CHARS)
            Kind.PDF -> pdf(bytes)
            Kind.DOCX -> office(bytes, "word/document.xml", "Word document")
            Kind.PPTX -> office(bytes, listOf("ppt/slides/slide"), "PowerPoint deck")
            Kind.XLSX -> office(bytes, "xl/worksheets/sheet1.xml", "Excel sheet")
            Kind.UNSUPPORTED -> "Not a readable document type: " + name +
                ". The document reader handles pdf, docx, pptx, xlsx and plain text."
        }
    }

    // ------------------------------------------------------------------ PDF

    private val STREAM = Regex("stream\\r?\\n", RegexOption.DOT_MATCHES_ALL)

    /**
     * Text from a PDF's content streams.
     *
     * Deliberately narrow and honest: it inflates FlateDecode streams, reads the literal strings of
     * text operators (`Tj`, `TJ`, `'`, `"`), and reports when it found no text layer at all. It does
     * NOT decode CID/embedded subset fonts, which is where a scanned or exotic document ends up - and
     * it says so instead of returning nothing.
     */
    fun pdf(bytes: ByteArray): String {
        val raw = String(bytes, Charsets.ISO_8859_1)
        if (raw.contains("/Encrypt")) {
            return "This PDF is encrypted; its text cannot be read without the password."
        }
        val out = StringBuilder()
        var streams = 0
        var cursor = 0
        while (true) {
            val m = STREAM.find(raw, cursor) ?: break
            val start = m.range.last + 1
            val end = raw.indexOf("endstream", start)
            if (end < 0) break
            cursor = end + 9
            val chunk = raw.substring(start, end)
            val decodedBytes = inflate(chunk.toByteArray(Charsets.ISO_8859_1))
            val decoded = if (decodedBytes != null) String(decodedBytes, Charsets.ISO_8859_1) else chunk
            streams++
            appendTextOperators(decoded, out)
            if (out.length > MAX_OUTPUT_CHARS) break
        }
        val text = out.toString().trim()
        if (text.isEmpty()) {
            return "No text layer found in this PDF (" + streams + " streams read). It is probably a scan; " +
                "there is no OCR here, so the page images would have to be viewed instead."
        }
        return text.take(MAX_OUTPUT_CHARS)
    }

    private fun appendTextOperators(content: String, out: StringBuilder): Unit {
        // (literal) Tj  and  [(a) -20 (b)] TJ
        var i = 0
        while (i < content.length) {
            val c = content[i]
            if (c == '(') {
                val sb = StringBuilder()
                var depth = 1
                var j = i + 1
                while (j < content.length && depth > 0) {
                    val ch = content[j]
                    when {
                        ch == '\\' && j + 1 < content.length -> {
                            val n = content[j + 1]
                            when (n) {
                                'n' -> sb.append('\n'); 'r' -> sb.append('\n'); 't' -> sb.append(' ')
                                '(', ')', '\\' -> sb.append(n)
                                else -> if (n.isDigit()) {
                                    // octal escape, up to 3 digits
                                    var k = j + 1
                                    var code = 0
                                    var digits = 0
                                    while (k < content.length && digits < 3 && content[k].isDigit()) {
                                        code = code * 8 + (content[k] - '0'); k++; digits++
                                    }
                                    sb.append(code.toChar())
                                    j = k - 2
                                } else sb.append(n)
                            }
                            j += 2
                        }
                        ch == '(' -> { depth++; sb.append(ch); j++ }
                        ch == ')' -> { depth--; if (depth > 0) sb.append(ch); j++ }
                        else -> { sb.append(ch); j++ }
                    }
                }
                if (sb.isNotEmpty()) {
                    out.append(sb).append(' ')
                    if (out.length > MAX_OUTPUT_CHARS) return
                }
                i = j
            } else i++
        }
    }

    private fun inflate(data: ByteArray): ByteArray? = try {
        val inflater = Inflater()
        inflater.setInput(data)
        val buffer = ByteArray(16_384)
        val result = ByteArrayOutputStream()
        var guard = 0
        while (!inflater.finished() && guard < 512) {
            val n = inflater.inflate(buffer)
            if (n == 0) break
            result.write(buffer, 0, n)
            if (result.size() > MAX_INPUT_BYTES) break
            guard++
        }
        inflater.end()
        if (result.size() == 0) null else result.toByteArray()
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------ Office

    /** Office files are ZIP containers: pull the part that holds the words and strip the markup. */
    private fun office(bytes: ByteArray, part: String, label: String): String =
        office(bytes, listOf(part), label)

    private fun office(bytes: ByteArray, parts: List<String>, label: String): String {
        val wanted = StringBuilder()
        var found = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                var guard = 0
                while (entry != null && guard < 4_000) {
                    guard++
                    val name = entry.name
                    val matches = parts.any { p -> name == p } ||
                        (parts.size == 1 && parts[0].endsWith("/slide") && name.startsWith(parts[0]) && name.endsWith(".xml"))
                    if (matches && !entry.isDirectory) {
                        found++
                        wanted.append(stripXml(readBounded(zip))).append('\n')
                        if (wanted.length > MAX_OUTPUT_CHARS) break
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (t: Throwable) {
            return "Could not open this " + label + ": " + (t.message ?: "not a valid Office container")
        }
        val text = wanted.toString().trim()
        if (text.isEmpty()) {
            return if (found == 0) {
                label + " opened but contains no readable text part."
            } else {
                label + " has only empty text."
            }
        }
        return text.take(MAX_OUTPUT_CHARS)
    }

    private fun readBounded(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < MAX_INPUT_BYTES) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** WordprocessingML text lives in `<w:t>`; a paragraph break is `</w:p>`. */
    fun stripXml(xml: String): String {
        val sb = StringBuilder(xml.length / 3)
        var i = 0
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) { sb.append(xml, i, xml.length); break }
            if (lt > i) sb.append(xml, i, lt)
            val gt = xml.indexOf('>', lt)
            if (gt < 0) break
            val tag = xml.substring(lt + 1, gt)
            if (tag == "/w:p" || tag == "/a:p" || tag == "/row") sb.append('\n')
            else if (tag == "/c" || tag == "w:br/") sb.append(' ')
            else if (tag.startsWith("w:tab")) sb.append('\t')
            i = gt + 1
        }
        return sb.toString().lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
    }
}
