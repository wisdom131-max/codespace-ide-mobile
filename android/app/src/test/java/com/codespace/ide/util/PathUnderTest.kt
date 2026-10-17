package com.codespace.ide.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH5-P2 (advisor-approved 2026-10-17, with CHANGES): pure matching for
 * workspace-root removal. Required matrix: nested roots (/a and /a/b),
 * sibling roots with a common prefix (/a/proj and /a/proj2), alias forms,
 * trailing slashes — plus the EXPLICIT no-suffix-fallback ruling and the
 * kept-under-remaining-root ruling.
 */
class PathUnderTest {

    private val P = "/storage/emulated/0/MyProject"

    @Test
    fun `nested roots - tab under the remaining nested root is kept`() {
        // Remove /a while /a/b remains: /a/b/x.kt is under BOTH -> kept.
        val d = PathUnder.decideRemoveRoot(
            openTabs = listOf("/a/b/x.kt", "/a/y.kt"),
            removedRoot = "/a",
            remainingRoots = listOf("/a/b"),
            projectRoot = null,
        )
        assertEquals(listOf("/a/b/x.kt", "/a/y.kt"), d.matchedTabs)
        assertEquals(setOf("/a/y.kt"), d.toCloseTabs)
        assertFalse(d.unmatchedTabsExist)
    }

    @Test
    fun `sibling roots with common prefix never cross-match`() {
        // Remove /a/proj with /a/proj2 remaining: proj2's tab must NOT match
        // (this is exactly what a suffix-match fallback would get wrong).
        val d = PathUnder.decideRemoveRoot(
            openTabs = listOf("/a/proj2/f.kt"),
            removedRoot = "/a/proj",
            remainingRoots = listOf("/a/proj2"),
            projectRoot = null,
        )
        assertTrue(d.matchedTabs.isEmpty())
        assertTrue(d.toCloseTabs.isEmpty())
        assertTrue(d.unmatchedTabsExist)
    }

    @Test
    fun `alias forms match - sdcard equals storage emulated 0`() {
        // Tab in /sdcard dialect, root in /storage dialect.
        assertTrue(PathUnder.isUnder("/sdcard/Scratch/f.txt", "/storage/emulated/0/Scratch"))
        // And the reverse direction.
        assertTrue(PathUnder.isUnder("/storage/emulated/0/Scratch/f.txt", "/sdcard/Scratch"))
        // /sdcard itself normalizes onto the user-storage root.
        assertTrue(PathUnder.isUnder("/sdcard/Scratch", "/storage/emulated/0/Scratch/".trimEnd('/')))
    }

    @Test
    fun `trailing slashes are trimmed on both sides`() {
        assertTrue(PathUnder.isUnder("/a/b/f.kt", "/a/b/"))
        assertTrue(PathUnder.isUnder("/a/b", "/a/b/"))
        assertTrue(PathUnder.isUnder("/a/b/", "/a/b"))
    }

    @Test
    fun `relative tab path resolves against the project root`() {
        val d = PathUnder.decideRemoveRoot(
            openTabs = listOf("src/A.kt"),
            removedRoot = P,
            remainingRoots = emptyList(),
            projectRoot = P,
        )
        assertEquals(listOf("src/A.kt"), d.matchedTabs)
        assertEquals(setOf("src/A.kt"), d.toCloseTabs)
    }

    @Test
    fun `relative tab path with unknown project root matches nothing`() {
        // Fail closed: cannot resolve, cannot close.
        val d = PathUnder.decideRemoveRoot(
            openTabs = listOf("src/A.kt"),
            removedRoot = P,
            remainingRoots = emptyList(),
            projectRoot = null,
        )
        assertTrue(d.matchedTabs.isEmpty())
        assertTrue(d.unmatchedTabsExist)
    }

    @Test
    fun `no suffix fallback - a path merely ending with the root never matches`() {
        // /x/a/b/f.kt CONTAINS "/a/b" as a suffix but is NOT under it.
        assertFalse(PathUnder.isUnder("/x/a/b/f.kt", "/a/b"))
        val d = PathUnder.decideRemoveRoot(
            openTabs = listOf("/x/a/b/f.kt"),
            removedRoot = "/a/b",
            remainingRoots = emptyList(),
            projectRoot = null,
        )
        assertTrue(d.matchedTabs.isEmpty())
    }

    @Test
    fun `exact equality matches the root itself`() {
        assertTrue(PathUnder.isUnder(P, P))
        val d = PathUnder.decideRemoveRoot(
            openTabs = listOf(P),
            removedRoot = P,
            remainingRoots = emptyList(),
            projectRoot = null,
        )
        assertEquals(setOf(P), d.toCloseTabs)
    }

    @Test
    fun `empty root fails closed`() {
        assertFalse(PathUnder.isUnder("/anything", "/"))
        assertFalse(PathUnder.isUnder("/anything", ""))
    }

    @Test
    fun `tab under two remaining roots is kept, tab under one is closed`() {
        val d = PathUnder.decideRemoveRoot(
            openTabs = listOf("/r1/f.kt", "/r2/g.kt", "/r1/h.kt"),
            removedRoot = "/r1",
            remainingRoots = listOf("/r1/f.kt", "/r2"),
            projectRoot = null,
        )
        // /r1/f.kt is ALSO a remaining root itself -> kept. /r2/g.kt is not
        // under /r1 -> unmatched-family (open but not matched). /r1/h.kt closed.
        assertEquals(listOf("/r1/f.kt", "/r1/h.kt"), d.matchedTabs)
        assertEquals(setOf("/r1/h.kt"), d.toCloseTabs)
    }

    @Test
    fun `normalize maps the alias and keeps absolute paths untouched`() {
        assertEquals("/storage/emulated/0/X", PathUnder.normalize("/sdcard/X", null))
        assertEquals("/storage/emulated/0", PathUnder.normalize("/sdcard/", null))
        assertEquals("/a/b", PathUnder.normalize("/a/b/", null))
        assertEquals(P + "/src/A.kt", PathUnder.normalize("src/A.kt", P))
        // The alias maps only /sdcard — never other /storage forms.
        assertEquals("/storage/17BE-3A1C/notes", PathUnder.normalize("/storage/17BE-3A1C/notes", null))
    }
}
