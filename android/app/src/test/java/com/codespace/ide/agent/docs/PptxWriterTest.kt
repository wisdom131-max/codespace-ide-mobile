package com.codespace.ide.agent.docs

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 2.7: a deck that only unzips is not a deck.
 *
 * PowerPoint repairs or refuses a presentation whose package is missing its master, layout or
 * theme, so the test asserts the whole chain exists and points somewhere real. The round trip
 * through PptxDeck then proves the slides and their bullets are where a reader expects them.
 */
class PptxWriterTest {

    private fun parts(bytes: ByteArray): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                map[e.name] = z.readBytes().toString(Charsets.UTF_8)
                e = z.nextEntry
            }
        }
        return map
    }

    private fun deck(): ByteArray = PptxWriter.build(
        PptxWriter.slidesOf("# Roadmap\nShip the editor\nShip the assistant\n# Status\nGreen"),
    )

    @Test
    fun thePackageCarriesTheWholeChain() {
        val names = parts(deck()).keys
        listOf(
            "[Content_Types].xml", "_rels/.rels", "ppt/presentation.xml",
            "ppt/_rels/presentation.xml.rels", "ppt/slideMasters/slideMaster1.xml",
            "ppt/slideLayouts/slideLayout1.xml", "ppt/slideLayouts/slideLayout2.xml",
            "ppt/theme/theme1.xml", "ppt/slides/slide1.xml", "ppt/slides/slide2.xml",
        ).forEach { assertTrue("missing " + it, names.contains(it)) }
    }

    @Test
    fun everySlidePointsAtALayoutThatExists() {
        val p = parts(deck())
        for (index in 1..2) {
            val rels = p.getValue("ppt/slides/_rels/slide$index.xml.rels")
            val target = Regex("Target=\"\\.\\./(slideLayouts/slideLayout[0-9]+\\.xml)\"").find(rels)!!.groupValues[1]
            assertTrue("slide$index points at a missing layout", p.containsKey("ppt/$target"))
        }
    }

    @Test
    fun slidesComeBackWithTitlesAndBullets() {
        val slides = PptxDeck.slides(deck())
        assertEquals(listOf("Roadmap", "Status"), slides.map { it.title })
        assertEquals(listOf("Ship the editor", "Ship the assistant"), slides[0].lines)
    }

    @Test
    fun thePresentationOrderMatchesTheSourceOrder() {
        val xml = parts(deck()).getValue("ppt/presentation.xml")
        val ids = Regex("<p:sldId id=\"[0-9]+\" r:id=\"(rId[0-9]+)\"/>").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(listOf("rId2", "rId3"), ids)
    }

    @Test
    fun contentIsEscaped() {
        val bytes = PptxWriter.build(listOf(PptxWriter.Slide("a < b & c", listOf("x > y"))))
        assertTrue(parts(bytes).getValue("ppt/slides/slide1.xml").contains("a &lt; b &amp; c"))
    }

    @Test
    fun aTitleWithNoBulletsUsesTheTitleLayout() {
        val bytes = PptxWriter.build(listOf(PptxWriter.Slide("Only a title", emptyList())))
        assertTrue(parts(bytes).getValue("ppt/slides/_rels/slide1.xml.rels").contains("slideLayout1.xml"))
    }
}
