package com.codespace.ide.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Wave 1 Group A (owner approval 2026-11-11): the data tools.
 *
 * WHY THESE EXIST: a JSON or CSV file used to reach the model as 3,000 lines of context, and the
 * model then "read" it by eye. Base64 arrived as a wall of text it either guessed at or shelled out
 * to decode. Each of these returns the ANSWER instead of the payload, so a big file costs a few
 * hundred tokens rather than a few thousand, and the answer is computed rather than eyeballed.
 *
 * Every limit here REFUSES or ANNOUNCES. Nothing is silently trimmed: a truncated result says it was
 * truncated and how far it got, because a model that does not know its result is partial will
 * confidently report a half-answer.
 */
object DataTools {

    /** Parsing a document this large inside the model's call budget is already unkind; refuse. */
    const val MAX_JSON_CHARS = 2_000_000
    const val MAX_MATCHES = 200
    const val MAX_VALUE_CHARS = 300
    const val MAX_CSV_CHARS = 4_000_000
    const val MAX_CSV_ROWS = 500
    const val MAX_CODEC_CHARS = 1_000_000

    // ── json_query ─────────────────────────────────────────────────────────

    private sealed class Step {
        data class Key(val name: String) : Step()
        data class Index(val index: Int) : Step()
        data class Union(val names: List<String>, val indexes: List<Int>) : Step()
        object Wildcard : Step()
        data class RecursiveKey(val name: String) : Step()
        object RecursiveAll : Step()
    }

    private class Collector(val limit: Int) {
        val found = ArrayList<Pair<String, Any?>>()
        var overflow = false
        fun add(path: String, value: Any?) {
            if (found.size >= limit) { overflow = true; return }
            found.add(path to value)
        }
    }

    /**
     * A documented JSONPath SUBSET, not JSONPath.
     *
     * Supported: `$`, `.key`, `['key']`, `[0]`, `[*]`, `[0,2]`, `['a','b']`, `..key`, `..*`.
     * NOT supported: filters `[?(@.x)]`, slices `[1:3]`, functions. An unsupported form is REFUSED
     * with the character position rather than approximated: silently answering a different question
     * than the one asked is worse than saying no.
     */
    fun jsonQuery(json: String, expression: String): String {
        if (json.length > MAX_JSON_CHARS) {
            return "Refusing: the document is ${json.length} characters; the limit for one call is $MAX_JSON_CHARS."
        }
        val root = try {
            parseJson(json.trim())
        } catch (e: Exception) {
            return "Invalid JSON: " + (e.message ?: "could not parse the document") +
                "\nNothing was queried. The document is not modified."
        }

        val steps = try {
            parsePath(expression.trim())
        } catch (e: IllegalArgumentException) {
            return "Invalid expression '$expression': " + (e.message ?: "could not parse it") +
                "\nSupported: \$ , .key , ['key'] , [0] , [*] , [0,2] , ..key , ..*"
        }

        val collector = Collector(MAX_MATCHES)
        walk(root, steps, 0, "$", collector)

        val body = StringBuilder()
        body.append("{\"expression\":").append(quote(expression.trim()))
            .append(",\"count\":").append(collector.found.size)
            .append(",\"truncated\":").append(if (collector.overflow) "true" else "false")
            .append(",\"matches\":[")
        collector.found.forEachIndexed { i, (path, value) ->
            if (i > 0) body.append(',')
            body.append("{\"path\":").append(quote(path)).append(",\"value\":").append(render(value)).append('}')
        }
        body.append("]}")
        if (collector.overflow) {
            body.append("\nStopped after $MAX_MATCHES matches; more exist. Narrow the expression with a key or an index.")
        }
        return body.toString()
    }

    private fun parseJson(text: String): Any? {
        if (text.isEmpty()) throw IllegalArgumentException("the document is empty")
        return when (text.first()) {
            '{' -> JSONObject(text)
            '[' -> JSONArray(text)
            else -> throw IllegalArgumentException("the document must start with '{' or '['")
        }
    }

    private fun parsePath(expression: String): List<Step> {
        if (expression.isEmpty()) throw IllegalArgumentException("the expression is empty")
        if (expression.first() != '$') throw IllegalArgumentException("an expression must start with '\$'")
        val steps = ArrayList<Step>()
        var i = 1
        while (i < expression.length) {
            val c = expression[i]
            when {
                c == '.' -> {
                    if (i + 1 < expression.length && expression[i + 1] == '.') {
                        i += 2
                        if (i < expression.length && expression[i] == '*') {
                            steps.add(Step.RecursiveAll); i++
                        } else {
                            val name = readName(expression, i)
                            if (name.isEmpty()) throw IllegalArgumentException("'..' must be followed by a key or '*' at $i")
                            steps.add(Step.RecursiveKey(name)); i += name.length
                        }
                    } else {
                        i++
                        if (i < expression.length && expression[i] == '*') {
                            steps.add(Step.Wildcard); i++
                        } else {
                            val name = readName(expression, i)
                            if (name.isEmpty()) throw IllegalArgumentException("a '.' must be followed by a key at $i")
                            steps.add(Step.Key(name)); i += name.length
                        }
                    }
                }
                c == '[' -> {
                    val close = expression.indexOf(']', i)
                    if (close < 0) throw IllegalArgumentException("unclosed '[' at $i")
                    val inner = expression.substring(i + 1, close).trim()
                    steps.add(parseBracket(inner, i))
                    i = close + 1
                }
                else -> throw IllegalArgumentException("unexpected '" + c + "' at $i; expected '.' or '['")
            }
        }
        if (steps.isEmpty()) throw IllegalArgumentException("'\$' on its own selects the whole document; add a step")
        return steps
    }

    private fun readName(s: String, from: Int): String {
        var end = from
        while (end < s.length && (s[end].isLetterOrDigit() || s[end] == '_' || s[end] == '-' || s[end] == ' ')) end++
        return s.substring(from, end)
    }

    private fun parseBracket(inner: String, at: Int): Step {
        if (inner.isEmpty()) throw IllegalArgumentException("empty '[]' at $at")
        if (inner == "*") return Step.Wildcard
        if (inner.startsWith("?") || inner.contains(":")) {
            throw IllegalArgumentException("filters and slices are not supported (at $at); use a key, an index or '[*]'")
        }
        val parts = splitTop(inner)
        val names = ArrayList<String>()
        val indexes = ArrayList<Int>()
        for (raw in parts) {
            val p = raw.trim()
            when {
                p.length >= 2 && (p.first() == '\'' || p.first() == '"') && p.last() == p.first() ->
                    names.add(p.substring(1, p.length - 1))
                p.toIntOrNull() != null -> indexes.add(p.toInt())
                else -> throw IllegalArgumentException("'" + p + "' at $at is not a key in quotes or an index")
            }
        }
        if (names.isNotEmpty() && indexes.isNotEmpty()) {
            throw IllegalArgumentException("a union cannot mix keys and indexes (at $at)")
        }
        if (names.size == 1) return Step.Key(names[0])
        if (indexes.size == 1) return Step.Index(indexes[0])
        return Step.Union(names, indexes)
    }

    private fun splitTop(inner: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (c in inner) {
            when {
                quote != null && c == quote -> { quote = null; cur.append(c) }
                quote != null -> cur.append(c)
                c == '\'' || c == '"' -> { quote = c; cur.append(c) }
                c == ',' -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
        }
        out.add(cur.toString())
        return out
    }

    private fun walk(value: Any?, steps: List<Step>, at: Int, path: String, out: Collector) {
        if (value == null || value == JSONObject.NULL) return
        if (at >= steps.size) { out.add(path, value); return }
        when (val step = steps[at]) {
            is Step.Key -> if (value is JSONObject && value.has(step.name)) {
                walk(value.opt(step.name), steps, at + 1, path + "." + step.name, out)
            }
            is Step.Index -> if (value is JSONArray && step.index >= 0 && step.index < value.length()) {
                walk(value.opt(step.index), steps, at + 1, path + "[" + step.index + "]", out)
            }
            is Step.Union -> {
                if (value is JSONObject) step.names.forEach { name ->
                    if (value.has(name)) walk(value.opt(name), steps, at + 1, path + "." + name, out)
                }
                if (value is JSONArray) step.indexes.forEach { idx ->
                    if (idx >= 0 && idx < value.length()) walk(value.opt(idx), steps, at + 1, path + "[" + idx + "]", out)
                }
            }
            is Step.Wildcard -> when (value) {
                is JSONArray -> for (i in 0 until value.length()) {
                    walk(value.opt(i), steps, at + 1, path + "[" + i + "]", out)
                }
                is JSONObject -> value.keys().asSequence().toList().sorted().forEach { k ->
                    walk(value.opt(k), steps, at + 1, path + "." + k, out)
                }
                else -> Unit
            }
            is Step.RecursiveKey -> descend(value, path) { childPath, child ->
                if (child is JSONObject && child.has(step.name)) {
                    walk(child.opt(step.name), steps, at + 1, childPath + "." + step.name, out)
                }
            }
            is Step.RecursiveAll -> descend(value, path) { childPath, child ->
                walk(child, steps, at + 1, childPath, out)
            }
        }
    }

    /** Visits every descendant (not the node itself) so `..key` can look in every object. */
    private fun descend(value: Any?, path: String, visit: (String, Any?) -> Unit) {
        when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().forEach { k ->
                val child = value.opt(k)
                val childPath = path + "." + k
                visit(childPath, child)
                descend(child, childPath, visit)
            }
            is JSONArray -> for (i in 0 until value.length()) {
                val child = value.opt(i)
                val childPath = path + "[" + i + "]"
                visit(childPath, child)
                descend(child, childPath, visit)
            }
        }
    }

    private fun render(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> clip(quote(value))
        is JSONObject -> clip(value.toString())
        is JSONArray -> clip(value.toString())
        is Number, is Boolean -> value.toString()
        else -> clip(quote(value.toString()))
    }

    private fun clip(text: String): String =
        if (text.length <= MAX_VALUE_CHARS) text
        else quote(text.substring(1, MAX_VALUE_CHARS - 1) + "… (" + (text.length - MAX_VALUE_CHARS) + " chars omitted)")

    private fun quote(text: String): String {
        val sb = StringBuilder(text.length + 8)
        sb.append('"')
        for (c in text) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }

    // ── csv_parse ──────────────────────────────────────────────────────────

    /**
     * RFC 4180 as it is actually found in the wild: quoted fields, commas and NEWLINES inside
     * quotes, doubled quotes for a literal quote, CRLF or LF line endings.
     *
     * Ragged rows are reported, not repaired silently: the columns are padded so the output lines
     * up, and the answer says how many rows disagreed with the header.
     */
    fun csvParse(
        text: String,
        delimiter: Char = ',',
        hasHeader: Boolean = true,
        offset: Int = 0,
        limit: Int = 50,
        columns: List<String> = emptyList(),
    ): String {
        if (text.length > MAX_CSV_CHARS) {
            return "Refusing: the file is ${text.length} characters; the limit for one call is $MAX_CSV_CHARS."
        }
        val rows = ArrayList<List<String>>()
        val cur = StringBuilder()
        var row = ArrayList<String>()
        var inQuotes = false
        var i = 0
        var lineNo = 1
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cur.append('"'); i += 2; continue }
                    c == '"' -> { inQuotes = false; i++ }
                    else -> { if (c == '\n') lineNo++; cur.append(c); i++ }
                }
                continue
            }
            when {
                c == '"' && cur.isEmpty() -> { inQuotes = true; i++ }
                c == delimiter -> { row.add(cur.toString()); cur.setLength(0); i++ }
                c == '\r' -> i++
                c == '\n' -> {
                    row.add(cur.toString()); cur.setLength(0)
                    rows.add(row); row = ArrayList(); lineNo++; i++
                }
                else -> { cur.append(c); i++ }
            }
        }
        if (inQuotes) {
            return "Malformed CSV: an opening quote near line $lineNo is never closed. Nothing was parsed."
        }
        if (cur.isNotEmpty() || row.isNotEmpty()) {
            row.add(cur.toString())
            rows.add(row)
        }
        if (rows.isEmpty()) return "The CSV is empty: no rows found."

        val header = if (hasHeader) rows.first() else emptyList()
        val data = if (hasHeader) rows.drop(1) else rows
        val width = rows.maxOf { it.size }
        val ragged = rows.count { it.size != width }

        val headerCells = if (hasHeader) header else (0 until width).map { "col" + (it + 1) }
        val wanted: List<Int> = if (columns.isEmpty()) (0 until width).toList()
        else columns.map { name ->
            val idx = headerCells.indexOfFirst { it.trim().equals(name.trim(), ignoreCase = true) }
            if (idx < 0) return "No column named '$name'. Available: " + headerCells.joinToString(", ")
            idx
        }

        val from = offset.coerceAtLeast(0)
        if (from >= data.size) {
            return "Offset $from is past the end: the file has ${data.size} data rows."
        }
        val capped = limit.coerceIn(1, MAX_CSV_ROWS)
        val slice = data.drop(from).take(capped)

        val sb = StringBuilder()
        sb.append("CSV: ").append(data.size).append(" data rows, ").append(width).append(" columns")
        if (hasHeader) sb.append(" (header: ").append(headerCells.joinToString(", ")).append(')')
        else sb.append(" (no header row)")
        sb.append('\n')
        if (ragged > 0) sb.append("Warning: ").append(ragged).append(" row(s) do not have ").append(width)
            .append(" cells; short rows are padded with empty strings.\n")
        sb.append("Columns shown: ").append(wanted.map { headerCells[it] }.joinToString(", ")).append('\n')
        if (hasHeader) sb.append("---\n").append(wanted.joinToString(" | ") { headerCells[it] }).append('\n')
        slice.forEachIndexed { n, cells ->
            sb.append("row ").append(from + n + 1).append(": ")
            sb.append(wanted.joinToString(" | ") { if (it < cells.size) cells[it] else "" })
            sb.append('\n')
        }
        val shown = slice.size
        if (from + shown < data.size) {
            sb.append("Showing ").append(shown).append(" of ").append(data.size)
                .append(" data rows (offset ").append(from).append("). Use offset=")
                .append(from + shown).append(" to continue.")
        }
        return sb.toString().trim()
    }

    // ── base64_codec ───────────────────────────────────────────────────────

    /**
     * Encode or decode Base64 as a pure transformation. Nothing is written anywhere.
     *
     * Decoding is STRICT: line breaks and spaces are tolerated (they are everywhere in real base64),
     * any other character is REFUSED with its position. A decoder that quietly skips what it does not
     * understand turns a corrupt payload into plausible garbage.
     */
    fun base64Codec(op: String, text: String): String {
        val verb = op.trim().lowercase()
        if (text.length > MAX_CODEC_CHARS) {
            return "Refusing: ${text.length} characters; the limit for one call is $MAX_CODEC_CHARS."
        }
        return when (verb) {
            "enc", "encode", "b64", "b64encode" -> {
                if (text.isEmpty()) return "Nothing to encode: the input is empty."
                val encoded = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
                "Base64 (UTF-8 in, ${text.toByteArray(Charsets.UTF_8).size} bytes -> ${encoded.length} chars):\n" + encoded
            }
            "dec", "decode", "b64decode" -> decodeBase64(text)
            else -> "Unknown operation '$op'. Use encode or decode."
        }
    }

    private fun decodeBase64(text: String): String {
        if (text.isEmpty()) return "Nothing to decode: the input is empty."
        for ((index, c) in text.withIndex()) {
            val ok = c.isLetterOrDigit() || c == '+' || c == '/' || c == '=' || c == '\n' || c == '\r' || c == ' '
            if (!ok) {
                return "Invalid Base64: character '" + c + "' at position " + (index + 1) +
                    " is not part of the alphabet. Nothing was decoded (no characters are skipped)."
            }
        }
        val compact = text.filter { it != '\n' && it != '\r' && it != ' ' }
        if (compact.length % 4 != 0) {
            return "Invalid Base64: length " + compact.length + " is not a multiple of 4 (padding is missing or the data is truncated)."
        }
        val bytes = try {
            Base64.getDecoder().decode(compact)
        } catch (e: IllegalArgumentException) {
            return "Invalid Base64: " + (e.message ?: "the decoder rejected it") + ". Nothing was decoded."
        }
        if (bytes.isEmpty()) return "Decoded 0 bytes: the input carried no data."
        val asText = String(bytes, Charsets.UTF_8)
        val printable = asText.none { it.code < 9 || (it.code in 14..31) || it.code == 127 }
        return if (printable) {
            "Decoded ${bytes.size} bytes (UTF-8 text):\n" + asText
        } else {
            "Decoded ${bytes.size} bytes; the content is BINARY, not text, so it is not shown inline.\n" +
                "First 64 bytes (hex): " + bytes.take(64).joinToString("") { "%02x".format(it) } +
                (if (bytes.size > 64) " …" else "")
        }
    }
}
