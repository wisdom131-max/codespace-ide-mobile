package com.codespace.ide.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Markdown preview composes three things that are easy to break silently: a user stylesheet
 * (which is injected into the document), mermaid fences (which change a code block into a
 * diagram) and the scroll hook the editor drives. None of them fail loudly, so each is pinned.
 */
class MarkdownRendererThemeTest {

    private val mermaidFence = "```mermaid\ngraph TD;\n  A-->B;\n```"
    private val jsFence = "```kotlin\nval x = 1\n```"

    @Test
    fun aStylesheetIsInjectedIntoTheDocument() {
        val html = MarkdownRenderer.render("# Title", customCss = "h1 { color: #4FC3F7 }")
        assertTrue("the rule must reach the page", html.contains("h1 { color: #4FC3F7 }"))
        assertTrue("inside a style element", html.contains("<style>"))
    }

    @Test
    fun aStylesheetCannotCloseTheStyleElement() {
        val hostile = "h1 { color: red }</style><script>alert(1)</script>"
        val html = MarkdownRenderer.render("# Title", customCss = hostile)
        assertFalse("the closing tag must be neutralised", html.contains("</style><script>"))
        assertTrue("neutralised as an escaped sequence", html.contains("<\\/style"))
    }

    @Test
    fun aMermaidFenceBecomesADiagramNotACodeBlock() {
        val html = MarkdownRenderer.render(mermaidFence)
        assertTrue("mermaid container", html.contains("class=\"mermaid\""))
        // The stylesheet legitimately names every hl-* class, so assert on markup instead.
        assertFalse("must not also render as highlighted code", html.contains("class=\"hl-"))
        assertTrue("the source stays readable", html.contains("A--&gt;B"))
    }

    @Test
    fun anOrdinaryFenceIsStillACodeBlock() {
        val html = MarkdownRenderer.render(jsFence)
        assertTrue(html.contains("class=\"code-block\""))
        assertFalse("only mermaid becomes a diagram", html.contains("class=\"mermaid\""))
    }

    @Test
    fun theDiagramEngineIsOnlyEmbeddedWhenItIsNeeded() {
        val withoutLib = MarkdownRenderer.render(mermaidFence)
        assertFalse("no engine, no initialise call", withoutLib.contains("mermaid.initialize"))

        val withLib = MarkdownRenderer.render(mermaidFence, mermaidJs = "/* mermaid */")
        assertTrue("the engine is embedded", withLib.contains("/* mermaid */"))
        assertTrue("and started", withLib.contains("mermaid.initialize"))
    }

    @Test
    fun theEditorScrollHookIsAlwaysPresent() {
        val html = MarkdownRenderer.render("plain")
        assertTrue(
            "the editor drives this function by name, so it must exist in every document",
            html.contains("vncodeScrollToRatio"),
        )
    }
}
