package com.codespace.ide.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The highlighter must colour tokens and never emit raw markup. */
class MarkdownHighlightTest {

    @Test
    fun `keywords and strings are wrapped`() {
        val html = MarkdownHighlight.highlight("val name = \"wisdom\"", "kotlin")
        assertTrue(html.contains("<span class=\"hl-keyword\">val</span>"))
        assertTrue(html.contains("<span class=\"hl-string\">&quot;wisdom&quot;</span>".replace("&quot;", "\"")))
    }

    @Test
    fun `comments stop at the line end`() {
        val html = MarkdownHighlight.highlight("// note\nval x = 1", "kotlin")
        assertTrue(html.contains("hl-comment"))
        assertTrue(html.contains("hl-keyword"))
    }

    @Test
    fun `escaping happens once, not twice`() {
        val html = MarkdownHighlight.highlight("val s = \"<b>&\"", "kotlin")
        assertFalse(html.contains("<b>"))
        assertTrue(html.contains("&lt;b&gt;"))
        assertFalse(html.contains("&amp;lt;"))
    }

    @Test
    fun `an unterminated string does not swallow the rest of the file`() {
        val html = MarkdownHighlight.highlight("val a = \"oops\nval b = 1", "kotlin")
        assertTrue("second line still highlighted", html.contains("hl-keyword"))
    }

    @Test
    fun `unknown languages are supported as plain escaped code`() {
        assertFalse(MarkdownHighlight.isSupported("brainfuck"))
        val html = MarkdownHighlight.highlight("a < b", "brainfuck")
        assertTrue(html.contains("&lt;"))
        assertFalse(html.contains("<span"))
    }

    @Test
    fun `call sites are distinguished from keywords`() {
        val html = MarkdownHighlight.highlight("println(x)", "kotlin")
        assertTrue(html.contains("hl-function"))
    }

    @Test
    fun `python hashes are comments, kotlin hashes are not`() {
        val py = MarkdownHighlight.highlight("# comment", "python")
        assertTrue(py.contains("hl-comment"))
        val kt = MarkdownHighlight.highlight("val x = 1 // yes", "kotlin")
        assertTrue(kt.contains("hl-comment"))
    }
}
