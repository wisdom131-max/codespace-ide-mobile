package com.codespace.ide.editor.textmate

import org.joni.Regex
import org.joni.Option
import org.joni.Region
import org.jcodings.specific.UTF8Encoding
import java.nio.charset.Charset

/**
 * Oniguruma regex wrapper using joni (pure Java Oniguruma implementation).
 * Joni is MIT-licensed from the JRuby project.
 */
object OnigRegexFactory {

    private val encoding = UTF8Encoding.INSTANCE
    private val utf8: Charset = Charsets.UTF_8

    fun compile(pattern: String): Regex? {
        return try {
            val bytes = pattern.toByteArray(utf8)
            Regex(bytes, 0, bytes.size, Option.NONE, encoding)
        } catch (e: Exception) {
            null
        }
    }

    fun compileIgnoreCase(pattern: String): Regex? {
        return try {
            val bytes = pattern.toByteArray(utf8)
            Regex(bytes, 0, bytes.size, Option.IGNORECASE, encoding)
        } catch (e: Exception) {
            null
        }
    }

    fun search(regex: Regex, text: String, startChar: Int): OnigMatchResult? {
        if (startChar >= text.length) return null
        val textBytes = text.toByteArray(utf8)

        var byteStart = 0
        for (i in 0 until startChar.coerceAtMost(text.length)) {
            val c = text[i]
            byteStart += if (c.code < 128) 1 else if (c.code < 2048) 2 else if (c.code < 65536) 3 else 4
        }

        if (byteStart > textBytes.size) return null

        val matcher = regex.matcher(textBytes, 0, textBytes.size)
        val result = matcher.search(byteStart, textBytes.size, Option.NONE)

        if (result >= 0) {
            // joni API: getEagerRegion() returns a Region with getBeg(i)/getEnd(i)
            val region: Region = matcher.getEagerRegion()
            val numRegs = region.numRegs
            val captures = Array(numRegs) { i ->
                OnigCaptureIndex(
                    if (region.getBeg(i) >= 0) byteToChar(text, textBytes, region.getBeg(i)) else -1,
                    if (region.getEnd(i) >= 0) byteToChar(text, textBytes, region.getEnd(i)) else -1
                )
            }
            return OnigMatchResult(captures)
        }
        return null
    }

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

data class OnigCaptureIndex(
    val start: Int,
    val end: Int,
)

data class OnigMatchResult(
    val captures: Array<OnigCaptureIndex>,
) {
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
