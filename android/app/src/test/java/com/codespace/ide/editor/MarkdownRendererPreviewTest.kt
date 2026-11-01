package com.codespace.ide.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rendered document has to carry the affordances the preview now relies on. */
class MarkdownRendererPreviewTest {

    private fun render(md: String) = MarkdownRenderer.render(md)

    @Test
    fun `code fences are highlighted, labelled and copyable`() {
        val html = render("```kotlin\nval x = 1\n```")
        assertTrue(html.contains("class=\"code-block\""))
        assertTrue(html.contains("copy-btn"))
        assertTrue(html.contains("hl-keyword"))
    }

    @Test
    fun `an unknown fence language is escaped, never highlighted`() {
        val html = render("```brainfuck\n<<<>>>\n```")
        assertTrue(html.contains("&lt;&lt;&lt;"))
        // The stylesheet legitimately mentions every hl-* class, so assert on the markup
        // that would only exist if the code had actually been tokenised.
        assertFalse(html.contains("class=\"hl-"))
    }

    @Test
    fun `blocks carry source line numbers for the double-tap mapping`() {
        val html = render("# Title\n\nA paragraph.")
        assertTrue(html.contains("data-line=\"1\""))
        assertTrue(html.contains("data-line=\"3\""))
    }

    @Test
    fun `task lists render as read-only checkboxes`() {
        val html = render("- [x] done\n- [ ] todo")
        assertTrue(html.contains("type=\"checkbox\""))
        assertTrue(html.contains("checked"))
        assertTrue(html.contains("disabled"))
    }

    @Test
    fun `the page reports its own errors to the console`() {
        val html = render("text")
        assertTrue(html.contains("window.onerror"))
        assertTrue(html.contains("unhandledrejection"))
        assertTrue(html.contains("vncode://line/"))
    }

    @Test
    fun inlineMathRendersAndLeavesCodeSpansAlone() {
        val html = MarkdownRenderer.render("energy \$E=mc^2\$ and `\$HOME`")
        assertTrue(html, html.contains("<math"))
        // \$HOME inside backticks is a shell variable: it must stay a code span.
        assertTrue(html, html.contains("<code>\$HOME</code>"))
        assertFalse(html, html.contains("<code><math"))
    }

    @Test
    fun displayMathGetsItsOwnBlock() {
        val html = MarkdownRenderer.render("\$\$\\frac{1}{2}\$\$")
        assertTrue(html, html.contains("math-display"))
        assertTrue(html, html.contains("<mfrac>"))
    }

    @Test
    fun linksAreRoutedThroughTheHostNotNavigated() {
        val html = MarkdownRenderer.render("[doc](docs/other.md)")
        assertTrue(html, html.contains("vncode://link/"))
        assertTrue(html, html.contains("docs/other.md"))
    }
}
