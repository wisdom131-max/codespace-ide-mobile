package com.codespace.ide.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH11-S1 (advisor approved design items 3-4): the resolve chain's cwd
 * source ORDER and honest reason classification, JVM-tested against a fake
 * file system (the LinkFileProbe seam) — no Android Context, no real disk.
 *
 * Owner device evidence this pins:
 *  - "trying session cwd=/" — the /proc cwd reported "/" and the resolver probed
 *    it as a base. "/" and "/root" must now be SKIPPED, visibly (never silently).
 *  - "[Link] Lookup timed out: 1/1.py (storage busy)" for a token that simply
 *    did not exist — NOT_FOUND must be distinguishable from TIMEOUT.
 */
class LinkResolverS1Test {

    /** In-memory fake file system: path -> isDirectory. */
    private class FakeFs(private val entries: Map<String, Boolean>) : LinkFileProbe {
        val probed = mutableListOf<String>()
        override fun exists(path: String): Boolean {
            probed.add(path)
            return entries.containsKey(normalize(path))
        }
        override fun isDirectory(path: String): Boolean {
            return entries[normalize(path)] ?: false
        }
        private fun normalize(p: String): String {
            var r = p.replace("//", "/")
            while (r.length > 1 && r.endsWith("/")) r = r.substring(0, r.length - 1)
            return r
        }
    }

    private val appDir = "/sdcard/My codespace app 1"

    @Test fun cwdOrder_633_trackedCwd_wins_over_proc_and_initial() {
        val fs = FakeFs(mapOf(appDir + "/src/1.py" to false))
        val r = IdeTerminalBridge.resolveTappedFileLink(
            null, null, "src/1.py", projectId = null, lockedRoot = null, diagId = null,
            cwdCtx = IdeTerminalBridge.CwdContext(
                tracked633 = appDir,
                initialDir = "/some/other/initial",
            ),
            probe = fs,
        )
        assertEquals(appDir + "/src/1.py", r?.first?.absolutePath)
    }

    @Test fun cwdOrder_initialDir_used_when633_null() {
        val fs = FakeFs(mapOf("/initial/dir/1.py" to false))
        val r = IdeTerminalBridge.resolveTappedFileLink(
            null, null, "1.py", projectId = null, lockedRoot = null, diagId = null,
            cwdCtx = IdeTerminalBridge.CwdContext(tracked633 = null, initialDir = "/initial/dir"),
            probe = fs,
        )
        assertEquals("/initial/dir/1.py", r?.first?.absolutePath)
    }

    @Test fun rootAndBareRoot_are_neverUsedAsCwdBase() {
        // 633 says "/" (bad), initial says "/root" (legacy placeholder): BOTH must be
        // skipped, no probe may hit "/1.py" or "/root/1.py" — and the result is an
        // honest NOT_FOUND-shaped null (resolver level), not a wrong hit.
        val fs = FakeFs(mapOf("/1.py" to false, "/root/1.py" to false))
        val r = IdeTerminalBridge.resolveTappedFileLink(
            null, null, "1.py", projectId = null, lockedRoot = null, diagId = null,
            cwdCtx = IdeTerminalBridge.CwdContext(tracked633 = "/", initialDir = "/root"),
            probe = fs,
        )
        assertEquals(null, r)
        assertTrue(fs.probed.none { it == "/1.py" || it == "/root/1.py" })
    }

    @Test fun noCwdSources_atAll_skipsBranch_withoutProbingBareRoot() {
        val fs = FakeFs(emptyMap())
        val r = IdeTerminalBridge.resolveTappedFileLink(
            null, null, "1.py", projectId = null, lockedRoot = null, diagId = null,
            cwdCtx = null, probe = fs,
        )
        assertEquals(null, r)
        assertTrue(fs.probed.none { it == "/1.py" })
    }

    @Test fun absoluteHostPath_resolves_beforeCwdBranches() {
        val fs = FakeFs(mapOf("/abs/dir/file.kt" to false))
        val r = IdeTerminalBridge.resolveTappedFileLink(
            null, null, "/abs/dir/file.kt", projectId = null, lockedRoot = null, diagId = null,
            cwdCtx = IdeTerminalBridge.CwdContext(tracked633 = appDir),
            probe = fs,
        )
        assertEquals("/abs/dir/file.kt", r?.first?.absolutePath)
        // No :line suffix: the legacy marker is negative (parse -1 -> 0-based -2);
        // the editor clamps. Pin "negative = no line", not the exact legacy value.
        assertTrue((r?.second ?: 0) < 0)
    }

    @Test fun notFoundToken_reasonIsNotFound_notTimeout() {
        // The async wrapper classifies: a completed resolve that matched nothing is
        // NOT_FOUND; only a real budget overrun is TIMEOUT (see timeout test below).
        val r = kotlinx.coroutines.runBlocking {
            IdeTerminalBridge.resolveTappedFileLinkAsync(
                null, null, "zz_missing.py:9",
                cwdCtx = IdeTerminalBridge.CwdContext(tracked633 = appDir),
                probe = FakeFs(emptyMap()),
            )
        }
        assertEquals(LinkResolveReason.NOT_FOUND, r.reason)
        assertEquals(null, r.file)
    }

    @Test fun slowProbes_reasonIsTimeout() {
        // 3s budget vs. 400ms probes: branch 1 (absolute) + branch 3 (633, then
        // initialDir) per candidate, and the 2-space token forces THREE candidates
        // (linkCandidates leading-chunk drop) = 9 probes x 400ms = 3.6s — the
        // budget is exceeded deterministically before the chain completes.
        // TIMEOUT (the ONLY honest "storage may be busy" verdict) is the result.
        // NOTE: blocking probes cannot be preempted — the block finishes past
        // the budget and the measured wall time is what classifies (same
        // approximate-budget reality as FUSE stalls on device).
        val r = kotlinx.coroutines.runBlocking {
            val slowCertain = object : LinkFileProbe {
                override fun exists(path: String): Boolean { Thread.sleep(400); return false }
                override fun isDirectory(path: String): Boolean { Thread.sleep(400); return false }
            }
            IdeTerminalBridge.resolveTappedFileLinkAsync(
                null, null, "zz dir/zz missing.py:9",
                cwdCtx = IdeTerminalBridge.CwdContext(tracked633 = appDir, initialDir = "/initial"),
                probe = slowCertain,
            )
        }
        assertEquals(LinkResolveReason.TIMEOUT, r.reason)
    }

    @Test fun throwingProbe_reasonIsError_withClassName() {
        val bomb = object : LinkFileProbe {
            override fun exists(path: String): Boolean { throw IllegalStateException("disk on fire") }
            override fun isDirectory(path: String): Boolean { throw IllegalStateException("disk on fire") }
        }
        val r = kotlinx.coroutines.runBlocking {
            IdeTerminalBridge.resolveTappedFileLinkAsync(
                null, null, "/abs/zz.kt:3", probe = bomb,
            )
        }
        assertEquals(LinkResolveReason.ERROR, r.reason)
        assertEquals("IllegalStateException", r.errorClass)
    }
}
