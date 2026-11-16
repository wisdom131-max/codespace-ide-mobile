package com.codespace.ide.chat.ui

import com.codespace.ide.agent.research.ResearchSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROJECT SEVEN 7.8 — sources are presented from what the runtime returned, and a bound never hides itself. */
class Stage78ResearchPresentationTest {

    private fun item(n: Int, title: String = "Title $n", domain: String = "example$n.com") =
        ResearchSources.Item("S$n", title, domain, "https://example$n.com/page-$n")

    @Test
    fun `a normal list draws every row and says nothing extra`() {
        val b = ResearchPresentation.bound(List(10) { item(it) })
        assertEquals(10, b.shown.size)
        assertEquals(0, b.hidden)
        assertNull(ResearchPresentation.overflowLine(b.hidden))
    }

    @Test
    fun `a huge list is bounded and the omission is counted, not silent`() {
        val b = ResearchPresentation.bound(List(100) { item(it) })
        assertEquals(ResearchPresentation.MAX_ROWS, b.shown.size)
        assertEquals(60, b.hidden)
        val line = ResearchPresentation.overflowLine(b.hidden)!!
        assertTrue(line.contains("60"))
        assertTrue(line.contains("cited in the answer"))
    }

    @Test
    fun `exactly at the bound, nothing is withheld`() {
        val b = ResearchPresentation.bound(List(ResearchPresentation.MAX_ROWS) { item(it) })
        assertEquals(ResearchPresentation.MAX_ROWS, b.shown.size)
        assertEquals(0, b.hidden)
    }

    @Test
    fun `each source is described with its title, domain and whether it opens`() {
        val label = ResearchPresentation.label(item(1))
        assertTrue(label.contains("Title 1"))
        assertTrue(label.contains("example1.com"))
        assertTrue(label.contains("opens the source"))
    }

    @Test
    fun `missing metadata is stated rather than left blank`() {
        val label = ResearchPresentation.label(ResearchSources.Item("S1", "", "", "https://x.test/a"))
        assertTrue(label.contains("Untitled source"))
        assertTrue(label.contains("domain unknown"))
    }

    @Test
    fun `an unopenable url is described as such, never as a working link`() {
        val label = ResearchPresentation.label(ResearchSources.Item("S1", "T", "d.test", "javascript:alert(1)"))
        assertTrue(label.contains("cannot be opened"))
    }

    @Test
    fun `the header counts honestly, including none`() {
        assertEquals("No sources", ResearchPresentation.header(0))
        assertEquals("1 source", ResearchPresentation.header(1))
        assertEquals("4 sources", ResearchPresentation.header(4))
    }

    @Test
    fun `empty research is reported as a result, not a success`() {
        assertTrue(ResearchPresentation.emptyNote().contains("not a success"))
    }
}
