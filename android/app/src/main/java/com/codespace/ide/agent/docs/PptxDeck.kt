package com.codespace.ide.agent.docs

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * PROJECT 2.3 (2026-11-15): a deck has an ORDER, and a slide has a TITLE.
 *
 * PPTX used to be every `ppt/slides/slide*.xml` concatenated in file-name order. Two things were
 * lost that matter when a deck is discussed: the order the author actually put the slides in (which
 * lives in `presentation.xml`'s `sldIdLst`, not in the file names), and which text is the slide's
 * title as opposed to its bullets.
 */
object PptxDeck {

    data class Slide(val number: Int, val title: String, val lines: List<String>, val notes: String)

    fun slides(bytes: ByteArray, maxSlides: Int = 40): List<Slide> {
        val entries = entriesOf(bytes) ?: return emptyList()
        val rels = relTargets(entries["ppt/_rels/presentation.xml.rels"])
        val order = slideOrder(entries["ppt/presentation.xml"], rels, entries)
        val out = ArrayList<Slide>()
        for ((index, path) in order.withIndex()) {
            if (out.size >= maxSlides) break
            val xml = entries[path] ?: continue
            val title = titleOf(xml)
            val lines = textRuns(xml).filter { it != title && it.isNotBlank() }
            val notesPath = relTargets(entries[notesRelsPath(path)])
                .values.firstOrNull { it.contains("notesSlide") }
            val notes = notesPath?.let { pathOf(it, "ppt/slides/" + path.substringAfterLast('/')) }
                ?.let { entries[it] }
                ?.let { notesText(it) }
                .orEmpty()
            out.add(Slide(number = index + 1, title = title, lines = lines, notes = notes))
        }
        return out
    }

    fun render(bytes: ByteArray, maxSlides: Int = 40): String {
        val slides = slides(bytes, maxSlides)
        if (slides.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append(slides.size).append(" slide(s), in presentation order")
        for (s in slides) {
            sb.append("\n\nSlide ").append(s.number)
            if (s.title.isNotBlank()) sb.append(" — ").append(s.title) else sb.append(" — (no title)")
            s.lines.forEach { line -> sb.append("\n  - ").append(line) }
            if (s.notes.isNotBlank()) sb.append("\n  notes: ").append(s.notes)
        }
        return sb.toString()
    }

    // ── internals ────────────────────────────────────────────────────────────

    /** Slide part paths in the order `presentation.xml` lists them. */
    private fun slideOrder(
        presentation: String?,
        rels: Map<String, String>,
        entries: Map<String, String>,
    ): List<String> {
        val ordered = ArrayList<String>()
        if (presentation != null) {
            val list = SLD_ID_LIST.find(presentation)?.value.orEmpty()
            for (m in SLD_ID.findAll(list)) {
                val target = rels[m.groupValues[1]] ?: continue
                ordered.add(pathOf(target, "ppt/presentation.xml"))
            }
        }
        if (ordered.isNotEmpty()) return ordered
        // No readable order: fall back to file-name order, and say so by simply being honest with
        // what exists rather than inventing an order.
        return entries.keys
            .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    }

    /** A title placeholder's text if present, otherwise the first non-empty text run. */
    private fun titleOf(xml: String): String {
        val shape = TITLE_SHAPE.find(xml)?.value
        if (shape != null) {
            val t = textRuns(shape).firstOrNull { it.isNotBlank() }
            if (t != null) return t
        }
        return textRuns(xml).firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun textRuns(xml: String): List<String> =
        TEXT.findAll(xml).map { unescape(it.groupValues[1]).trim() }.toList()

    private fun notesText(xml: String): String =
        textRuns(xml).filter { it.isNotBlank() && !it.matches(Regex("\\d+")) }.joinToString(" / ")

    private fun notesRelsPath(slidePath: String): String {
        val dir = slidePath.substringBeforeLast('/')
        val name = slidePath.substringAfterLast('/')
        return dir + "/_rels/" + name + ".rels"
    }

    /** Turn a relationship target into an entry path, relative to the part that declared it. */
    private fun pathOf(target: String, basePart: String): String {
        if (target.startsWith("ppt/")) return target
        if (target.startsWith("/")) return target.trimStart('/')
        val baseDir = basePart.substringBeforeLast('/', "")
        val parts = ArrayList<String>()
        (if (baseDir.isEmpty()) emptyList() else baseDir.split('/')).forEach { parts.add(it) }
        target.split('/').forEach { seg ->
            when (seg) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                ".", "" -> Unit
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun relTargets(xml: String?): Map<String, String> {
        if (xml == null) return emptyMap()
        return REL.findAll(xml).mapNotNull { m ->
            val id = m.groupValues[1]
            val target = m.groupValues[2]
            if (id.isBlank() || target.isBlank()) null else id to target
        }.toMap()
    }

    private fun entriesOf(bytes: ByteArray): Map<String, String>? = try {
        val map = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            var total = 0L
            while (e != null) {
                val wanted = e.name == "ppt/presentation.xml" ||
                    e.name == "ppt/_rels/presentation.xml.rels" ||
                    (e.name.startsWith("ppt/slides/") && e.name.endsWith(".xml")) ||
                    (e.name.startsWith("ppt/slides/_rels/") && e.name.endsWith(".rels")) ||
                    (e.name.startsWith("ppt/notesSlides/") && e.name.endsWith(".xml"))
                if (wanted) {
                    val data = zip.readBytes()
                    total += data.size
                    if (total <= MAX_UNZIPPED_BYTES) map[e.name] = data.toString(Charsets.UTF_8)
                }
                e = zip.nextEntry
            }
        }
        map
    } catch (_: Throwable) {
        null
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private const val MAX_UNZIPPED_BYTES = 24L * 1024L * 1024L

    private val SLD_ID_LIST = Regex("<p:sldIdLst>(.*?)</p:sldIdLst>", RegexOption.DOT_MATCHES_ALL)
    private val SLD_ID = Regex("<p:sldId[^>]*\\br:id=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
    private val REL = Regex(
        "<Relationship[^>]*\\bId=\"([^\"]+)\"[^>]*\\bTarget=\"([^\"]+)\"",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val TEXT = Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL)

    /** A shape whose placeholder is the slide title. */
    private val TITLE_SHAPE = Regex(
        "<p:sp>.*?<p:ph[^>]*type=\"(?:title|ctrTitle)\".*?</p:sp>",
        RegexOption.DOT_MATCHES_ALL,
    )
}
