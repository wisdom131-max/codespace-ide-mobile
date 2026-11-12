package com.codespace.ide.agent.research

/**
 * PROJECT 1.2 - WHAT KIND OF SOURCE IS THIS, AND DOES ITS AGE MATTER HERE?
 *
 * The programme is explicit about the trap: search ranking is not credibility, and domain
 * popularity is not authority either. So this judges a source by what it structurally IS - an
 * original artifact, a project's own documentation, an edited secondary account, a community
 * answer, or a page whose only purpose is to rank - and says why in words the answer can repeat.
 *
 * Recency is conditional. A question about a language's concept does not get worse with age; a
 * question about a current version, a deprecation, a breaking change or a security issue does.
 * Only then is an old publication date a penalty, and the reason says so instead of quietly
 * lowering a number.
 */
object SourceQuality {

    enum class Authority(val label: String) {
        /** The artifact itself: a spec, a standard, a release's own notes, source code. */
        PRIMARY("primary source"),
        /** The project's own documentation. */
        OFFICIAL("official documentation"),
        /** Edited third-party reporting or writing. Useful, not authoritative. */
        SECONDARY("reputable secondary source"),
        /** Q&A and forum answers: real experience, one person's account. */
        COMMUNITY("community answer"),
        /** Rank-farming pages: listicles, scrapers, "download free" bait. */
        WEAK("weak source"),
    }

    data class Assessment(
        val authority: Authority,
        /** True when the question needs current information and this source is old. */
        val stale: Boolean,
        /** Ordering hint only. Never a credibility verdict, and never shown as one. */
        val score: Int,
        val reason: String,
    )

    private val PRIMARY_HOSTS = setOf(
        "rfc-editor.org", "ietf.org", "w3.org", "iso.org", "kernel.org", "unicode.org",
        "eclipse.org", "apache.org", "ietf.org",
    )

    private val OFFICIAL_HOSTS = setOf(
        "developer.android.com", "kotlinlang.org", "developer.mozilla.org", "docs.python.org",
        "nodejs.org", "go.dev", "rust-lang.org", "doc.rust-lang.org", "spring.io", "react.dev",
        "vuejs.org", "gradle.org", "docs.oracle.com", "learn.microsoft.com", "git-scm.com",
        "postgresql.org", "sqlite.org", "nginx.org",
    )

    private val COMMUNITY_HOSTS = setOf(
        "stackoverflow.com", "stackexchange.com", "superuser.com", "reddit.com", "news.ycombinator.com",
        "discourse.org", "forum.kotlinlang.org", "github.com",
    )

    private val WEAK_MARKERS = listOf(
        "top-10", "top10", "best-", "-best", "listicle", "coupon", "crack", "download-free",
        "free-download", "apkpure", "softonic", "cheatsheet-online",
    )

    /** Terms that make an out-of-date answer actively misleading rather than merely old. */
    private val FRESHNESS_TERMS = setOf(
        "latest", "current", "currently", "now", "today", "new", "newest", "recent", "release",
        "released", "changelog", "deprecated", "deprecation", "migration", "migrate", "breaking",
        "version", "cve", "security", "support", "supported", "upgrade", "roadmap", "price",
        "pricing", "2024", "2025", "2026",
    )

    /** Does this question need current information? */
    fun freshnessMatters(question: String): Boolean {
        val tokens = question.lowercase().split(Regex("[^a-z0-9.]+")).filter { it.isNotBlank() }
        if (tokens.any { it in FRESHNESS_TERMS }) return true
        // "kotlin 2.0", "android 15": a version in the question is a request for that era.
        return tokens.any { Regex("^\\d+(\\.\\d+)+$").matches(it) }
    }

    fun assess(
        url: String,
        title: String,
        question: String,
        publishedAt: String = "",
        nowYear: Int = 2026,
    ): Assessment {
        val host = UrlTools.domain(url)
        val authority = classify(host, url, title)
        val needsFresh = freshnessMatters(question)
        val year = yearOf(publishedAt)
        val stale = needsFresh && year != null && nowYear - year >= 3
        val veryNew = needsFresh && year != null && nowYear - year <= 1
        val score = authority.ordinal * -1 + (if (stale) -2 else 0) + (if (veryNew) 1 else 0)
        return Assessment(authority, stale, score, reason(authority, host, stale, needsFresh, year, nowYear))
    }

    private fun reason(
        authority: Authority,
        host: String,
        stale: Boolean,
        needsFresh: Boolean,
        year: Int?,
        nowYear: Int,
    ): String {
        val base = when (authority) {
            Authority.PRIMARY -> "The original artifact, not an account of it."
            Authority.OFFICIAL -> host + " is the project's own documentation."
            Authority.SECONDARY -> "Edited third-party source: usable, but check its claims against the project."
            Authority.COMMUNITY -> "One person's account, not documentation."
            Authority.WEAK -> "This looks like a page built to rank rather than to inform."
        }
        return when {
            stale -> base + " Written in " + year + ", " + (nowYear - (year ?: nowYear)) +
                " years before a question about current behaviour."
            needsFresh && year == null -> base + " No publication date, so its currency cannot be checked."
            else -> base
        }
    }

    fun classify(host: String, url: String, title: String): Authority {
        val t = title.lowercase()
        val u = url.lowercase()
        if (WEAK_MARKERS.any { t.contains(it) || u.contains(it) }) return Authority.WEAK
        return when {
            host in PRIMARY_HOSTS -> Authority.PRIMARY
            (host == "github.com" || host == "gitlab.com") &&
                (u.contains("/releases") || u.contains("/blob/") || u.contains("/raw/")) -> Authority.PRIMARY
            host in OFFICIAL_HOSTS -> Authority.OFFICIAL
            host.startsWith("docs.") || host.startsWith("developer.") -> Authority.OFFICIAL
            u.contains("/documentation/") || u.contains("/reference/") || u.contains("/api/") -> Authority.OFFICIAL
            host in COMMUNITY_HOSTS -> Authority.COMMUNITY
            host.endsWith(".gov") || host.endsWith(".edu") -> Authority.SECONDARY
            else -> Authority.SECONDARY
        }
    }

    /** First plausible year in a date string; null when there is no date at all. */
    fun yearOf(publishedAt: String): Int? =
        Regex("\\b(19|20)(\\d{2})\\b").find(publishedAt)?.let { it.value.toIntOrNull() }
}
