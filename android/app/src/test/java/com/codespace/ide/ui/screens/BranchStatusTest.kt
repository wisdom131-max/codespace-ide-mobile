package com.codespace.ide.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The status bar branch label: the parsing half. The file resolution half needs a Context and a
 * rootfs, so it is exercised on device - but every way `.git/HEAD` can be shaped is here, because
 * a wrong label is indistinguishable from a broken repo in the one place a user checks.
 */
class BranchStatusTest {

    @Test
    fun `a normal head reads the branch name`() {
        assertEquals("main", BranchStatus.labelFromHead("ref: refs/heads/main\n"))
        assertEquals("feature/x", BranchStatus.labelFromHead("ref: refs/heads/feature/x"))
    }

    @Test
    fun `a detached head shows the short sha`() {
        val sha = "9f8a21b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8"
        assertEquals("9f8a21b", BranchStatus.labelFromHead(sha))
    }

    @Test
    fun `a short detached hash is left alone`() {
        assertEquals("9f8a21b", BranchStatus.labelFromHead("9f8a21b"))
    }

    @Test
    fun `a missing or empty head falls back to main`() {
        assertEquals(BranchStatus.FALLBACK, BranchStatus.labelFromHead(null))
        assertEquals(BranchStatus.FALLBACK, BranchStatus.labelFromHead(""))
        assertEquals(BranchStatus.FALLBACK, BranchStatus.labelFromHead("   \n"))
    }

    @Test
    fun `a truncated ref does not produce an empty label`() {
        assertEquals(BranchStatus.FALLBACK, BranchStatus.labelFromHead("ref: refs/heads/"))
        assertEquals(BranchStatus.FALLBACK, BranchStatus.labelFromHead("ref:"))
    }
}
