package com.codespace.ide.agent.research

/**
 * R4 (owner approval 2026-11-11): site-specific readers as INTERNAL adapters, not four new tools.
 *
 * A tool per site would cost prompt tokens on every send and ask the model to pick the right site
 * before it knows which site has the answer. web_fetch recognises the host instead and reads the
 * page properly; when the adapter fails, the caller falls through to general extraction.
 */
object SiteAdapters {

    enum class Kind { YOUTUBE, REDDIT, WIKIPEDIA, FEED, GENERIC }

    fun kindOf(url: String, contentType: String = "", body: String = ""): Kind {
        val host = UrlTools.domain(url)
        val path = try { java.net.URI(url).path.orEmpty() } catch (_: Exception) { "" }
        return when {
            host.endsWith("youtube.com") && path.contains("/watch") -> Kind.YOUTUBE
            host == "youtu.be" -> Kind.YOUTUBE
            host.endsWith("reddit.com") && path.contains("/comments/") -> Kind.REDDIT
            host.endsWith("wikipedia.org") && path.contains("/wiki/") -> Kind.WIKIPEDIA
            contentType.contains("rss", true) || contentType.contains("atom", true) -> Kind.FEED
            path.endsWith(".rss") || path.endsWith(".atom") || path.endsWith("/feed") ||
                path.endsWith("/feeds/posts/default") -> Kind.FEED
            body.isNotBlank() && (body.trimStart().startsWith("<?xml") || body.trimStart().startsWith("<rss")) -> Kind.FEED
            else -> Kind.GENERIC
        }
    }

    /** YouTube's watch page: title, channel, published date, description. No invented view counts. */
    fun youtube(html: String, url: String): String {
        val title = Regex("(?is)<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]*)\"")
            .find(html)?.groupValues?.get(1)
            ?: Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1).orEmpty()
        val channel = Regex("(?is)<link[^>]+itemprop=\"name\"[^>]+content=\"([^\"]*)\"")
            .find(html)?.groupValues?.get(1)
            ?: Regex("(?is)\"author\":\"([^\"]{1,80})\"").find(html)?.groupValues?.get(1).orEmpty()
        val published = Regex("(?is)itemprop=\"datePublished\"[^>]*content=\"([^\"]*)\"")
            .find(html)?.groupValues?.get(1)
            ?: Regex("(?is)\"publishDate\":\"([^\"]*)\"").find(html)?.groupValues?.get(1).orEmpty()
        val description = Regex("(?is)<meta[^>]+property=\"og:description\"[^>]+content=\"([^\"]*)\"")
            .find(html)?.groupValues?.get(1)
            ?: Regex("(?is)\"shortDescription\":\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(html)?.groupValues?.get(1)?.replace("\\n", "\n").orEmpty()
        if (title.isBlank()) return ""
        return buildString {
            append("YouTube video\n")
            append("Title: ").append(Entities.decode(HtmlExtractor.cleanInline(title))).append('\n')
            if (channel.isNotBlank()) append("Channel: ").append(HtmlExtractor.cleanInline(channel)).append('\n')
            if (published.isNotBlank()) append("Published: ").append(published.take(10)).append('\n')
            append("URL: ").append(url).append('\n')
            if (description.isNotBlank()) append("\n").append(Entities.decode(description).take(1500))
            append("\nNote: view and like counts are not reported because they are not reliably present.")
        }
    }

    /**
     * Reddit's public .json view of a thread. Scores and comment counts come from the payload when
     * present; when absent they are omitted rather than guessed.
     */
    fun reddit(json: String): String {
        return try {
            val arr = org.json.JSONArray(json)
            val post = arr.optJSONObject(0)?.optJSONObject("data")?.optJSONArray("children")
                ?.optJSONObject(0)?.optJSONObject("data") ?: return ""
            val sb = StringBuilder("Reddit thread\n")
            sb.append("Title: ").append(post.optString("title")).append('\n')
            sb.append("Subreddit: r/").append(post.optString("subreddit")).append('\n')
            if (post.has("score")) sb.append("Score: ").append(post.optInt("score")).append('\n')
            if (post.has("num_comments")) sb.append("Comments: ").append(post.optInt("num_comments")).append('\n')
            val created = post.optDouble("created_utc", 0.0)
            if (created > 0) sb.append("Posted: ").append(epochDay(created)).append('\n')
            sb.append("URL: https://www.reddit.com").append(post.optString("permalink")).append('\n')
            val self = post.optString("selftext").trim()
            if (self.isNotEmpty()) sb.append("\n").append(self.take(1200)).append('\n')
            val comments = arr.optJSONObject(1)?.optJSONObject("data")?.optJSONArray("children")
            if (comments != null) {
                sb.append("\nTop comments:\n")
                var n = 0
                for (i in 0 until comments.length()) {
                    if (n >= 8) break
                    val d = comments.optJSONObject(i)?.optJSONObject("data") ?: continue
                    val body = d.optString("body").trim()
                    if (body.isEmpty() || body == "[deleted]") continue
                    sb.append("- ")
                    if (d.has("score")) sb.append("(").append(d.optInt("score")).append(") ")
                    sb.append(body.replace(Regex("\\s+"), " ").take(240)).append('\n')
                    n++
                }
            }
            sb.toString().trim()
        } catch (_: Exception) {
            ""
        }
    }

    /** Wikipedia REST summary: the documented endpoint, already plain text. */
    fun wikipedia(json: String, url: String): String {
        return try {
            val o = org.json.JSONObject(json)
            val extract = o.optString("extract").ifBlank { return "" }
            buildString {
                append("Wikipedia\nTitle: ").append(o.optString("title")).append('\n')
                if (o.has("description")) append("Description: ").append(o.optString("description")).append('\n')
                append("URL: ").append(o.optString("content_urls", url)).take(0)
                append("URL: ").append(url).append('\n')
                append("\n").append(extract.take(2500))
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** RSS 2.0 and Atom, item by item. Dates are passed through as published, never reformatted. */
    fun feed(xml: String, url: String, limit: Int = 8): String {
        val items = Regex("(?is)<item\\b.*?</item>|<entry\\b.*?</entry>").findAll(xml).map { it.value }.toList()
        if (items.isEmpty()) return ""
        val sb = StringBuilder("Feed: ").append(items.size).append(" items\nURL: ").append(url).append('\n')
        items.take(limit).forEach { item ->
            fun tag(name: String): String =
                Regex("(?is)<" + name + "[^>]*>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</" + name + ">")
                    .find(item)?.groupValues?.get(1)?.let { HtmlExtractor.cleanInline(it) }.orEmpty()
            val title = tag("title")
            if (title.isEmpty()) return@forEach
            val link = tag("link").ifBlank {
                Regex("(?is)<link[^>]+href=\"([^\"]+)\"").find(item)?.groupValues?.get(1).orEmpty()
            }
            val date = tag("pubDate").ifBlank { tag("published") }.ifBlank { tag("updated") }
            val summary = tag("description").ifBlank { tag("summary") }.take(200)
            sb.append("\n- ").append(title)
            if (date.isNotEmpty()) sb.append(" (").append(date.take(31)).append(")")
            sb.append('\n')
            if (summary.isNotEmpty()) sb.append("  ").append(summary).append('\n')
            if (link.isNotEmpty()) sb.append("  ").append(link).append('\n')
        }
        return sb.toString().trim()
    }

    private fun epochDay(seconds: Double): String =
        java.time.Instant.ofEpochSecond(seconds.toLong()).toString().take(10)
}
