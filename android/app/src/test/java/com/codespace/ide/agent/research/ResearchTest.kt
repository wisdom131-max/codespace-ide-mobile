package com.codespace.ide.agent.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R1: readable text out of real-world HTML, including the shapes that used to defeat web_fetch. */
class HtmlExtractorTest {

    @Test
    fun anArticleBecomesTitleParagraphsAndLinks() {
        val html = """
            <html><head><title>Kotlin 2.1 &amp; You</title>
            <meta name="description" content="What changed.">
            <style>.x{}</style><script>var a = "</p>";</script></head>
            <body><nav><a href="/home">Home</a></nav>
            <h1>Kotlin 2.1</h1>
            <h2>Performance</h2>
            <p>The compiler is meaningfully faster in this release, especially for large projects.</p>
            <ul><li>Faster warm builds</li><li>Better incremental results</li></ul>
            <a href="/docs/whatsnew">What is new</a>
            <a href="https://example.com/x?utm_source=a">External</a>
            </body></html>
        """.trimIndent()
        val page = HtmlExtractor.extract(html, "https://kotlinlang.org/releases/2-1")
        assertEquals("Kotlin 2.1 & You", page.title)
        assertTrue(page.text.contains("compiler is meaningfully faster"))
        assertFalse("script content must never be readable text", page.text.contains("var a"))
        assertFalse(page.text.contains(".x{}"))
        assertTrue(page.headings.contains("Performance"))
        val urls = page.links.map { it.url }
        assertTrue(urls.contains("https://kotlinlang.org/docs/whatsnew"))
        assertTrue(urls.contains("https://example.com/x?utm_source=a"))
    }

    @Test
    fun malformedHtmlDoesNotThrow() {
        val page = HtmlExtractor.extract("<div><p>unclosed <b>bold <h2>Heading<p>Second paragraph here ok", null)
        assertTrue(page.text.contains("unclosed"))
    }

    @Test
    fun aScriptOnlyPageYieldsNoReadableText() {
        val page = HtmlExtractor.extract("<html><body><div id=root></div><script>app.mount()</script></body></html>")
        assertEquals("", page.text.trim())
    }

    @Test
    fun truncationIsExplicitAndBounded() {
        val big = "<p>" + "word ".repeat(4000) + "</p>"
        val page = HtmlExtractor.extract(big, null, 500)
        assertTrue(page.truncated)
        assertEquals(500, page.text.length)
        assertEquals(big.length, page.originalChars)
    }

    @Test
    fun entitiesAndEncodingAreDecoded() {
        val page = HtmlExtractor.extract(
            "<p>Caf&eacute; &amp; cr&#232;me &#x2014; a na&#239;ve but very readable paragraph.</p>")
        assertTrue(page.text.contains("Café & crème \u2014 a naïve but very readable paragraph."))
    }
}

/** R2: search results carry enough to choose from, and junk never reaches the model. */
class SearchParsingTest {

    private val ddg = """
        <div class="result results_links">
          <div class="links_main links_deep result__body">
            <h2 class="result__title"><a rel="nofollow" class="result__a"
              href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fagents%3Futm_source%3Dddg&amp;rut=abc">AI coding agents</a></h2>
            <a class="result__snippet" href="#">A survey of agentic coding tools and how they differ in practice.</a>
            <div class="result__extras"><span class="result__timestamp">2 Nov 2026</span></div>
          </div>
        </div>
        <div class="result results_links">
          <div class="links_main links_deep result__body">
            <h2 class="result__title"><a rel="nofollow" class="result__a"
              href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fagents%3Futm_source%3Dx&amp;rut=y">AI coding agents</a></h2>
            <a class="result__snippet" href="#">Duplicate of the first result.</a>
          </div>
        </div>
    """.trimIndent()

    @Test
    fun resultsCarryTitleDomainSnippetAndDate() {
        val parsed = ResultParser.duckDuckGoHtml(ddg)
        assertTrue(parsed.isNotEmpty())
        val first = parsed.first()
        assertEquals("https://example.com/agents?utm_source=ddg", first.url)
        assertEquals("example.com", first.domain)
        assertTrue(first.snippet.contains("survey of agentic coding tools"))
        assertEquals("2 Nov 2026", first.date)
    }

    @Test
    fun duplicatesAreFoldedAndJunkDomainsDropped() {
        val prepared = ResultQuality.prepare(ResultParser.duckDuckGoHtml(ddg))
        assertEquals(1, prepared.size)
        val junk = ResultQuality.prepare(
            listOf(WebResult("x", "https://duckduckgo.com/y?q=1", "duckduckgo.com")),
        )
        assertTrue(junk.isEmpty())
    }

    @Test
    fun theRenderedBlockIsStructuredAndBounded() {
        val results = (1..8).map {
            WebResult("Title $it", "https://site$it.example/a/$it", "site$it.example", "Snippet $it", "")
        }
        val block = ResultQuality.render(results, listOf("ddg-html [ok]"))
        assertTrue(block.startsWith("8 results"))
        assertTrue(block.contains("1. Title 1"))
        assertTrue(block.contains("site1.example"))
        assertTrue(block.length < 2200)
    }

    @Test
    fun aChallengePageIsRecognisedRatherThanParsedAsEmpty() {
        assertTrue(ResultParser.looksLikeChallenge("<div>Our systems detected unusual traffic</div>"))
        assertTrue(ResultParser.looksLikeChallenge("Please verify you are human"))
    }
}

/** R3: recovery, not a dead end. */
class SearchRecoveryTest {

    private fun fetcher(responses: Map<String, Pair<Int, String>>): SearchRecovery.Fetcher =
        object : SearchRecovery.Fetcher {
            override fun get(url: String): Pair<Int, String> {
                for ((needle, resp) in responses) if (url.contains(needle)) return resp
                return 404 to ""
            }
        }

    /** lite.duckduckgo.com shape: a plain table of result-link anchors. */
    private val liteHtml = """<table><tr><td><a class="result-link"
        href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fgood.example%2Fa">Good result</a></td></tr></table>"""

    @Test
    fun anEmptyFirstBackendFallsThroughToTheNext() {
        val f = fetcher(mapOf("html.duckduckgo.com" to (200 to "<html>nothing here</html>"),
            "lite.duckduckgo.com" to (200 to liteHtml)))
        val report = SearchRecovery.run("kotlin agents", f)
        assertTrue(report.results.isNotEmpty())
        assertTrue(report.attempts.any { it.backend == "ddg-html" && it.outcome != SearchRecovery.Outcome.OK })
        assertTrue(report.attempts.any { it.backend == "ddg-lite" && it.outcome == SearchRecovery.Outcome.OK })
    }

    @Test
    fun aRateLimitIsDetectedAndNotTreatedAsNoResults() {
        val f = fetcher(mapOf("duckduckgo" to (429 to "slow down"), "bing" to (200 to "")))
        val report = SearchRecovery.run("q", f)
        assertTrue(report.attempts.any { it.outcome == SearchRecovery.Outcome.RATE_LIMITED })
        assertTrue(report.results.isEmpty())
    }

    @Test
    fun noBackendAndPhrasingPairIsEverRequestedTwice() {
        // The rule is about REPEATING a failed pair, not about exploring untried ones: with a
        // request budget per call, a later call may spend its budget on pairs the first never
        // reached. What must never happen is the same URL being asked for again.
        val attempted = HashSet<String>()
        val seen = ArrayList<String>()
        val counting = object : SearchRecovery.Fetcher {
            override fun get(url: String): Pair<Int, String> {
                seen.add(url)
                return 200 to "<html>empty</html>"
            }
        }
        SearchRecovery.run("q", counting, attempted)
        SearchRecovery.run("q", counting, attempted)
        assertEquals("no URL may be requested twice", seen.size, seen.distinct().size)
    }

    @Test
    fun allBackendsFailingStillTellsTheModelWhatToDoInsteadOfAskingForAUrl() {
        val f = fetcher(mapOf("duckduckgo" to (429 to ""), "bing" to (503 to "")))
        val report = SearchRecovery.run("q", f)
        val text = ResultQuality.render(report.results, report.attempts.map { it.label() })
        assertTrue(text.contains("No usable results"))
        assertTrue(text.contains("Do not ask the user for a URL"))
        assertTrue(text.contains("Tried:"))
    }

    @Test
    fun queryVariantsWidenWithoutRepeating() {
        val v = SearchRecovery.variants("what are people saying about ai coding agents this week")
        assertTrue(v.size >= 2)
        assertEquals(v.size, v.distinct().size)
        assertTrue(v[1].startsWith("\""))
    }
}
