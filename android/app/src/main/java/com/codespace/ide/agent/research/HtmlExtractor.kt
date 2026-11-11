package com.codespace.ide.agent.research

/**
 * R1 (owner approval 2026-11-11): HTML to something a model can actually read.
 *
 * WHY THIS EXISTS: web_fetch used to return the RAW body cut at 8,000 characters, so a research
 * answer was built out of markup, script tags and CSS. The agent then reported it could not find
 * the information. Extraction is the difference between "a pipe for bytes" and "reading a page".
 *
 * Pure Kotlin on purpose: no Android, no Jsoup, so every rule below is asserted by a JVM test with
 * fixtures instead of discovered on a device.
 */
object HtmlExtractor {

    data class Link(val text: String, val url: String)

    data class Page(
        val title: String,
        val headings: List<String>,
        val paragraphs: List<String>,
        val links: List<Link>,
        /** The rendered, bounded text handed to the model. */
        val text: String,
        val originalChars: Int,
        val truncated: Boolean,
    )

    /** Tags whose CONTENT is never readable text. */
    private val DROP_BLOCKS = listOf(
        "script", "style", "noscript", "svg", "canvas", "iframe", "object", "embed",
        "form", "nav", "footer", "aside", "template", "head",
    )

    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "main", "header", "br", "li", "tr", "td", "th",
        "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "dd", "dt", "figcaption",
    )

    fun extract(html: String, baseUrl: String? = null, maxChars: Int = 6000): Page {
        val original = html.length
        var s = html
        // Headings are read BEFORE the drops so the title/outline survives nav stripping.
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(s)?.groupValues?.get(1)
            ?.let { cleanInline(it) }?.takeIf { it.isNotEmpty() }
            ?: Regex("(?is)<h1[^>]*>(.*?)</h1>").find(s)?.groupValues?.get(1)
                ?.let { cleanInline(it) }?.takeIf { it.isNotEmpty() } ?: ""

        val headings = Regex("(?is)<h([1-3])[^>]*>(.*?)</h\\1>").findAll(s)
            .map { cleanInline(it.groupValues[2]) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(20)
            .toList()

        for (tag in DROP_BLOCKS) {
            s = s.replace(Regex("(?is)<$tag\\b[^>]*>.*?</$tag>"), " ")
            s = s.replace(Regex("(?is)<$tag\\b[^>]*/?>"), " ")
        }
        val metaDescription = Regex("(?is)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"'](.*?)[\"']")
            .find(html)?.groupValues?.get(1)?.let { cleanInline(it) } ?: ""

        val links = Regex("(?is)<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>").findAll(s)
            .mapNotNull { m ->
                val href = Entities.decode(m.groupValues[1]).trim()
                val text = cleanInline(m.groupValues[2])
                val abs = UrlTools.absolute(href, baseUrl) ?: return@mapNotNull null
                if (text.isEmpty() || abs.startsWith("javascript:", true)) null else Link(text, abs)
            }
            .distinctBy { it.url }
            .take(40)
            .toList()

        // Block tags become newlines so paragraphs survive; everything else is inline.
        s = Regex("(?is)<br\\s*/?>").replace(s, "\n")
        s = Regex("(?is)</(p|div|section|article|li|tr|h[1-6]|blockquote|pre)>").replace(s, "\n")
        s = Regex("(?is)<li\\b[^>]*>").replace(s, "\u2022 ")
        s = Regex("(?is)<[^>]+>").replace(s, " ")
        s = Entities.decode(s)

        val paragraphs = s.split(Regex("\\n{1,}"))
            .map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
            .filter { it.length >= 25 }
            .distinct()
            .toList()

        val head = buildString {
            if (title.isNotEmpty()) append("# ").append(title).append('\n')
            if (headings.isNotEmpty()) {
                append(headings.joinToString(" | ")).append('\n')
            }
            if (metaDescription.isNotEmpty() && paragraphs.none { it.startsWith(metaDescription.take(60)) }) {
                append(metaDescription).append('\n')
            }
        }
        val body = paragraphs.joinToString("\n\n")
        val full = (head + body).trim()
        val bounded = if (full.length <= maxChars) full else full.take(maxChars)
        return Page(
            title = title,
            headings = headings,
            paragraphs = paragraphs,
            links = links,
            text = bounded,
            originalChars = original,
            truncated = full.length > maxChars,
        )
    }

    /** One-line text from an HTML fragment, entities decoded, tags gone. */
    fun cleanInline(fragment: String): String =
        Entities.decode(Regex("(?is)<[^>]+>").replace(fragment, " "))
            .replace(Regex("\\s+"), " ")
            .trim()
}
