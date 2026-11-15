package com.codespace.ide.agent.docs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 2.3: the author's order beats the file names.
 *
 * A deck edited by hand routinely has slide3 before slide2 in presentation.xml. Reading them in
 * file-name order would silently reverse what the owner is looking at on screen.
 */
class PptxDeckTest {

    private fun zip(parts: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            parts.forEach { (name, xml) ->
                z.putNextEntry(ZipEntry(name)); z.write(xml.toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun slide(vararg texts: String): String =
        "<p:sld><p:cSld><p:spTree>" + texts.joinToString("") { "<p:sp><a:t>$it</a:t></p:sp>" } +
            "</p:spTree></p:cSld></p:sld>"

    private fun titledSlide(title: String, vararg bullets: String): String =
        "<p:sld><p:cSld><p:spTree><p:sp><p:nvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr>" +
            "<a:t>$title</a:t></p:sp>" + bullets.joinToString("") { "<p:sp><a:t>$it</a:t></p:sp>" } +
            "</p:spTree></p:cSld></p:sld>"

    private fun deck(): ByteArray = zip(
        mapOf(
            "ppt/presentation.xml" to
                "<p:presentation><p:sldIdLst><p:sldId id=\"256\" r:id=\"rId3\"/>" +
                "<p:sldId id=\"257\" r:id=\"rId2\"/></p:sldIdLst></p:presentation>",
            "ppt/_rels/presentation.xml.rels" to
                "<Relationships><Relationship Id=\"rId3\" Target=\"slides/slide2.xml\"/>" +
                "<Relationship Id=\"rId2\" Target=\"slides/slide1.xml\"/></Relationships>",
            "ppt/slides/slide1.xml" to titledSlide("Agenda", "first point", "second point"),
            "ppt/slides/slide2.xml" to slide("Opening remarks"),
        ),
    )

    @Test
    fun slidesFollowThePresentationOrderNotTheFileNames() {
        val slides = PptxDeck.slides(deck())
        assertEquals(2, slides.size)
        assertEquals("Opening remarks", slides[0].title)
        assertEquals("Agenda", slides[1].title)
        assertEquals(listOf(1, 2), slides.map { it.number })
    }

    @Test
    fun theTitleIsSeparatedFromTheBullets() {
        val agenda = PptxDeck.slides(deck())[1]
        assertEquals("Agenda", agenda.title)
        assertEquals(listOf("first point", "second point"), agenda.lines)
    }

    @Test
    fun theRenderedDeckSaysWhatItIs() {
        val rendered = PptxDeck.render(deck())
        assertTrue(rendered.startsWith("2 slide(s), in presentation order"))
        assertTrue(rendered.contains("Slide 2 — Agenda"))
        assertTrue(rendered.contains("  - first point"))
    }

    @Test
    fun aDeckWithNoReadableOrderStillListsItsSlides() {
        val bare = zip(
            mapOf(
                "ppt/slides/slide10.xml" to slide("ten"),
                "ppt/slides/slide2.xml" to slide("two"),
            ),
        )
        val slides = PptxDeck.slides(bare)
        assertEquals(listOf("two", "ten"), slides.map { it.title })
    }

    @Test
    fun anUnreadableFileIsEmpty() {
        assertTrue(PptxDeck.slides("not a zip".toByteArray()).isEmpty())
    }
}
