package com.codespace.ide.editor.textmate

import org.joni.Regex
import org.joni.Matcher
import org.joni.Option
import org.jcodings.specific.UTF8Encoding
import java.nio.charset.Charset

/**
 * Oniguruma regex wrapper using joni (pure Java Oniguruma implementation).
 *
 * Written from scratch. Architecture reference: org.eclipse.tm4e.core.internal.oniguruma
 * (EPL 2.0). Joni is MIT-licensed from the JRuby project.
 *
 * TextMate grammars use Oniguruma regex syntax — a superset of standard regex
 * with \G (anchor at match start), lookaheads, and other advanced features.
 * Joni provides a pure-Java implementation that works on Android without NDK.
 *
 * This version properly handles byte-to-char offset conversion for UTF-8.
 */
object OnigRegexFactory {

    private val encoding = UTF8Encoding.INSTANCE
    private val utf8: Charset = Charsets.UTF_8

    /**
     * Compile an Oniguruma regex pattern.
     * Returns null if the pattern cannot be compiled.
     */
    fun compile(pattern: String): Regex? {
        return try {
            val bytes = pattern.toByteArray(utf8)
            Regex(bytes, 0, bytes.size, Option.NONE, encoding)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Compile with case-insensitive flag.
     */
    fun compileIgnoreCase(pattern: String): Regex? {
        return try {
            val bytes = pattern.toByteArray(utf8)
            Regex(bytes, 0, bytes.size, Option.IGNORECASE, encoding)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Search for a pattern in text starting at a given position.
     * Returns a MatchResult if found, null if no match.
     *
     * Joni works on byte arrays, so we must convert char positions to byte
     * positions before searching and byte results back to char positions.
     */
    fun search(regex: Regex, text: String, startChar: Int): OnigMatchResult? {
        if (startChar >= text.length) return null
        val textBytes = text.toByteArray(utf8)

        // Convert char start to byte start
        var byteStart = 0
        for (i in 0 until startChar.coerceAtMost(text.length)) {
            val c = text[i]
            byteStart += if (c.code < 128) 1 else if (c.code < 2048) 2 else if (c.code < 65536) 3 else 4
        }

        if (byteStart > textBytes.size) return null

        val matcher = regex.matcher(textBytes, 0, textBytes.size)
        val result = matcher.search(byteStart, textBytes.size, Option.NONE)

        if (result >= 0) {
            val numGroups = matcher.getCaptureGroupCount() + 1
            val captures = Array(numGroups) { i ->
                val byteBegin = matcher.getBegin(i)
                val byteEnd = matcher.getEnd(i)
                OnigCaptureIndex(
                    byteToChar(text, textBytes, byteBegin),
                    byteToChar(text, textBytes, byteEnd)
                )
            }
            return OnigMatchResult(captures)
        }
        return null
    }

    /**
     * Convert a byte offset back to a character offset in the original string.
     */
    private fun byteToChar(text: String, textBytes: ByteArray, byteOffset: Int): Int {
        if (byteOffset < 0) return -1
        if (byteOffset == 0) return 0
        if (byteOffset >= textBytes.size) return text.length

        var charIdx = 0
        var byteIdx = 0
        while (byteIdx < byteOffset && charIdx < text.length) {
            val c = text[charIdx]
            val charBytes = if (c.code < 128) 1 else if (c.code < 2048) 2 else if (c.code < 65536) 3 else 4
            byteIdx += charBytes
            if (byteIdx <= byteOffset) charIdx++
        }
        return charIdx
    }
}

/** A capture group match result. */
data class OnigCaptureIndex(
    val start: Int,
    val end: Int,
)

/** A full match result with all capture groups. */
data class OnigMatchResult(
    val captures: Array<OnigCaptureIndex>,
) {
    /** The full match span (capture group 0). */
    val matchStart: Int get() = if (captures.isNotEmpty()) captures[0].start else -1
    val matchEnd: Int get() = if (captures.isNotEmpty()) captures[0].end else -1
    val length: Int get() = matchEnd - matchStart

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OnigMatchResult) return false
        return captures.contentEquals(other.captures)
    }

    override fun hashCode(): Int = captures.contentHashCode()
}
