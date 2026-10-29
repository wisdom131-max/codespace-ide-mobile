package com.codespace.ide.chat.ui

/**
 * BATCH 7 — MARKDOWN (owner mission part 7).
 *
 * Assistant replies are Markdown: the models write `**bold**`, lists and fenced code
 * blocks, and the panel was printing all of it raw, backticks and all. This is the parse
 * ONLY - a pure function of the text - so the interesting cases (an unclosed fence while
 * streaming, code that must survive byte-for-byte, a lone asterisk that is not emphasis)
 * are asserted in JVM tests instead of eyeballed on a phone.
 *
 * Design notes that matter:
 *  - NO AST LIBRARY. The transcript re-parses the growing text of the message being
 *    streamed, so the cost has to be linear and allocation-light; a full CommonMark parser
 *    is neither, and this renderer draws a chat bubble, not a document.
 *  - AN UNCLOSED FENCE IS A CODE BLOCK, not an error: that is the normal state of a reply
 *    arriving a few characters at a time, and it must render as code immediately.
 *  - CODE CONTENT IS NEVER REWRITTEN. No trimming, no tab expansion - what the model sent
 *    is what Copy puts on the clipboard and Insert puts in the editor.
 */
internal data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
)

internal sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Bullet(val indent: Int, val spans: List<MdSpan>) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    /** [closed] is false while a fence is still open (streaming); the UI may say so. */
    data class Code(val language: String, val code: String, val closed: Boolean) : MdBlock
}

internal object MarkdownParser {

    fun parse(markdown: String): List<MdBlock> {
        if (markdown.isEmpty()) return emptyList()
        val lines = markdown.split('\n')
        val blocks = ArrayList<MdBlock>()
        var i = 0
        val para = ArrayList<String>()

        fun flushParagraph() {
            if (para.isEmpty()) return
            val joined = para.joinToString(" ").trim()
            para.clear()
            if (joined.isNotEmpty()) blocks.add(MdBlock.Paragraph(parseInline(joined)))
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()

            // Fenced code, including the still-open case.
            if (trimmed.startsWith("```")) {
                flushParagraph()
                // The closing fence must have AT LEAST as many backticks as the opener, so a
                // ``` example nested inside a ```` block does not end it early.
                val fence = trimmed.takeWhile { it == '`' }.length
                val language = trimmed.drop(fence).trim()
                i++
                val code = StringBuilder()
                var closed = false
                while (i < lines.size) {
                    val body = lines[i]
                    val bodyTrim = body.trimStart()
                    val bodyFence = bodyTrim.takeWhile { it == '`' }.length
                    if (bodyFence >= fence && bodyTrim.drop(bodyFence).isBlank()) {
                        closed = true; i++; break
                    }
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(body)
                    i++
                }
                blocks.add(MdBlock.Code(language, code.toString(), closed))
                continue
            }

            if (trimmed.isBlank()) { flushParagraph(); i++; continue }

            val heading = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
            if (heading != null) {
                flushParagraph()
                blocks.add(MdBlock.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2].trim())))
                i++
                continue
            }

            if (trimmed.startsWith(">")) {
                flushParagraph()
                blocks.add(MdBlock.Quote(parseInline(trimmed.removePrefix(">").trim())))
                i++
                continue
            }

            val bullet = Regex("^([-*+])\\s+(.*)$").find(trimmed)
            if (bullet != null) {
                flushParagraph()
                val indent = (line.length - trimmed.length) / 2
                blocks.add(MdBlock.Bullet(indent, parseInline(bullet.groupValues[2].trim())))
                i++
                continue
            }

            para.add(trimmed)
            i++
        }
        flushParagraph()
        return blocks
    }

    /**
     * Inline emphasis with MARKER FLANKING, which is the rule that keeps ordinary text safe:
     * an opener must be followed by non-space and a closer preceded by non-space. Without it
     * "2 * 3 = 6" reads as an italic run and the asterisk silently disappears from the user's
     * message - and half-typed markers arrive constantly while a reply streams.
     */
    fun parseInline(text: String): List<MdSpan> {
        val spans = ArrayList<MdSpan>()
        val buf = StringBuilder()
        var i = 0
        fun flush() {
            if (buf.isEmpty()) return
            spans.add(MdSpan(buf.toString()))
            buf.setLength(0)
        }
        while (i < text.length) {
            val ch = text[i]
            if (ch == '`') {
                val end = text.indexOf('`', i + 1)
                if (end < 0) { buf.append(ch); i++ } else {
                    flush()
                    val inner = text.substring(i + 1, end)
                    if (inner.isNotEmpty()) spans.add(MdSpan(inner, code = true))
                    i = end + 1
                }
            } else if (ch == '*' && i + 1 < text.length && text[i + 1] == '*') {
                val inner = if (canOpen(text, i + 2, 2)) text.substringOrNull(i + 2, findClose(text, i + 2, 2)) else null
                if (inner == null) { buf.append("**"); i += 2 } else {
                    flush()
                    for (sp in parseInline(inner)) spans.add(sp.copy(bold = true))
                    i = findClose(text, i + 2, 2)
                }
            } else if (ch == '*') {
                val inner = if (canOpen(text, i + 1, 1)) text.substringOrNull(i + 1, findClose(text, i + 1, 1)) else null
                if (inner == null) { buf.append(ch); i++ } else {
                    flush()
                    for (sp in parseInline(inner)) spans.add(sp.copy(italic = true))
                    i = findClose(text, i + 1, 1)
                }
            } else {
                buf.append(ch); i++
            }
        }
        flush()
        return spans
    }

    /** The opener must be followed by a non-space. */
    private fun canOpen(text: String, at: Int, marks: Int): Boolean =
        at < text.length && !text[at].isWhitespace() && text.length > at + marks

    /** First closer whose preceding char is non-space, else -1. */
    private fun findClose(text: String, from: Int, marks: Int): Int {
        val marker = "*".repeat(marks)
        var j = text.indexOf(marker, from)
        while (j > from) {
            if (!text[j - 1].isWhitespace()) return j
            j = text.indexOf(marker, j + marks)
        }
        return -1
    }

    private fun String.substringOrNull(from: Int, to: Int): String? =
        if (to > from && to <= length) substring(from, to) else null

    /** Plain text of a block list - used for tests and for a Copy of a whole reply. */
    fun flatten(blocks: List<MdBlock>): String = blocks.joinToString("\n") { b ->
        when (b) {
            is MdBlock.Paragraph -> inlineText(b.spans)
            is MdBlock.Heading -> inlineText(b.spans)
            is MdBlock.Bullet -> "- " + inlineText(b.spans)
            is MdBlock.Quote -> "> " + inlineText(b.spans)
            is MdBlock.Code -> b.code
        }
    }

    private fun inlineText(spans: List<MdSpan>): String = spans.joinToString("") { it.text }
}
