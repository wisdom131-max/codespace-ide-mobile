package com.codespace.ide.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH 7 — the parse, which is where a chat renderer goes wrong.
 *
 * The cases that matter are the streaming ones (an unclosed fence, a half-typed **) and the
 * fidelity ones (code must come back byte for byte), because those are the failures a user
 * notices and a screenshot would not reveal.
 */
class MarkdownParserTest {

    private fun code(blocks: List<MdBlock>) = blocks.filterIsInstance<MdBlock.Code>().single()

    @Test
    fun aPlainReplyIsOneParagraph() {
        val blocks = MarkdownParser.parse("Hello there")
        assertEquals(1, blocks.size)
        assertEquals("Hello there", (blocks[0] as MdBlock.Paragraph).spans.joinToString("") { it.text })
    }

    @Test
    fun wrappedLinesJoinIntoOneParagraph() {
        val blocks = MarkdownParser.parse("one line\nstill the same thought\n\nnew paragraph")
        assertEquals(2, blocks.size)
        assertEquals("one line still the same thought",
            (blocks[0] as MdBlock.Paragraph).spans.joinToString("") { it.text })
    }

    @Test
    fun headingsCarryTheirLevel() {
        val blocks = MarkdownParser.parse("# Big\n## Smaller\n###### Tiny")
        assertEquals(3, blocks.size)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertEquals(2, (blocks[1] as MdBlock.Heading).level)
        assertEquals(6, (blocks[2] as MdBlock.Heading).level)
    }

    @Test
    fun bulletsAreRecognisedWithTheirIndent() {
        val blocks = MarkdownParser.parse("- one\n  - nested\n* two")
        val bullets = blocks.filterIsInstance<MdBlock.Bullet>()
        assertEquals(3, bullets.size)
        assertEquals(0, bullets[0].indent)
        assertEquals("one", bullets[0].spans.joinToString("") { it.text })
        assertEquals(1, bullets[1].indent)
    }

    @Test
    fun quotesAreRecognised() {
        val blocks = MarkdownParser.parse("> quoted words")
        assertEquals("quoted words", (blocks[0] as MdBlock.Quote).spans.joinToString("") { it.text })
    }

    @Test
    fun aFencedBlockKeepsItsLanguageAndItsExactContent() {
        val md = "```kotlin\nval a = 1\n\tval b = \"  spaced  \"\n```"
        val block = code(MarkdownParser.parse(md))
        assertEquals("kotlin", block.language)
        assertEquals("val a = 1\n\tval b = \"  spaced  \"", block.code)
        assertTrue(block.closed)
    }

    @Test
    fun anUnclosedFenceIsStillACodeBlock() {
        // This is the normal state of a reply arriving a few characters at a time.
        val block = code(MarkdownParser.parse("```python\nprint(1)"))
        assertEquals("python", block.language)
        assertEquals("print(1)", block.code)
        assertFalse("the UI may say it is still streaming", block.closed)
    }

    @Test
    fun codeIsNeverRewritten() {
        val inner = "  if (x) {\n    y()\n  }"
        val block = code(MarkdownParser.parse("```\n" + inner + "\n```"))
        assertEquals(inner, block.code)
    }

    @Test
    fun aShorterFenceInsideCodeDoesNotCloseTheBlockEarly() {
        // A ```` block that documents ``` code: the inner fence must not end it.
        val md = "````md\n```js\nx\n```\n````"
        val block = code(MarkdownParser.parse(md))
        assertTrue(block.closed)
        assertEquals("```js\nx\n```", block.code)
    }

    @Test
    fun boldItalicAndCodeSpansAreParsed() {
        val spans = MarkdownParser.parseInline("a **bold** b *ital* c `code` d")
        val joined = spans.joinToString("") { it.text }
        assertEquals("a bold b ital c code d", joined)
        assertTrue(spans.any { it.bold && it.text == "bold" })
        assertTrue(spans.any { it.italic && it.text == "ital" })
        assertTrue(spans.any { it.code && it.text == "code" })
    }

    @Test
    fun anUnmatchedMarkerStaysLiteral() {
        // A half-typed ** arrives constantly while streaming; it must not be eaten.
        val spans = MarkdownParser.parseInline("2 * 3 = 6 and 4 ** 2")
        val joined = spans.joinToString("") { it.text }
        assertTrue(joined.contains("2 * 3 = 6"))
        assertTrue(joined.contains("4 ** 2"))
    }

    @Test
    fun anAsteriskInsideCodeIsNotEmphasis() {
        val spans = MarkdownParser.parseInline("use `a * b` here")
        val code = spans.single { it.code }
        assertEquals("a * b", code.text)
    }

    @Test
    fun emptyInputIsNoBlocks() {
        assertTrue(MarkdownParser.parse("").isEmpty())
    }

    @Test
    fun aBlankLineAloneIsNoBlocks() {
        assertTrue(MarkdownParser.parse("\n\n   \n").isEmpty())
    }

    @Test
    fun parseIsStableAcrossCalls() {
        val md = "# t\n- a\n\n```js\nx\n```"
        assertEquals(MarkdownParser.flatten(MarkdownParser.parse(md)), MarkdownParser.flatten(MarkdownParser.parse(md)))
    }
}
