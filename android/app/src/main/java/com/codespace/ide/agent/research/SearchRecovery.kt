package com.codespace.ide.agent.research

/**
 * R3 (owner approval 2026-11-11): a failed search is a step, not an answer.
 *
 * The device complaint was "it said it could not find the information and asked me for a URL". That
 * is the OLD code treating one empty DuckDuckGo response as final. This class makes recovery an
 * explicit, testable strategy: try another backend, then another PHRASING, never the same pair twice,
 * and report what was attempted instead of implying nothing is out there.
 */
object SearchRecovery {

    /** What one backend did. Kept as text so a refusal can never look like a result. */
    enum class Outcome { OK, EMPTY, CHALLENGE, RATE_LIMITED, NETWORK, MALFORMED }

    data class Attempt(val backend: String, val query: String, val outcome: Outcome) {
        fun label(): String = backend + " [" + outcome.name.lowercase() + "]"
    }

    data class Report(
        val results: List<WebResult>,
        val attempts: List<Attempt>,
    ) {
        val failure: Outcome? = when {
            results.isNotEmpty() -> null
            attempts.any { it.outcome == Outcome.OK || it.outcome == Outcome.EMPTY } -> Outcome.EMPTY
            attempts.isNotEmpty() -> attempts.last().outcome
            else -> Outcome.EMPTY
        }
    }

    /** One backend call: the fetcher returns (httpCode, body). */
    interface Fetcher {
        fun get(url: String): Pair<Int, String>
    }

    data class Backend(val name: String, val urlFor: (String) -> String, val parse: (String) -> List<WebResult>)

    fun backends(): List<Backend> = listOf(
        Backend("ddg-html", { q -> "https://html.duckduckgo.com/html/?q=" + enc(q) }, ResultParser::duckDuckGoHtml),
        Backend("ddg-lite", { q -> "https://lite.duckduckgo.com/lite/?q=" + enc(q) }, ResultParser::duckDuckGoLite),
        Backend("bing", { q -> "https://www.bing.com/search?q=" + enc(q) + "&count=10" }, ResultParser::bingHtml),
    )

    /** Query variants, cheapest first: as asked, then quoted, then the last clause dropped. */
    fun variants(query: String): List<String> {
        val q = query.trim()
        val out = LinkedHashSet<String>()
        if (q.isNotEmpty()) out.add(q)
        if (q.isNotEmpty() && !q.startsWith("\"")) out.add("\"" + q + "\"")
        val words = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size > 4) out.add(words.take(words.size - 2).joinToString(" "))
        if (words.size > 2) out.add(words.take(3).joinToString(" "))
        return out.toList()
    }

    fun classify(code: Int, body: String): Outcome = when {
        code == 429 -> Outcome.RATE_LIMITED
        code == 403 -> Outcome.CHALLENGE
        code !in 200..299 -> Outcome.NETWORK
        body.isBlank() -> Outcome.EMPTY
        ResultParser.looksLikeChallenge(body) -> Outcome.CHALLENGE
        else -> Outcome.OK
    }

    /**
     * Run the strategy. `attempted` is carried across calls inside one task so the same failing pair
     * is never retried, which is what turned a slow search into a loop in the old flow.
     */
    fun run(
        query: String,
        fetcher: Fetcher,
        attempted: MutableSet<String> = HashSet(),
        backendList: List<Backend> = backends(),
        maxRequests: Int = 4,
        limit: Int = 8,
    ): Report {
        val attempts = ArrayList<Attempt>()
        val collected = ArrayList<WebResult>()
        var requests = 0
        for (v in variants(query)) {
            for (b in backendList) {
                if (requests >= maxRequests) break
                val key = b.name + "|" + v
                if (key in attempted) continue
                attempted.add(key)
                requests++
                val (code, body) = try {
                    fetcher.get(b.urlFor(v))
                } catch (e: Exception) {
                    attempts.add(Attempt(b.name, v, Outcome.NETWORK)); continue
                }
                val outcome = classify(code, body)
                if (outcome != Outcome.OK) {
                    attempts.add(Attempt(b.name, v, outcome)); continue
                }
                val parsed = try { b.parse(body) } catch (e: Exception) { emptyList() }
                if (parsed.isEmpty()) {
                    attempts.add(Attempt(b.name, v, Outcome.MALFORMED)); continue
                }
                attempts.add(Attempt(b.name, v, Outcome.OK))
                collected.addAll(parsed)
                val prepared = ResultQuality.prepare(collected, limit)
                if (prepared.size >= 3) return Report(prepared, attempts)
            }
            if (requests >= maxRequests) break
        }
        return Report(ResultQuality.prepare(collected, limit), attempts)
    }

    private fun enc(q: String): String = java.net.URLEncoder.encode(q, "UTF-8")
}
