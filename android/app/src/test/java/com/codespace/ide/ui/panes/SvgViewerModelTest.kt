package com.codespace.ide.ui.panes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the SVG viewer that do not need a WebView: what size the file asks for, whether it
 * parses at all, and the data URI. These are the numbers the toolbar shows, so a wrong answer here
 * is a wrong answer on screen.
 */
class SvgViewerModelTest {

    @Test
    fun `explicit width and height win`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"64\" height=\"32\"></svg>"
        val size = SvgViewerModel.intrinsic(svg)
        assertEquals(64f, size.width, 0.01f)
        assertEquals(32f, size.height, 0.01f)
    }

    @Test
    fun `viewbox supplies the size when the attributes are missing`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 120 240\"></svg>"
        val size = SvgViewerModel.intrinsic(svg)
        assertEquals(120f, size.width, 0.01f)
        assertEquals(240f, size.height, 0.01f)
    }

    @Test
    fun `an svg with neither falls back to the css default`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"
        val size = SvgViewerModel.intrinsic(svg)
        assertEquals(300f, size.width, 0.01f)
        assertEquals(150f, size.height, 0.01f)
    }

    @Test
    fun `a malformed file reports the line it broke on`() {
        val broken = "<svg xmlns=\"http://www.w3.org/2000/svg\">\n  <rect width=\"10\"\n</svg>"
        val error = SvgViewerModel.validate(broken)
        assertNotNull(error)
        assertTrue("message should name a line: " + error, error!!.contains("Line"))
    }

    @Test
    fun `a good file validates clean`() {
        assertNull(
            SvgViewerModel.validate("<svg xmlns=\"http://www.w3.org/2000/svg\"><circle r=\"5\"/></svg>")
        )
    }

    @Test
    fun `an empty file says so instead of rendering nothing`() {
        assertEquals("This file is empty.", SvgViewerModel.validate("   "))
    }

    @Test
    fun `node count ignores the root, which is what a person counts`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><g><rect/><circle/></g><path/></svg>"
        // svg, g, rect, circle, path: getElementsByTagName("*") counts every element.
        assertEquals(5, SvgViewerModel.nodeCount(svg))
    }

    @Test
    fun `metrics line carries size, nodes and bytes`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><rect/></svg>"
        val line = SvgViewerModel.metricsLine(svg)
        assertTrue(line, line.startsWith("10 x 10"))
        assertTrue(line, line.contains("2 nodes"))
        assertTrue(line, line.contains(" B") || line.contains("KB"))
    }

    @Test
    fun `the data uri is base64 and round trips`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>héllo</text></svg>"
        val uri = SvgViewerModel.dataUri(svg)
        assertTrue(uri.startsWith("data:image/svg+xml;base64,"))
        val decoded = String(
            java.util.Base64.getDecoder().decode(uri.removePrefix("data:image/svg+xml;base64,")),
            Charsets.UTF_8,
        )
        assertEquals(svg, decoded)
    }

    @Test
    fun `byte labels switch units at the right point`() {
        assertEquals("512 B", SvgViewerModel.sizeLabel(512))
        assertEquals("1.0 KB", SvgViewerModel.sizeLabel(1024))
        assertEquals("1.0 MB", SvgViewerModel.sizeLabel(1024 * 1024))
    }
}
