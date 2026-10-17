package com.codespace.ide.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BATCH6-P3 (advisor D): the pure decision core of the verified-install gate —
 * manifest build/compare, the 3-per-day repair cap, backoff, and the host-side
 * tree walk (real temp dirs).
 */
class VerifiedInstallTest {

    private fun meta(path: String, size: Long) = VerifiedInstall.FileMeta(path, size)

    @Test fun manifest_isSortedAndStable() {
        val m = VerifiedInstall.buildManifest(
            listOf(meta("bin/jdtls", 100L), meta("plugins/a.jar", 5L), meta("plugins/b.jar", 6L)))
        assertEquals("bin/jdtls 100\nplugins/a.jar 5\nplugins/b.jar 6", m)
    }

    @Test fun manifestMatch_sameTree_true() {
        val files = listOf(meta("bin/x", 1L), meta("y.jar", 2L))
        val stored = VerifiedInstall.buildManifest(files)
        assertTrue(VerifiedInstall.manifestMatches(stored, files))
    }

    @Test fun manifestMatch_sizeDrift_false() {
        // A truncated/partially-overwritten file: same paths, different size —
        // exactly the "corrupted install" the owner's tar short-read produced.
        val stored = VerifiedInstall.buildManifest(listOf(meta("plugins/a.jar", 5000L)))
        assertFalse(VerifiedInstall.manifestMatches(stored, listOf(meta("plugins/a.jar", 1200L))))
    }

    @Test fun manifestMatch_missingFile_false() {
        val stored = VerifiedInstall.buildManifest(
            listOf(meta("a.jar", 1L), meta("b.jar", 2L)))
        assertFalse(VerifiedInstall.manifestMatches(stored, listOf(meta("a.jar", 1L))))
    }

    @Test fun manifestMatch_extraFile_false() {
        val stored = VerifiedInstall.buildManifest(listOf(meta("a.jar", 1L)))
        assertFalse(VerifiedInstall.manifestMatches(stored, listOf(meta("a.jar", 1L), meta("extra", 9L))))
    }

    @Test fun manifestMatch_blankStored_false() {
        assertFalse(VerifiedInstall.manifestMatches(" ", listOf(meta("a", 1L))))
    }

    @Test fun attemptWindow_dropsOldAttempts() {
        val now = 100L * VerifiedInstall.ATTEMPT_WINDOW_MS
        val attempts = listOf(
            now - VerifiedInstall.ATTEMPT_WINDOW_MS - 1_000L, // outside
            now - VerifiedInstall.ATTEMPT_WINDOW_MS + 5_000L,  // inside
            now - 1_000L,                                      // inside
        )
        assertEquals(2, VerifiedInstall.windowAttempts(attempts, now).size)
    }

    @Test fun repairCap_threePerDay() {
        val now = 500L * VerifiedInstall.ATTEMPT_WINDOW_MS
        // RED FIX (CI 39066058431): attempt #3 must also clear the 30-minute
        // backoff after attempt #2 (repairAllowed checks BOTH the cap and the
        // elapsed time) — the original expectation ignored the backoff rule.
        val fresh = listOf(now - 40L * 60_000L, now - 35L * 60_000L)
        assertTrue(VerifiedInstall.repairAllowed(fresh, now))
        val capped = fresh + (now - 30_000L)          // three today
        assertFalse(VerifiedInstall.repairAllowed(capped, now))
    }

    @Test fun backoff_blocksImmediateRetry() {
        val now = 500L * VerifiedInstall.ATTEMPT_WINDOW_MS
        val justFailed = listOf(now - 60_000L) // 1 minute ago
        // First backoff is 15 minutes — an immediate retry must NOT run.
        assertFalse(VerifiedInstall.repairAllowed(justFailed, now))
        assertTrue(VerifiedInstall.repairAllowed(justFailed, now + VerifiedInstall.BACKOFF_BASE_MS + 1_000L))
    }

    @Test fun walk_realTempTree_pathsAndSizes() {
        val root = File(System.getProperty("java.io.tmpdir"),
            "vi-walk-" + System.nanoTime())
        val plugins = File(root, "plugins")
        assert(plugins.mkdirs())
        File(root, "bin").apply { mkdirs() }
        File(root, "bin/jdtls").apply { writeText("hello") }   // 5 bytes
        File(plugins, "a.jar").apply { writeText("12345678") } // 8 bytes
        val walked = VerifiedInstall.walk(root)
        assertEquals(
            listOf("bin/jdtls" to 5L, "plugins/a.jar" to 8L),
            walked.map { it.path to it.size })
        root.deleteRecursively()
    }
}
