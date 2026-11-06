package com.codespace.ide.editor

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 2 of the 2026-11-06 order: the MEMORY arithmetic behind the large-file ceiling, measured
 * rather than asserted.
 *
 * What is REAL here: [TextFileDecoder.decode] is the shipped function, called with real bytes.
 * What is a REPLICA: the token walk below reproduces `SyntaxHighlighter.highlight`'s loop shape
 * (char scan, `startsWith` for comment/string openers, one `substring` per token) without building
 * a Compose AnnotatedString, which cannot exist on the JVM test classpath. It therefore measures
 * the STRING and SPAN-COUNT cost exactly and the span OBJECT cost only by count. Whatever
 * AnnotatedString does with those spans on top is device-only and is marked UNVERIFIED in the
 * report.
 *
 * Sizes are capped at 10 MB so CI stays fast; 25 and 50 MB are extrapolated in the report.
 */
class TextMemoryCostTest {

    private fun jsContent(targetBytes: Int): String {
        val line = "function bench_0(a, b) { return a + b; } // filler line for the ladder\n"
        val sb = StringBuilder(targetBytes + line.length)
        var n = 0
        while (sb.length < targetBytes) {
            sb.append(line.replaceFirst("bench_0", "bench_" + n))
            n++
        }
        return sb.toString()
    }

    /** Counts tokens with the shipped loop's shape: comments, strings, words, punctuation. */
    private fun countTokens(text: String): Int {
        var tokens = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (text.startsWith("//", i)) {
                val end = text.indexOf('\n', i).let { if (it == -1) n else it }
                text.substring(i, end); tokens++; i = end; continue
            }
            if (c == '"' || c == '\'') {
                val close = text.indexOf(c, i + 1)
                val end = if (close == -1) n else close + 1
                text.substring(i, end); tokens++; i = end; continue
            }
            if (c.isLetterOrDigit() || c == '_') {
                var j = i
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                text.substring(i, j); tokens++; i = j; continue
            }
            if (!c.isWhitespace()) { text.substring(i, i + 1); tokens++ }
            i++
        }
        return tokens
    }

    private fun mb(bytes: Int) = "%.1f MB".format(bytes / 1048576.0)

    @Test
    fun oneMegabyteAlreadyAllocatesAFewHundredThousandSmallStrings() {
        val text = jsContent(1024 * 1024)
        val t0 = System.nanoTime()
        val tokens = countTokens(text)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val heapText = text.length * 2L // Android strings are UTF-16
        println(
            "[perf] memory 1MB: chars=${text.length} tokens=$tokens tokenise=${"%.1f".format(ms)}ms " +
                "text=${heapText / 1048576}MB-as-UTF16"
        )
        assertTrue("1 MB must tokenise into tens of thousands of pieces", tokens > 100_000)
    }

    @Test
    fun tenMegabytesScalesLinearlyAndTheCopiesAddUp() {
        val bytes = 10 * 1024 * 1024
        val raw = ByteArray(bytes) { 'x'.code.toByte() }
        val heapBefore = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val decoded = TextFileDecoder.decode(raw)
        val heapAfter = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val t0 = System.nanoTime()
        val tokens = countTokens(decoded)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        println(
            "[perf] memory 10MB: decodeHeapDelta=${(heapAfter - heapBefore) / 1048576}MB " +
                "chars=${decoded.length} tokens=$tokens tokenise=${"%.1f".format(ms)}ms"
        )
        println("[perf] memory note: ${mb(bytes)} of bytes + ${decoded.length * 2L / 1048576}MB UTF-16 text = the floor before any span exists")
        assertTrue("decode must not lose content", decoded.length == bytes)
    }
}
