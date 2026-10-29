package com.codespace.ide.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U6-d — Explorer reveal for agent-written files.
 *
 * The refresh channel already re-scanned the tree, so every bug that survived was a REVEAL
 * bug: the file existed in the tree but its parents were collapsed, or a path outside the
 * project tried to reveal something that was never there.
 */
class ExplorerRevealTest {

    private val root = "/storage/emulated/0/My codespace app 1"

    @After
    fun tearDown() {
        ExplorerReveal.consume()
    }

    @Test
    fun aFileDirectlyInTheRootNeedsNoExpansion() {
        val ancestors = ExplorerReveal.ancestorsToExpand("$root/main.rs", root)
        assertEquals(emptyList<String>(), ancestors)
    }

    @Test
    fun aNestedFileExpandsEveryDirectoryBetweenRootAndIt() {
        val ancestors = ExplorerReveal.ancestorsToExpand("$root/src/parser/lexer.rs", root)
        assertEquals(
            listOf("$root/src", "$root/src/parser"),
            ancestors,
        )
        // Outermost first: the pane must expand top-down or the inner dir is not rendered.
        assertTrue(ancestors.first().endsWith("/src"))
        assertTrue(ancestors.last().endsWith("/parser"))
    }

    @Test
    fun aNewlyCreatedDeepDirectoryChainStillResolves() {
        // write_file calls mkdirs(), so the chain exists on disk by the time we reveal it.
        val ancestors = ExplorerReveal.ancestorsToExpand("$root/a/b/c/d/file.txt", root)
        assertEquals(4, ancestors.size)
        assertEquals("$root/a/b/c/d", ancestors.last())
    }

    @Test
    fun aPathOutsideTheProjectRevealsNothing() {
        assertEquals(emptyList<String>(), ExplorerReveal.ancestorsToExpand("/data/local/tmp/x.txt", root))
        // The dangerous near-miss: a SIBLING whose name starts with the same characters.
        assertEquals(
            emptyList<String>(),
            ExplorerReveal.ancestorsToExpand("/storage/emulated/0/My codespace app 10/x/y.txt", root),
        )
        assertFalse(ExplorerReveal.isInsideProject("/storage/emulated/0/Other/x.txt", root))
    }

    @Test
    fun aMissingRootNeverGuesses() {
        assertEquals(emptyList<String>(), ExplorerReveal.ancestorsToExpand("$root/src/a.kt", null))
        assertFalse(ExplorerReveal.isInsideProject("$root/src/a.kt", ""))
    }

    @Test
    fun duplicateSlashesAndTrailingSlashDoNotBreakThePrefixTest() {
        assertEquals("$root/src", ExplorerReveal.normalize("$root//src/"))
        assertEquals(
            listOf("$root/src"),
            ExplorerReveal.ancestorsToExpand("$root//src//a.kt", "$root/"),
        )
    }

    @Test
    fun multipleChangedFilesAllQueueUp() {
        ExplorerReveal.requestFor(listOf("$root/a/x.kt", "$root/b/y.kt"))
        assertEquals(listOf("$root/a/x.kt", "$root/b/y.kt"), ExplorerReveal.pending.value)
    }

    @Test
    fun aSecondBatchAddsToTheQueueInsteadOfOverwritingIt() {
        // The overwrite bug would silently drop the first file of a two-operation burst.
        ExplorerReveal.requestFor("$root/a/x.kt")
        ExplorerReveal.requestFor("$root/b/y.kt")
        assertEquals(listOf("$root/a/x.kt", "$root/b/y.kt"), ExplorerReveal.pending.value)
    }

    @Test
    fun theSamePathTwiceIsQueuedOnce() {
        ExplorerReveal.requestFor("$root/a/x.kt")
        ExplorerReveal.requestFor("$root/a/x.kt")
        assertEquals(1, ExplorerReveal.pending.value.size)
    }

    @Test
    fun anEmptyRequestIsANoOp() {
        ExplorerReveal.requestFor(emptyList())
        assertTrue(ExplorerReveal.pending.value.isEmpty())
    }

    @Test
    fun consumingClearsTheQueueSoItCannotReplay() {
        ExplorerReveal.requestFor("$root/a/x.kt")
        ExplorerReveal.consume()
        assertTrue(ExplorerReveal.pending.value.isEmpty())
    }

    @Test
    fun theRootItselfIsInsideTheProject() {
        assertTrue(ExplorerReveal.isInsideProject(root, root))
    }
}
