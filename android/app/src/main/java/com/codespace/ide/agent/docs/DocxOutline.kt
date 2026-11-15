package com.codespace.ide.agent.docs

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * PROJECT 2.1 (2026-11-15): a Word document's STRUCTURE, not just its words.
 *
 * The extractor flattened `word/document.xml` to text, which is enough to answer "what does it say"
 * and useless for "how is it organised": heading levels, the outline, what sits under what. The
 * prompt asks Word support to understand headings and structure, so the style each paragraph
 * declares is read here and rendered as an outline the model can reason about.
 *
 * Real Word files put the level in `w:pStyle` (`Heading1`..`Heading9`, `Title`) or, when a heading
 * style was applied through numbering, in an outline level on the paragraph properties. Both are
 * read; anything else is body text and is not part of the outline.
 */
object DocxOutline {

    data class Line(val level: Int, val text: String)

    /** Returns the headings in document order. Empty when the file has no headings at all. */
    fun outline(bytes: ByteArray): List<Line> {
        val xml = documentXml(bytes) ?: return emptyList()
        val lines = ArrayList<Line>()
        for (p in paragraphRuns(xml)) {
            val text = textOf(p).trim()
            if (text.isEmpty()) continue
            val level = headingLevel(p) ?: continue
            lines.add(Line(level, text))
        }
        return lines
    }

    /** "H1: Introduction" style lines, indented one step per level. Empty string when no headings. */
    fun render(bytes: ByteArray): String {
        val lines = outline(bytes)
        if (lines.isEmpty()) return ""
        return lines.joinToString("\n") { l ->
            "  ".repeat((l.level - 1).coerceIn(0, 8)) + "H" + l.level + ": " + l.text
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun headingLevel(paragraph: String): Int? {
        STYLE.find(paragraph)?.let { m ->
            val style = m.groupValues[1].lowercase().replace(" ", "")
            val numbered = Regex("^heading([1-9])$").find(style)
            if (numbered != null) return numbered.groupValues[1].toInt()
            if (style == "title") return 1
            if (style == "subtitle") return 2
        }
        return OUTLINE_LEVEL.find(paragraph)?.let { m ->
            // w:outlineLvl is 0-based; a valid heading outline level is 0..8, so level = +1.
            (m.groupValues[1].toIntOrNull()?.plus(1))?.takeIf { it in 1..9 }
        }
    }

    private fun documentXml(bytes: ByteArray): String? = try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var found: String? = null
            var entry = zip.nextEntry
            while (entry != null && found == null) {
                if (entry.name == "word/document.xml") {
                    found = zip.readBytes().toString(Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
            found
        }
    } catch (_: Throwable) {
        null
    }

    private fun paragraphRuns(xml: String): List<String> =
        PARAGRAPH.findAll(xml).map { it.value }.toList()

    private fun textOf(paragraph: String): String {
        val sb = StringBuilder()
        for (m in TEXT.findAll(paragraph)) sb.append(m.groupValues[1])
        return sb.toString()
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
    }

    private val PARAGRAPH = Regex("<w:p[ >].*?</w:p>", RegexOption.DOT_MATCHES_ALL)
    private val STYLE = Regex("<w:pStyle[^>]*w:val=\"([^\"]+)\"")
    private val OUTLINE_LEVEL = Regex("<w:outlineLvl[^>]*w:val=\"(\\d)\"")
    private val TEXT = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
}
