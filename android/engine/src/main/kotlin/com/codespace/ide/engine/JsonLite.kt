package com.codespace.ide.engine

/**
 * STAGE 5.2: the smallest JSON reader the engine needs, hand-rolled for the same reason
 * [SseParser] is — this module is deliberately dependency-free ("NO Android dependencies"), and an
 * `org.json` jar here would collide with Android's own `org.json` at dex time.
 *
 * Values are plain Kotlin: `Map<String, Any?>` (JSONObject), `List<Any?>` (JSONArray), `String`,
 * `Double`, `Boolean`, or null. Malformed input returns null rather than throwing, because a broken
 * provider payload is data to report, not a crash to survive.
 */
object JsonLite {

    fun parse(text: String): Any? {
        val p = Cursor(text)
        return try {
            p.ws()
            val v = p.value()
            p.ws()
            if (p.i < text.length) null else v
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(v: Any?): List<Any?>? = v as? List<Any?>

    fun str(v: Any?): String = v as? String ?: ""

    fun int(v: Any?, default: Int = 0): Int = when (v) {
        is Double -> v.toInt()
        is String -> v.toIntOrNull() ?: default
        else -> default
    }

    fun bool(v: Any?, default: Boolean = false): Boolean = v as? Boolean ?: default

    /** Serialises a parsed value back to compact JSON (used for tool arguments). */
    fun write(v: Any?): String = buildString { writeTo(this, v) }

    private fun writeTo(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Double -> sb.append(if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    writeTo(sb, value)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, value ->
                    if (i > 0) sb.append(',')
                    writeTo(sb, value)
                }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Cursor(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            if (i >= s.length) throw IllegalStateException("eof")
            return when (s[i]) {
                '{' -> obj()
                '[' -> list()
                '"' -> string()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> number()
            }
        }

        private fun expect(word: String) {
            if (!s.startsWith(word, i)) throw IllegalStateException("expected $word")
            i += word.length
        }

        private fun obj(): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (s[i] != '"') throw IllegalStateException("key")
                val k = string()
                ws()
                if (s[i] != ':') throw IllegalStateException(":")
                i++
                out[k] = value()
                ws()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalStateException("obj")
                }
            }
        }

        private fun list(): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                ws()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalStateException("arr")
                }
            }
        }

        private fun string(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalStateException("eof in string")
                when (val c = s[i]) {
                    '\\' -> {
                        i++
                        when (val e = s[i]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                        i++
                    }
                    '"' -> { i++; return sb.toString() }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }
}
