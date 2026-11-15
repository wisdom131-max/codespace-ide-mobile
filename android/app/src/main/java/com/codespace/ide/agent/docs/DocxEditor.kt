package com.codespace.ide.agent.docs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * PROJECT 2.5 (2026-11-15): an existing Word document can be EDITED.
 *
 * "Create" was not enough: the usual request is to add a section to a report that already exists,
 * and the only way to do that before this was to regenerate the whole file and throw away every
 * part the writer does not understand (images, headers, custom styles, comments).
 *
 * So an edit touches ONE part. `word/document.xml` gains the new paragraphs before the section
 * properties, and every other entry in the package is copied through byte for byte. What this does
 * not do is honest: it appends, it does not rewrite paragraphs in place, so "preserve formatting" is
 * true of everything it does not touch rather than of the new text, which is written in this app's
 * own styles.
 */
object DocxEditor {

    /** Appends [blocks] to the end of the document body, before the section properties. */
    fun append(bytes: ByteArray, blocks: List<DocxWriter.Block>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            ZipOutputStream(out).use { target ->
                var e: ZipEntry? = zip.nextEntry
                while (e != null) {
                    val name = e.name
                    val data = zip.readBytes()
                    target.putNextEntry(ZipEntry(name))
                    if (name == "word/document.xml") {
                        target.write(insert(data.toString(Charsets.UTF_8), blocks).toByteArray(Charsets.UTF_8))
                    } else {
                        target.write(data)
                    }
                    target.closeEntry()
                    e = zip.nextEntry
                }
            }
        }
        return out.toByteArray()
    }

    /** True when the package actually holds a Word document part. */
    fun isDocx(bytes: ByteArray): Boolean = try {
        var found = false
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") found = true
                e = zip.nextEntry
            }
        }
        found
    } catch (_: Throwable) {
        false
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun insert(xml: String, blocks: List<DocxWriter.Block>): String {
        val added = blocks.joinToString("") { paragraphOf(it) }
        val sect = xml.lastIndexOf("<w:sectPr")
        return if (sect > 0) {
            xml.substring(0, sect) + added + xml.substring(sect)
        } else {
            val bodyEnd = xml.lastIndexOf("</w:body>")
            if (bodyEnd < 0) xml else xml.substring(0, bodyEnd) + added + xml.substring(bodyEnd)
        }
    }

    private fun paragraphOf(block: DocxWriter.Block): String {
        val style = when (block) {
            is DocxWriter.Block.Heading -> "Heading" + block.level.coerceIn(1, 3)
            is DocxWriter.Block.Bullet -> "ListParagraph"
            is DocxWriter.Block.Paragraph -> null
        }
        val text = when (block) {
            is DocxWriter.Block.Heading -> block.text
            is DocxWriter.Block.Bullet -> "• " + block.text
            is DocxWriter.Block.Paragraph -> block.text
        }
        val pPr = if (style == null) "" else "<w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>"
        val runs = text.split('\n').joinToString("") { line ->
            "<w:r><w:t xml:space=\"preserve\">" + escape(line) + "</w:t></w:r>"
        }
        return "<w:p>" + pPr + runs + "</w:p>"
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
