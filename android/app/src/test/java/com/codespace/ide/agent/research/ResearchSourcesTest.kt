package com.codespace.ide.agent.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-11: the sources row's contract. Every rule here is one the owner asked for by name:
 * multiple sources, http and https allowed, unsafe schemes refused, missing titles filled,
 * duplicates folded, and a restored conversation rebuilding its sources from its own text.
 */
class ResearchSourcesTest {

    private fun src(n: Int, url: String, title: String = "Title $n") = SourceLedger.Source(
        id = "S$n", title = title, url = url, domain = "", retrievedAt = "12:30", publishedAt = "", excerpt = "",
    )

    @Test
    fun multipleSourcesRenderInOrderWithIds() {
        val items = ResearchSources.fromLedger(
            listOf(
                src(1, "https://developer.android.com/topic/performance"),
                src(2, "http://example.com/a"),
                src(3, "https://kotlinlang.org/docs/home.html"),
            )
        )
        assertEquals(3, items.size)
        assertEquals(listOf("S1", "S2", "S3"), items.map { it.id })
        assertEquals("developer.android.com", items[0].domain)
    }

    @Test
    fun httpsAndHttpAreBothOpenable() {
        assertTrue(ResearchSources.isOpenable("https://example.com"))
        assertTrue(ResearchSources.isOpenable("http://example.com/x?y=1"))
    }

    @Test
    fun unsafeSchemesAreRefused() {
        listOf(
            "javascript:alert(1)",
            "file:///data/data/com.codespace.ide/files/secret",
            "content://com.android.providers/sms",
            "intent://evil#Intent;scheme=http;end",
            "ftp://example.com/a",
            "https://",
            "  ",
        ).forEach { assertFalse("must refuse $it", ResearchSources.isOpenable(it)) }
    }

    @Test
    fun unsafeSourcesNeverReachTheRow() {
        val items = ResearchSources.sanitise(
            listOf(
                ResearchSources.Item("S1", "ok", "example.com", "https://example.com"),
                ResearchSources.Item("S2", "bad", "x", "javascript:alert(1)"),
                ResearchSources.Item("S3", "also bad", "y", "file:///etc/passwd"),
            )
        )
        assertEquals(1, items.size)
        assertEquals("https://example.com", items[0].url)
    }

    @Test
    fun missingTitlesFallBackToTheDomain() {
        val items = ResearchSources.sanitise(
            listOf(ResearchSources.Item("S1", "   ", "", "https://news.ycombinator.com/item?id=1"))
        )
        assertEquals(1, items.size)
        assertEquals("news.ycombinator.com", items[0].title)
        assertEquals("news.ycombinator.com", items[0].domain)
    }

    @Test
    fun duplicateUrlsAreFoldedRegardlessOfTrailingSlash() {
        val items = ResearchSources.sanitise(
            listOf(
                ResearchSources.Item("S1", "A", "", "https://example.com/page"),
                ResearchSources.Item("S2", "B", "", "https://example.com/page/"),
                ResearchSources.Item("S3", "C", "", "HTTPS://example.com/page"),
            )
        )
        assertEquals(1, items.size)
    }

    @Test
    fun aRestoredAnswerRebuildsItsSources() {
        val answer = "Kotlin is fine.\n\nSources:\n" +
            "[1] Kotlin docs — kotlinlang.org — retrieved 12:30 — [open](https://kotlinlang.org/docs/home.html)\n" +
            "[2] Android perf — developer.android.com — retrieved 12:31 — [open](https://developer.android.com/topic/performance)\n"
        val items = ResearchSources.parse(answer)
        assertEquals(2, items.size)
        assertEquals("Kotlin docs", items[0].title)
        assertEquals("kotlinlang.org", items[0].domain)
    }

    @Test
    fun anAnswerWithNoSourcesParsesToNothing() {
        assertTrue(ResearchSources.parse("Just an answer, no sources at all.").isEmpty())
        assertTrue(ResearchSources.parse("").isEmpty())
    }

    @Test
    fun aSourcesBlockFullOfUnsafeLinksParsesToNothing() {
        val answer = "Sources:\n[1] bad — [open](javascript:alert(1))\n[2] bad — [open](file:///etc/passwd)\n"
        assertTrue(ResearchSources.parse(answer).isEmpty())
    }

    @Test
    fun aRestoredTurnKeepsOnlyItsOwnSources() {
        // The ledger mark: sources recorded after the mark belong to that answer.
        val before = listOf(src(1, "https://example.com/old"))
        val after = listOf(src(1, "https://example.com/old"), src(2, "https://example.com/new"))
        val turn = ResearchSources.fromLedger(after.drop(before.size))
        assertEquals(1, turn.size)
        assertEquals("https://example.com/new", turn[0].url)
    }
}
