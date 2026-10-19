package com.codespace.ide.terminal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BATCH11-S3 (advisor approved design, stage 3): EXISTENCE-GATED EXPANSION —
 * the iTerm2 tap-time model, JVM-tested against an in-memory fake file system.
 * This is the fix for the owner's Bug 1 evidence class: a plain "cd /sdcard/My
 * codespace app 1/1.py" row (NO :line suffix) can never produce a full-path
 * span token — the chunk window around the tap grows and FILE EXISTENCE picks
 * the winner. The span scanner itself stays untouched (space-free regex, no
 * nested quantifiers, BATCH7 hang rules).
 */
class LinkExpansionTest {

    private class FakeFs(private var entries: MutableMap<String, Boolean> = mutableMapOf()) : LinkFileProbe {
        var existsCalls = 0
        override fun exists(path: String): Boolean {
            existsCalls++
            return entries.containsKey(norm(path))
        }
        override fun isDirectory(path: String): Boolean = entries[norm(path)] ?: false
        fun add(path: String, dir: Boolean = false) { entries[norm(path)] = dir }
        fun remove(path: String) { entries.remove(norm(path)) }
        private fun norm(p: String): String {
            var r = p.replace("//", "/")
            while (r.length > 1 && r.endsWith("/")) r = r.substring(0, r.length - 1)
            return r
        }
    }

    @Before fun resetCache() = LinkExistenceCache.clearAll()
    @After fun cleanCache() = LinkExistenceCache.clearAll()

    private val appDir = "/sdcard/My codespace app 1"

    private fun ctx(
        tracked: String? = null,
        initial: String? = null,
        proc: String? = null,
        roots: List<String> = emptyList(),
    ) = LinkExpansion.SelectionContext(
        cwdCtx = IdeTerminalBridge.CwdContext(tracked633 = tracked, initialDir = initial),
        procCwd = proc,
        roots = roots,
    )

    private fun offsetOf(row: String, needle: String): Int = row.indexOf(needle)

    // ── the owner's Bug 1 shape: spaces, NO :line suffix ───────────────────

    @Test fun plainCdRow_withSpaces_resolvesFullFile() {
        val fs = FakeFs()
        fs.add(appDir + "/1.py")
        val row = "cd /sdcard/My codespace app 1/1.py"
        val r = LinkExpansion.select(row, offsetOf(row, "1.py"), ctx(tracked = "/nowhere"), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        assertEquals(appDir + "/1.py", (r as LinkExpansion.Result.Found).hit.path)
    }

    @Test fun promptRow_stillResolves_fullPathPastThePrompt() {
        val fs = FakeFs()
        fs.add(appDir + "/1.py")
        val row = "root@localhost:/sdcard/My codespace app 1# cat 1.py"
        val r = LinkExpansion.select(row, offsetOf(row, "1.py"), ctx(tracked = appDir), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        assertEquals(appDir + "/1.py", (r as LinkExpansion.Result.Found).hit.path)
    }

    @Test fun quotedPath_inRow_isExplicit() {
        val fs = FakeFs()
        fs.add(appDir + "/zz dir/1.py")
        val row = "cat '" + appDir + "/zz dir/1.py'"
        val r = LinkExpansion.select(row, offsetOf(row, "1.py"), ctx(), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        assertEquals(appDir + "/zz dir/1.py", (r as LinkExpansion.Result.Found).hit.path)
        assertTrue(r.hit.explicit)
    }

    @Test fun relativeToken_resolvesAgainstVerifiedCwd_633First() {
        val fs = FakeFs()
        fs.add("/tracked/cwd/zz file.txt")
        val row = "cat zz file.txt"
        val r = LinkExpansion.select(row, offsetOf(row, "file.txt"), ctx(tracked = "/tracked/cwd", initial = "/initial/dir"), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        assertEquals("/tracked/cwd/zz file.txt", (r as LinkExpansion.Result.Found).hit.path)
        assertTrue(r.hit.origin.startsWith("cwd-633"))
    }

    @Test fun lineCol_inRow_carriedThrough() {
        val fs = FakeFs()
        fs.add(appDir + "/1.py")
        val row = "python3 " + appDir + "/1.py:9:4"
        val r = LinkExpansion.select(row, offsetOf(row, "1.py"), ctx(), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        val h = (r as LinkExpansion.Result.Found).hit
        assertEquals(9, h.line); assertEquals(4, h.col)
    }

    // ── ranking rules ─────────────────────────────────────────────────────

    @Test fun directory_neverReturnedAsFile() {
        val fs = FakeFs()
        fs.add("/sdcard/My codespace app 1", dir = true)
        val row = "cd /sdcard/My codespace app 1"
        val r = LinkExpansion.select(row, offsetOf(row, "app"), ctx(), fs)
        assertTrue(r is LinkExpansion.Result.DirectoryOnly)
    }

    @Test fun duplicateAcrossRoots_isAmbiguous_notSilentlyPicked() {
        val fs = FakeFs()
        fs.add("/rootA/shared.txt"); fs.add("/rootB/shared.txt")
        val row = "cat shared.txt"
        val r = LinkExpansion.select(row, offsetOf(row, "shared.txt"), ctx(roots = listOf("/rootA", "/rootB")), fs)
        assertTrue(r is LinkExpansion.Result.Ambiguous)
        val paths = (r as LinkExpansion.Result.Ambiguous).matches.map { it.path }
        assertTrue(paths.contains("/rootA/shared.txt"))
        assertTrue(paths.contains("/rootB/shared.txt"))
    }

    @Test fun uniqueRoot_resolves() {
        val fs = FakeFs()
        fs.add("/rootB/only.txt")
        val row = "cat only.txt"
        val r = LinkExpansion.select(row, offsetOf(row, "only.txt"), ctx(roots = listOf("/rootA", "/rootB")), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        assertEquals("/rootB/only.txt", (r as LinkExpansion.Result.Found).hit.path)
    }

    @Test fun longestValidWindow_beatsNarrower() {
        // "1.py" exists at the cwd AND "zz dir/1.py" exists at the cwd — the
        // widest window resolving to a FILE wins; both must be probed honestly.
        val fs = FakeFs()
        fs.add("/tracked/1.py")
        fs.add("/tracked/zz dir/1.py")
        val row = "cat zz dir/1.py"
        val r = LinkExpansion.select(row, offsetOf(row, "1.py"), ctx(tracked = "/tracked"), fs)
        assertTrue(r is LinkExpansion.Result.Found)
        assertEquals("/tracked/zz dir/1.py", (r as LinkExpansion.Result.Found).hit.path)
    }

    @Test fun nonexistent_everywhere_isNotFound() {
        val fs = FakeFs()
        val row = "cat zz missing file.py"
        val r = LinkExpansion.select(row, offsetOf(row, "missing"), ctx(tracked = "/tracked"), fs)
        assertTrue(r is LinkExpansion.Result.NotFound)
    }

    // ── bounds ─────────────────────────────────────────────────────────────

    @Test fun windowGeneration_isBounded_andFast() {
        val row = buildString { repeat(120) { append("chunk$it ") }; append("1.py") }
        val wins = LinkExpansion.windows(row, row.length - 3)
        assertTrue(wins.size <= LinkExpansion.MAX_WINDOWS)
        val t0 = System.currentTimeMillis()
        repeat(200) { LinkExpansion.windows(row, row.length - 3) }
        assertTrue(System.currentTimeMillis() - t0 < 2000)  // no adversarial hang
    }

    @Test fun emptyRow_isNotFound() {
        val r = LinkExpansion.select("", 0, ctx(), FakeFs())
        assertTrue(r is LinkExpansion.Result.NotFound)
    }

    // ── the existence cache (advisor item 3) ─────────────────────────────

    @Test fun cache_positiveShortcircuits_probesWithinTtl() {
        val fs = FakeFs()
        fs.add("/x/1.py")
        LinkExistenceCache.exists(fs, "/x/1.py")
        val calls = fs.existsCalls
        LinkExistenceCache.exists(fs, "/x/1.py")
        LinkExistenceCache.exists(fs, "/x/1.py")
        assertEquals("positive entry must short-circuit inside the TTL", calls, fs.existsCalls)
    }

    @Test fun cache_negativeIsNeverTrustedAlone_fileCreatedMomentAgo_isFound() {
        val fs = FakeFs()
        assertFalse(LinkExistenceCache.exists(fs, "/x/new.py"))  // negative recorded
        fs.add("/x/new.py")  // ...the file appears a moment later
        assertTrue("a stale negative must be re-checked before not-found", LinkExistenceCache.exists(fs, "/x/new.py"))
    }

    @Test fun cache_clearedOnCommandComplete() {
        val fs = FakeFs()
        fs.add("/x/1.py")
        LinkExistenceCache.exists(fs, "/x/1.py")
        LinkExistenceCache.clearAll()  // what COMMAND_COMPLETE does
        val calls = fs.existsCalls
        LinkExistenceCache.exists(fs, "/x/1.py")
        assertEquals("after clearAll the entry must be re-probed", calls + 1, fs.existsCalls)
    }

    @Test fun cache_bounded_at256Entries() {
        val fs = FakeFs()
        repeat(300) { LinkExistenceCache.exists(fs, "/bulk/$it.txt") }
        assertTrue(LinkExistenceCache.size() <= LinkExistenceCache.MAX_ENTRIES)
    }
}
