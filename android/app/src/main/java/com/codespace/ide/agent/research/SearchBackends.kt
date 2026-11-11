package com.codespace.ide.agent.research

/**
 * R2 (owner approval 2026-11-11): a search RESULT, not a list of bare links.
 *
 * The old web_search returned five "title - url" lines with no snippet, so the model had nothing to
 * judge a result by and jumped to a guess or gave up. Every field here exists because an agent needs
 * it to decide what to open next.
 */
data class WebResult(
    val title: String,
    val url: String,
    val domain: String,
    val snippet: String = "",
    val date: String = "",
)

object ResultParser {

    /** html.duckduckgo.com/html/ — result__a anchors inside result__body blocks. */
    fun duckDuckGoHtml(html: String): List<WebResult> {
        val out = ArrayList<WebResult>()
        // Slice on the marker instead of matching nested </div>s: real DuckDuckGo responses close
        // these blocks inconsistently, and requiring exact nesting silently lost every snippet.
        val blocks = ArrayList<String>()
        val marks = Regex("(?is)<(?:div|table|tr)[^>]*class=\"[^\"]*result__body[^\"]*\"").findAll(html)
            .map { it.range.first }.toList()
        marks.forEachIndexed { i, start ->
            val end = if (i + 1 < marks.size) marks[i + 1] else minOf(html.length, start + 4000)
            blocks.add(html.substring(start, end))
        }
        for (b in blocks) {
            out.add(parseDDGBlock(b))
        }
        if (out.isEmpty()) {
            // Some responses carry the anchors without the wrapper divs.
            Regex("(?is)<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
                .findAll(html).forEach { m ->
                    val url = UrlTools.unwrapRedirect(Entities.decode(m.groupValues[1]))
                    val title = HtmlExtractor.cleanInline(m.groupValues[2])
                    if (title.isNotEmpty() && url.startsWith("http")) {
                        out.add(WebResult(title, url, UrlTools.domain(url)))
                    } else if (title.isNotEmpty()) {
                        out.add(WebResult(title, url, UrlTools.domain(url)))
                    }
                }
        }
        return out
    }

    private fun parseDDGBlock(block: String): WebResult {
        val anchor = Regex("(?is)<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
            .find(block)
        val rawHref = anchor?.groupValues?.get(1)?.let { Entities.decode(it) } ?: ""
        val url = UrlTools.unwrapRedirect(rawHref)
        val title = anchor?.groupValues?.get(2)?.let { HtmlExtractor.cleanInline(it) }.orEmpty()
        val snippetHtml = Regex("(?is)<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>")
            .find(block)?.groupValues?.get(1)
            ?: Regex("(?is)<div[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</div>")
                .find(block)?.groupValues?.get(1)
        val snippet = HtmlExtractor.cleanInline(snippetHtml.orEmpty())
        val date = Regex("(?is)class=\"[^\"]*result__timestamp[^\"]*\"[^>]*>(.*?)</")
            .find(block)?.groupValues?.get(1)?.let { HtmlExtractor.cleanInline(it) }.orEmpty()
        return WebResult(title, url, UrlTools.domain(url), snippet, date)
    }

    /** Bing's public results page. Class names change; both shapes are parsed. */
    fun bingHtml(html: String): List<WebResult> {
        val out = ArrayList<WebResult>()
        Regex("(?is)<li[^>]*class=\"[^\"]*b_algo[^\"]*\"[^>]*>(.*?)</li>").findAll(html).forEach { m ->
            val block = m.groupValues[1]
            val a = Regex("(?is)<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").find(block)
            val url = a?.groupValues?.get(1)?.let { Entities.decode(it) }.orEmpty()
            val title = a?.groupValues?.get(2)?.let { HtmlExtractor.cleanInline(it) }.orEmpty()
            val snippet = Regex("(?is)<p[^>]*>(.*?)</p>").find(block)
                ?.groupValues?.get(1)?.let { HtmlExtractor.cleanInline(it) }.orEmpty()
            if (title.isNotEmpty() && url.startsWith("http")) {
                out.add(WebResult(title, url, UrlTools.domain(url), snippet))
            }
        }
        return out
    }

    /** lite.duckduckgo.com — a table of plain links, the most robust of the three. */
    fun duckDuckGoLite(html: String): List<WebResult> {
        val out = ArrayList<WebResult>()
        Regex("(?is)<a[^>]*class=\"result-link\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").findAll(html)
            .forEach { m ->
                val url = UrlTools.unwrapRedirect(Entities.decode(m.groupValues[1]))
                val title = HtmlExtractor.cleanInline(m.groupValues[2])
                if (title.isNotEmpty()) out.add(WebResult(title, url, UrlTools.domain(url)))
            }
        return out
    }

    fun looksLikeChallenge(html: String): Boolean {
        val h = html.lowercase()
        return listOf(
            "unusual traffic", "are you a robot", "captcha", "verify you are human",
            "automated queries", "anomaly-modal", "challenge-form",
        ).any { it in h }
    }
}

object ResultQuality {

    private val JUNK_DOMAINS = setOf(
        "duckduckgo.com", "bing.com", "google.com", "doubleclick.net", "googlesyndication.com",
    )

    /**
     * Clean, dedupe, bound. Order is preserved: a search engine's own ranking is evidence, and
     * re-ranking it locally would hide the fact that we are reading someone else's judgement.
     */
    fun prepare(results: List<WebResult>, limit: Int = 8): List<WebResult> {
        val seenUrls = HashSet<String>()
        val out = ArrayList<WebResult>(limit)
        for (r in results) {
            val title = r.title.trim()
            if (title.isEmpty()) continue
            if (r.url.isBlank() || !r.url.startsWith("http")) continue
            if (r.domain in JUNK_DOMAINS) continue
            val key = UrlTools.normalize(r.url)
            if (!seenUrls.add(key)) continue
            out.add(
                r.copy(
                    title = title,
                    snippet = r.snippet.replace(Regex("\\s+"), " ").trim().take(300),
                    url = stripTracking(r.url),
                )
            )
            if (out.size >= limit) break
        }
        return out
    }

    private fun stripTracking(url: String): String =
        if (url.contains("utm_") || url.contains("#")) UrlTools.normalize(url) else url

    /** The model-facing block. Bounded hard: 8 results never exceed ~1.6k characters. */
    fun render(results: List<WebResult>, attempts: List<String> = emptyList()): String {
        if (results.isEmpty()) {
            val tried = if (attempts.isEmpty()) "" else "\nTried: " + attempts.joinToString("; ")
            return "No usable results.$tried\n" +
                "Do not ask the user for a URL: reformulate the query or fetch a known site directly."
        }
        val sb = StringBuilder(results.size.toString() + " results\n")
        results.forEachIndexed { i, r ->
            sb.append(i + 1).append(". ").append(r.title).append(" \u2014 ").append(r.domain)
            if (r.date.isNotEmpty()) sb.append(" \u00b7 ").append(r.date)
            sb.append('\n')
            if (r.snippet.isNotEmpty()) sb.append("   ").append(r.snippet).append('\n')
            sb.append("   ").append(r.url).append('\n')
        }
        if (attempts.isNotEmpty()) sb.append("Tried: ").append(attempts.joinToString("; ")).append('\n')
        return sb.toString().trim()
    }
}
