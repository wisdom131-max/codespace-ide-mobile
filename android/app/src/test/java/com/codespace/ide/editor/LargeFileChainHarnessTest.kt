package com.codespace.ide.editor

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring harness this work needed and did not have: one test that walks the WHOLE chain the
 * device walks for the file that started all of this - a 13,000-line file.
 *
 * It is not a Compose test (there is no Robolectric in this project yet), so it drives the real
 * decision functions in the same order the screen does: fit decision -> slice -> registry -> writers
 * -> navigation -> staleness. Every step here is the production code, not a copy of its arithmetic.
 *
 * If any of these assertions breaks, the device scenario breaks.
 */
class LargeFileChainHarnessTest {

    private val root: File = File(System.getProperty("java.io.tmpdir"), "vn-chain-${System.nanoTime()}").apply { mkdirs() }
    private val target: File = File(root, "big13k.js")

    @After
    fun tearDown() {
        PreviewRegistry.clear()
        root.deleteRecursively()
    }

    private fun writeBigFile(lines: Int = 13_000) {
        val sb = StringBuilder(lines * 40)
        for (i in 0 until lines) sb.append("const v").append(i).append(" = ").append(i).append(";\n")
        target.writeText(sb.toString())
    }

    private fun metrics() = EditorLayoutLimits.Metrics(
        fontSizeSp = 13f,
        density = 2.75f,
        fontScale = 1f,
        widthPx = 1080,
    )

    @Test
    fun aThirteenThousandLineFileIsPreviewedPagedAndNeverWritten() {
        writeBigFile()
        val before = target.readBytes()
        val whole = target.readText()
        val lines = EditorLayoutLimits.lineCount(whole)

        // 1. THE OPEN DECISION: it must not fit one text field, so it opens as a preview.
        assertEquals(13_000, lines)
        assertFalse(
            "13,000 lines must not fit the editor at 13sp / density 2.75",
            EditorLayoutLimits.fits(lines, EditorLayoutLimits.averageLineChars(whole, lines), metrics(), false),
        )
        val pageLines = EditorLayoutLimits.previewLineBudget(60, metrics(), wrap = false)
        assertTrue("the guard must produce a usable page size (was $pageLines)", pageLines > 500)

        // 2. THE SLICE: page 1 is what the reader sees, and it is a real part of the file.
        val page1 = EditorLayoutLimits.previewSlice(whole, 0, pageLines)
        assertTrue("page 1 starts at the top of the file", whole.startsWith(page1.trimEnd('\n')))
        assertEquals("the page holds exactly the budgeted lines", pageLines, EditorLayoutLimits.lineCount(page1))

        // 3. THE REGISTRY: the preview path is known to every non-UI consumer.
        PreviewRegistry.replaceWith(mapOf(target.absolutePath to page1))
        assertTrue(PreviewRegistry.isPreview(target.absolutePath))
        assertTrue(PreviewRegistry.isSliceOf(target.absolutePath, page1))

        // 4. THE WRITERS: every editor-side write of that slice is refused, once and repeatedly.
        repeat(3) {
            assertFalse(
                "save of a preview slice must be refused",
                com.codespace.ide.ui.panes.writeTabToDisk(target.absolutePath, page1),
            )
        }
        assertEquals("the file must be byte-identical", before.toList(), target.readBytes().toList())

        // 5. NAVIGATION: a search hit at line 9,000 lands on the page holding it.
        val jump = PreviewJump.plan(requestedLine = 9_000, pageLines = pageLines, isPreview = true, currentPageStart = 0)
        assertEquals(pageLines, jump.pageStart)
        assertTrue(jump.pageLine in 1..pageLines)
        assertEquals(9_000, PreviewNavigation.absoluteLineFor(jump.pageStart, jump.pageLine))
        val page2 = EditorLayoutLimits.previewSlice(whole, jump.pageStart, pageLines)
        assertTrue(
            "the page the jump opens must actually contain line 9000",
            page2.lineSequence().drop(jump.pageLine - 1).first().startsWith("const v8999"),
        )

        // 6. STALENESS: the agent edits the REAL file; the preview notices and reloads the same page.
        val stampBefore = PreviewStaleness.Stamp(target.lastModified(), target.length())
        Thread.sleep(10)
        target.writeText(whole + "const appended = true;\n")
        val stampAfter = PreviewStaleness.Stamp(target.lastModified(), target.length())
        assertEquals(
            PreviewStaleness.Action.RELOAD,
            PreviewStaleness.decide(stampBefore, stampAfter, jump.pageStart, 13_001),
        )

        // 7. ...and the writer guard still holds after the external change.
        assertFalse(
            "the slice is still a slice after the file changed underneath it",
            com.codespace.ide.ui.panes.writeTabToDisk(target.absolutePath, page1),
        )

        // 8. THE AGENT's whole-file write is still allowed: it is the real content, not the slice.
        val agentContent = target.readText() + "const agentAdded = 1;\n"
        assertTrue(
            "the agent must be able to write the real file",
            com.codespace.ide.ui.panes.writeTabToDisk(target.absolutePath, agentContent),
        )
        assertNotNull(target.readText())
    }

    @Test
    fun aFileThatShrinksBelowThePageProducesTheStaleBannerNotASilentJump() {
        writeBigFile()
        val whole = target.readText()
        val pageLines = EditorLayoutLimits.previewLineBudget(60, metrics(), wrap = false)
        PreviewRegistry.replaceWith(mapOf(target.absolutePath to EditorLayoutLimits.previewSlice(whole, pageLines, pageLines)))

        val before = PreviewStaleness.Stamp(1L, 500_000L)
        val after = PreviewStaleness.Stamp(2L, 40_000L)
        assertEquals(
            PreviewStaleness.Action.NOTIFY,
            PreviewStaleness.decide(before, after, pageStartLine = pageLines, currentLineCount = 4_000),
        )
        assertTrue(PreviewStaleness.staleMessage("big13k.js").contains("shorter than the page"))
    }
}
