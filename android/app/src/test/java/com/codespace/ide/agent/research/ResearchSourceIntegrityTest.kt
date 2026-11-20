package com.codespace.ide.agent.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 2 (research improvements): source integrity.
 *
 * The acceptance criterion is "sources are not fabricated, and a failed or unusable fetch is never
 * presented as a source". These are the deterministic fixtures for exactly that: what may become a
 * citation, what may not, and what happens when an answer has no citations at all.
 *
 * Fixture size: nine cases over literal strings. This proves the sanitiser's policy, not the quality
 * of any model's research.
 */
class ResearchSourceIntegrityTest {

    private val good = "https://developer.android.com/topic/libraries/architecture"

    private fun src(id: String, title: String, domain: String, url: String) =
        SourceLedger.Source(id, title, url, domain, "", "", "")

    @Test fun `a sources block with markdown links parses in order with stable ids`() {
        val answer = """
            Answer body.

            Sources:
            [1] Compose docs — developer.android.com — retrieved 12:30 — [Compose](https://developer.android.com/jetpack/compose)
            [2] Kotlin — kotlinlang.org — retrieved 12:31 — [Kotlin](https://kotlinlang.org/docs/home.html)
        """.trimIndent()
        val items = ResearchSources.parse(answer)
        assertEquals(2, items.size)
        assertEquals(listOf("S1", "S2"), items.map { it.id })
        assertEquals("developer.android.com", items[0].domain)
        assertEquals("kotlinlang.org", items[1].domain)
        assertEquals("Compose docs", items[0].title)
    }

    @Test fun `bare urls in a sources block are accepted`() {
        val items = ResearchSources.parse("Sources:\n1. Android - $good")
        assertEquals(1, items.size)
        assertEquals(good, items[0].url)
    }

    @Test fun `an answer with no sources block invents nothing`() {
        assertTrue(ResearchSources.parse("I could not find anything reliable.").isEmpty())
        assertTrue(ResearchSources.parse("").isEmpty())
    }

    @Test fun `only http and https are ever openable`() {
        assertTrue(ResearchSources.isOpenable(good))
        assertFalse("javascript", ResearchSources.isOpenable("javascript:alert(1)"))
        assertFalse("file", ResearchSources.isOpenable("file:///etc/passwd"))
        assertFalse("data", ResearchSources.isOpenable("data:text/html,<h1>x</h1>"))
        assertFalse("relative", ResearchSources.isOpenable("/just/a/path"))
        assertFalse("empty", ResearchSources.isOpenable(""))
    }

    @Test fun `a control character in a url makes it unusable`() {
        assertFalse(ResearchSources.isOpenable("https://example.com/\u0000evil"))
    }

    @Test fun `sanitise drops the same source twice and renumbers the rest`() {
        val items = listOf(
            ResearchSources.Item("", "One", "", good),
            ResearchSources.Item("", "Duplicate", "", good),
            ResearchSources.Item("", "Two", "", "https://kotlinlang.org/docs/home.html"),
        )
        val out = ResearchSources.sanitise(items)
        assertEquals(2, out.size)
        assertEquals(listOf("S1", "S2"), out.map { it.id })
        assertEquals(listOf("One", "Two"), out.map { it.title })
    }

    @Test fun `a blank title falls back to the domain and is capped`() {
        val out = ResearchSources.sanitise(listOf(ResearchSources.Item("", "   ", "", good)))
        assertEquals("developer.android.com", out.single().title)

        val long = ResearchSources.sanitise(
            listOf(ResearchSources.Item("", "x".repeat(400), "", good))
        )
        assertEquals(120, long.single().title.length)
    }

    @Test fun `an unopenable ledger entry never becomes a citation`() {
        val sources = listOf(
            src("a", "Real", "developer.android.com", good),
            src("b", "Fake", "localhost", "file:///tmp/x"),
            src("c", "Also real", "kotlinlang.org", "https://kotlinlang.org/docs/home.html"),
        )
        val out = ResearchSources.fromLedger(sources)
        assertEquals(2, out.size)
        assertEquals(listOf("Real", "Also real"), out.map { it.title })
        assertTrue("nothing invented", out.none { it.url.startsWith("file:") })
    }

    @Test fun `order is the order the research produced`() {
        val items = listOf(
            ResearchSources.Item("z", "Last found", "", "https://kotlinlang.org/docs/home.html"),
            ResearchSources.Item("y", "First found", "", good),
        )
        assertEquals(listOf("Last found", "First found"), ResearchSources.sanitise(items).map { it.title })
    }
}
