package com.codespace.ide.agent.docs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PROJECT 2.4 (2026-11-15): Word documents can be CREATED, not only read.
 *
 * The brief lists "create documents" under Word, and Copilot could only write plain text files. A
 * `.docx` is a ZIP of XML parts, so producing one is ordinary work: the three parts Word needs to
 * open a file ([Content_Types].xml, _rels/.rels, word/document.xml), plus a styles part that gives
 * Heading 1..3 and Title real style ids. Without those ids a heading is just bold text to Word, and
 * the reader in this app (DocxOutline) could not see the structure back.
 *
 * The writer is deliberately small and honest: it produces a document whose structure round-trips
 * through our own reader. It does not attempt tables, images, footnotes or custom styles.
 */
object DocxWriter {

    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Paragraph(val text: String) : Block()
        data class Bullet(val text: String) : Block()
    }

    /** Markdown-ish source: `#`-`###` headings, `-` bullets, blank line = paragraph break. */
    fun blocksOf(source: String): List<Block> {
        val blocks = ArrayList<Block>()
        for (raw in source.lines()) {
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val hashes = trimmed.takeWhile { it == '#' }.length
            when {
                hashes in 1..3 && trimmed.length > hashes -> blocks.add(
                    Block.Heading(hashes, trimmed.substring(hashes).trim()),
                )
                trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
                    blocks.add(Block.Bullet(trimmed.substring(2).trim()))
                else -> blocks.add(Block.Paragraph(trimmed))
            }
        }
        return blocks
    }

    fun build(title: String, blocks: List<Block>): ByteArray {
        val body = StringBuilder()
        if (title.isNotBlank()) {
            body.append(paragraph(style = "Title", text = title, bold = false))
        }
        blocks.forEach { b ->
            when (b) {
                is Block.Heading -> body.append(
                    paragraph(style = "Heading" + b.level.coerceIn(1, 3), text = b.text, bold = true),
                )
                is Block.Paragraph -> body.append(paragraph(style = null, text = b.text, bold = false))
                is Block.Bullet -> body.append(paragraph(style = "ListParagraph", text = "• " + b.text, bold = false))
            }
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""" +
            body + """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/></w:sectPr></w:body></w:document>"""

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put("word/_rels/document.xml.rels", DOC_RELS)
            put("word/styles.xml", STYLES)
            put("word/document.xml", document)
        }
        return out.toByteArray()
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun paragraph(style: String?, text: String, bold: Boolean): String {
        val pPr = if (style == null) "" else "<w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>"
        val rPr = if (bold) "<w:rPr><w:b/></w:rPr>" else ""
        val runs = text.split('\n').joinToString("") { line ->
            "<w:r>" + rPr + "<w:t xml:space=\"preserve\">" + escape(line) + "</w:t></w:r>"
        }
        return "<w:p>" + pPr + runs + "</w:p>"
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private const val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:style w:type="paragraph" w:styleId="Normal" w:default="1"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/>
<w:pPr><w:spacing w:before="240" w:after="120"/></w:pPr><w:rPr><w:b/><w:sz w:val="48"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>
<w:pPr><w:outlineLvl w:val="0"/><w:spacing w:before="240" w:after="120"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/>
<w:pPr><w:outlineLvl w:val="1"/><w:spacing w:before="200" w:after="100"/></w:pPr><w:rPr><w:b/><w:sz w:val="28"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/>
<w:pPr><w:outlineLvl w:val="2"/><w:spacing w:before="160" w:after="80"/></w:pPr><w:rPr><w:b/><w:sz w:val="26"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/></w:style>
</w:styles>"""
}
