package com.codespace.ide.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner order 2026-11-06, item 1(d): a synthetic 20,000-file tree with a .git-like folder, to
 * prove the walk is bounded and that the folders which froze project open are never entered.
 *
 * The fixture is built once per test into a temp dir. Nothing here touches Android: the walk is
 * pure `java.io.File` plus a clock, which is exactly why it can be measured in CI.
 */
class WatchScanTest {

    private fun tree(
        sourceFiles: Int,
        gitObjects: Int,
        nodeModules: Int,
    ): File {
        val root = File.createTempFile("watchscan", "").let { f ->
            f.delete(); f.mkdirs(); f
        }
        var written = 0
        // Real source spread over 20 folders.
        for (d in 0 until 20) {
            val dir = File(root, "src$d").apply { mkdirs() }
            for (f in 0 until sourceFiles / 20) {
                File(dir, "F$f.kt").writeText("class C$f")
                written++
            }
        }
        // A .git-like folder with loose objects.
        val obj = File(root, ".git/objects/ab").apply { mkdirs() }
        for (f in 0 until gitObjects) File(obj, "o$f").writeText("x")
        // node_modules, the other classic.
        for (p in 0 until 20) {
            val pkg = File(root, "node_modules/pkg$p").apply { mkdirs() }
            for (f in 0 until nodeModules / 20) File(pkg, "index.js").writeText("//")
        }
        assertEquals(sourceFiles, written)
        return root
    }

    private fun accept(f: File) = f.extension.lowercase() in setOf("kt", "py", "js", "ts")

    @Test
    fun aTreeWithGitAndNodeModulesIsNeverWalkedIntoThem() {
        val root = tree(sourceFiles = 2_000, gitObjects = 2_000, nodeModules = 2_000)
        val r = WatchScan.scan(root, maxFiles = 8_000, budgetMs = 10_000, accept = ::accept)
        assertTrue("the source files are found", r.files.size >= 2_000)
        assertTrue(
            "no path under .git is ever accepted",
            r.files.keys.none { it.contains("/.git/") },
        )
        assertTrue(
            "no path under node_modules is ever accepted",
            r.files.keys.none { it.contains("/node_modules/") },
        )
        assertFalse("2,000 source files are under the cap", r.capped)
        println("[perf] watchscan: " + r.files.size + " files, " + r.dirsVisited +
            " dirs, " + r.elapsedMs + "ms")
        root.deleteRecursively()
    }

    @Test
    fun theCapStopsTheWalkInsteadOfWalkingEverything() {
        val root = tree(sourceFiles = 2_000, gitObjects = 0, nodeModules = 0)
        val r = WatchScan.scan(root, maxFiles = 500, budgetMs = 10_000, accept = ::accept)
        assertEquals("the cap is the ceiling", 500, r.files.size)
        assertTrue("and it says so", r.capped)
        root.deleteRecursively()
    }

    @Test
    fun theTimeBudgetStopsA20kFileTree() {
        // 20,000 source files: the fixture the advisor asked for.
        val root = tree(sourceFiles = 20_000, gitObjects = 3_000, nodeModules = 3_000)
        val r = WatchScan.scan(root, maxFiles = 8_000, budgetMs = 1_500, accept = ::accept)
        println("[perf] watchscan 20k: " + r.files.size + " files, " + r.dirsVisited +
            " dirs, " + r.elapsedMs + "ms, capped=" + r.capped)
        assertTrue("never returns more than the cap", r.files.size <= 8_000)
        assertTrue("and never runs past its budget by more than one directory listing",
            r.elapsedMs < 1_500 + 500)
        root.deleteRecursively()
    }
}
