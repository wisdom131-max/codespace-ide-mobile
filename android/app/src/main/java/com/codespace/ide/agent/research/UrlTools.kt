package com.codespace.ide.agent.research

/** URL and entity plumbing shared by search parsing, extraction and the site adapters. */
object UrlTools {

    /** Resolve a possibly-relative href against a page URL. Null when it cannot be made absolute. */
    fun absolute(href: String, baseUrl: String?): String? {
        var h = href.trim()
        if (h.isEmpty() || h.startsWith("#") || h.startsWith("javascript:", true)) return null
        if (h.startsWith("//")) return (schemeOf(baseUrl) ?: "https:") + h
        if (h.startsWith("http://") || h.startsWith("https://")) return h
        val base = baseUrl ?: return null
        return try {
            java.net.URI(base).resolve(h).toString()
        } catch (_: Exception) {
            null
        }
    }

    fun schemeOf(url: String?): String? = when {
        url == null -> null
        url.startsWith("https://") -> "https:"
        url.startsWith("http://") -> "http:"
        else -> null
    }

    /** Host without "www.", lowercased. Used for the domain column and dedupe. */
    fun domain(url: String): String = try {
        val host = java.net.URI(url).host ?: ""
        host.removePrefix("www.").lowercase()
    } catch (_: Exception) {
        ""
    }

    /** Stable identity for dedupe: scheme+host+path+query, minus fragments and tracking params. */
    fun normalize(url: String): String {
        val noFragment = url.substringBefore('#')
        val uri = try { java.net.URI(noFragment) } catch (_: Exception) { return noFragment }
        val kept = (uri.rawQuery ?: "").split('&')
            .filter { it.isNotBlank() && !it.startsWith("utm_") && !it.startsWith("ref=") }
            .joinToString("&")
        val path = uri.path.orEmpty().trimEnd('/')
        val scheme = uri.scheme ?: "https"
        val host = (uri.host ?: "").removePrefix("www.").lowercase()
        return scheme + "://" + host + path + (if (kept.isNotEmpty()) "?" + kept else "")
    }

    /** DuckDuckGo wraps results as //duckduckgo.com/l/?uddg=<encoded>. Unwrap it. */
    fun unwrapRedirect(href: String): String {
        val marker = "uddg="
        val i = href.indexOf(marker)
        if (i < 0) return href
        val raw = href.substring(i + marker.length).substringBefore('&')
        return try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            raw
        }
    }
}

/** The named entities that actually appear in scraped HTML, plus numeric forms. */
object Entities {
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026", "rsquo" to "\u2019",
        "lsquo" to "\u2018", "ldquo" to "\u201c", "rdquo" to "\u201d", "middot" to "\u00b7",
        "bull" to "\u2022", "copy" to "\u00a9", "reg" to "\u00ae", "trade" to "\u2122",
        "deg" to "\u00b0", "eacute" to "\u00e9", "egrave" to "\u00e8", "agrave" to "\u00e0",
        "uuml" to "\u00fc", "ouml" to "\u00f6", "auml" to "\u00e4", "szlig" to "\u00df",
    )

    fun decode(input: String): String {
        if (!input.contains('&')) return input
        var out = input
        for ((name, ch) in NAMED) out = out.replace("&$name;", ch)
        out = Regex("&#(x?)([0-9A-Fa-f]{1,6});").replace(out) { m ->
            val code = try {
                if (m.groupValues[1].isEmpty()) m.groupValues[2].toInt()
                else m.groupValues[2].toInt(16)
            } catch (_: Exception) { 0 }
            if (code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        // &amp; must be last so "&amp;lt;" collapses to "&lt;" not "<".
        return out.replace("&amp;", "&")
    }
}
