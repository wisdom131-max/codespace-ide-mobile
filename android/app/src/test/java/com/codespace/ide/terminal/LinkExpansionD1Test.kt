package com.codespace.ide.terminal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DEVICE DEFECT D1 (advisor round 2026-10-19, device-observed): tapping the
 * START of a spaces path fails ("[Link] File not found: \"/sdcard/My") while
 * tapping a later part opens the file — position-dependent resolution.
 *
 * Root cause (READ, LinkExpansion.kt windows()): the chunk window growth is
 * ASYMMETRIC — MAX_LEFT_CHUNKS=6 but MAX_RIGHT_CHUNKS=3. The device's quoted
 * absolute path spans SIX chunks:
 *   ["/sdcard/My] [codespace] [app] [1/absolute] [space] [test.txt"]
 * A tap on chunk 0 can only reach chunks 0..3 (1 + MAX_RIGHT_CHUNKS=3); the
 * full path needs right=5. Taps on LATER chunks worked because left growth
 * (6) reaches back to chunk 0. The tests below pin the fixed behavior — they
 * are RED against the 3-cap (commit A1, tests first) and GREEN after the
 * symmetric-cap + explicit-span fix (commit A2).
 *
 * Per the advisor: taps at BEGINNING, MIDDLE and END of each form
 * (quoted, shell-escaped, relative, absolute, :line, :line:col).
 */
class LinkExpansionD1Test {

    private class FakeFs : LinkFileProbe {
        val entries = mutableMapOf<String, Boolean>()
        override fun exists(path: String): Boolean = entries.containsKey(norm(path))
        override fun isDirectory(path: String): Boolean = entries[norm(path)] ?: false
        fun add(path: String, dir: Boolean = false) { entries[norm(path)] = dir }
        private fun norm(p: String): String {
            var r = p.replace("//", "/")
            while (r.length > 1 && r.endsWith("/")) r = r.substring(0, r.length - 1)
            return r
        }
    }

    @Before fun resetCache() = LinkExistenceCache.clearAll()
    @After fun cleanCache() = LinkExistenceCache.clearAll()

    private val appDir = "/sdcard/My codespace app 1"
    private val quotedAbs = "$appDir/absolute space test.txt"
    private val quotedRel = "relative space test.txt"
    private val dirChild = "space dir/child file.txt"

    private fun ctx(tracked: String? = null) = LinkExpansion.SelectionContext(
        cwdCtx = IdeTerminalBridge.CwdContext(tracked633 = tracked, initialDir = null),
        procCwd = null, roots = emptyList(),
    )

    private fun at(row: String, sub: String): Int = row.indexOf(sub).let { if (it >= 0) it else 0 }

    // ── quoted absolute: taps at beginning / middle / end ────────────────

    @Test fun quotedAbs_tapAtOpeningQuote_resolvesWholePath() {
        val fs = FakeFs().also { it.add(quotedAbs) }
        val row = "echo '" + quotedAbs + "'"
        val r = LinkExpansion.select(row, at(row, "'" + quotedAbs), ctx(), fs)
        assertTrue("expected Found, got $r", r is LinkExpansion.Result.Found)
        assertEquals(quotedAbs, (r as LinkExpansion.Result.Found).hit.path)
    }

    @Test fun quotedAbs_tapAtFirstCharInsideQuotes_resolvesWholePath() {
        val fs = FakeFs().also { it.add(quotedAbs) }
        val row = "echo '" + quotedAbs + "'"
        val r = LinkExpansion.select(row, at(row, quotedAbs), ctx(), fs)
        assertTrue("expected Found, got $r", r is LinkExpansion.Result.Found)
        assertEquals(quotedAbs, (r as LinkExpansion.Result.Found).hit.path)
    }

    @Test fun quotedAbs_tapMiddle_resolvesWholePath() {
        val fs = FakeFs().also { it.add(quotedAbs) }
        val row = "echo '" + quotedAbs + "'"
        val r = LinkExpansion.select(row, at(row, "space"), ctx(), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        assertEquals(quotedAbs, (r as LinkExpansion.Result.Found).hit.path)
    }

    @Test fun quotedAbs_tapEnd_resolvesWholePath() {
        val fs = FakeFs().also { it.add(quotedAbs) }
        val row = "echo '" + quotedAbs + "'"
        val r = LinkExpansion.select(row, at(row, "test.txt"), ctx(), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        assertEquals(quotedAbs, (r as LinkExpansion.Result.Found).hit.path)
    }

    // ── shell-escaped absolute: beginning / middle / end ────────────────

    private fun escaped(appPath: String): String = appPath.replace(" ", "\\ ")

    @Test fun escapedAbs_tapAtStart_resolvesWholePath() {
        val fs = FakeFs().also { it.add(quotedAbs) }
        val row = "echo " + escaped(quotedAbs)
        val r = LinkExpansion.select(row, at(row, "/sdcard"), ctx(), fs)
        assertTrue("expected Found, got $r", r is LinkExpansion.Result.Found)
        assertEquals(quotedAbs, (r as LinkExpansion.Result.Found).hit.path)
    }

    @Test fun escapedAbs_tapMiddle_resolvesWholePath() {
        val fs = FakeFs().also { it.add(quotedAbs) }
        val row = "echo " + escaped(quotedAbs)
        val r = LinkExpansion.select(row, at(row, "space"), ctx(), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        assertEquals(quotedAbs, (r as LinkExpansion.Result.Found).hit.path)
    }

    @Test fun escapedAbs_tapEnd_resolvesWholePath() {
        val fs = FakeFs().also { it.add(quotedAbs) }
        val row = "echo " + escaped(quotedAbs)
        val r = LinkExpansion.select(row, at(row, "test.txt"), ctx(), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        assertEquals(quotedAbs, (r as LinkExpansion.Result.Found).hit.path)
    }

    // ── relative 3-chunk: beginning / middle / end ──────────────────────

    @Test fun relative_tapAtStart_resolvesWholePath() {
        val fs = FakeFs().also { it.add("/tracked/" + quotedRel) }
        val row = "cat " + quotedRel
        val r = LinkExpansion.select(row, at(row, "relative"), ctx("/tracked"), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        assertEquals("/tracked/" + quotedRel, (r as LinkExpansion.Result.Found).hit.path)
    }

    @Test fun relative_tapMiddle_resolvesWholePath() {
        val fs = FakeFs().also { it.add("/tracked/" + quotedRel) }
        val row = "cat " + quotedRel
        val r = LinkExpansion.select(row, at(row, "space"), ctx("/tracked"), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        assertEquals("/tracked/" + quotedRel, (r as LinkExpansion.Result.Found).hit.path)
    }

    @Test fun relative_tapEnd_resolvesWholePath() {
        val fs = FakeFs().also { it.add("/tracked/" + quotedRel) }
        val row = "cat " + quotedRel
        val r = LinkExpansion.select(row, at(row, "test.txt"), ctx("/tracked"), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        assertEquals("/tracked/" + quotedRel, (r as LinkExpansion.Result.Found).hit.path)
    }

    // ── directory/child + :line and :line:col forms ─────────────────────

    @Test fun dirChild_withLine_tapAtDirectoryPart_resolvesWholePath() {
        val fs = FakeFs().also { it.add("/tracked/" + dirChild) }
        val row = "cat " + dirChild + ":2"
        val r = LinkExpansion.select(row, at(row, "space dir"), ctx("/tracked"), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        val h = (r as LinkExpansion.Result.Found).hit
        assertEquals("/tracked/" + dirChild, h.path)
        assertEquals(2, h.line)
    }

    @Test fun dirChild_withLineCol_tapAtStart_resolvesWholePath() {
        val fs = FakeFs().also { it.add("/tracked/" + dirChild) }
        val row = "cat " + dirChild + ":2:3"
        val r = LinkExpansion.select(row, at(row, "space"), ctx("/tracked"), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        val h = (r as LinkExpansion.Result.Found).hit
        assertEquals("/tracked/" + dirChild, h.path)
        assertEquals(2, h.line)
        assertEquals(3, h.col)
    }

    @Test fun quotedAbs_withLine_tapAtStart_resolvesWholePathWithLine() {
        val fs = FakeFs().also { it.add(quotedAbs) }
        val row = "cat '" + quotedAbs + ":2'"
        val r = LinkExpansion.select(row, at(row, quotedAbs), ctx(), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        val h = (r as LinkExpansion.Result.Found).hit
        assertEquals(quotedAbs, h.path)
        assertEquals(2, h.line)
    }

    // ── the RED window-cap pin: from a tap on chunk 0, the full 6-chunk
    //    span must be reachable as ONE window (fails at MAX_RIGHT_CHUNKS=3) ──

    @Test fun windows_fromTapAtStartOfSixChunkPath_includeFullSpan() {
        val row = "echo '" + quotedAbs + "'"
        val tap = at(row, quotedAbs)
        val wins = LinkExpansion.windows(row, tap)
        val fullPath = quotedAbs
        val reached = wins.any { w -> w.text.replace("'", "").contains(fullPath) }
        assertTrue(
            "no window from a start-tap reached the full 6-chunk path (right cap = " +
                LinkExpansion.MAX_RIGHT_CHUNKS + ", windows=" + wins.map { it.text.take(30) },
            reached,
        )
    }

    // ── performance guard: the wider growth stays bounded ─────────────────

    @Test fun windowGeneration_withSymmetricCaps_isStillBoundedAndFast() {
        val row = buildString { repeat(120) { append("chunk$it ") }; append("1.py") }
        val t0 = System.currentTimeMillis()
        repeat(200) { LinkExpansion.windows(row, 0) }
        val wins = LinkExpansion.windows(row, 0)
        assertTrue(System.currentTimeMillis() - t0 < 2000)
        assertTrue(wins.size <= LinkExpansion.MAX_WINDOWS)
    }
}
