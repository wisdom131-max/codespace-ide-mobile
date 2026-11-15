package com.codespace.ide.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * GATE 1 (2026-11-15): an Edit-mode proposal is containment-checked when it is staged and again
 * when it is applied.
 *
 * The bug this pins: dispatch containment never ran for a proposal, so `stage` stored any path
 * the process could reach and Apply wrote it. Two policies for one workspace.
 */
class StagedProposalContainmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun withNoProjectOpenThereIsNoBoundary() {
        // Same rule as dispatch: no open project means nothing to enforce, and the trust gate
        // still governs the run.
        assertTrue(WriteBoundary.insideProject(null, "/data/local/anything.txt"))
        assertTrue(WriteBoundary.insideProject("", "/data/local/anything.txt"))
    }

    @Test
    fun aPathInsideTheProjectIsAllowed() {
        val root = tmp.newFolder("proj").absolutePath
        assertTrue(WriteBoundary.insideProject(root, "$root/src/Main.kt"))
    }

    @Test
    fun theProjectRootItselfIsNotAFileTarget() {
        // A write whose "parent" is above the root is not inside the root.
        val root = tmp.newFolder("proj2").absolutePath
        assertFalse(WriteBoundary.insideProject(root, root))
    }

    @Test
    fun aPathOutsideTheProjectIsRefused() {
        val root = tmp.newFolder("proj3").absolutePath
        assertFalse(WriteBoundary.insideProject(root, "/data/data/com.codespace.ide/secrets.txt"))
    }

    @Test
    fun traversalOutOfTheProjectIsRefused() {
        val root = tmp.newFolder("proj4").absolutePath
        assertFalse(WriteBoundary.insideProject(root, "$root/../../etc/hosts"))
    }

    @Test
    fun theTwoRefusalsStateThatNothingHappened() {
        val stage = WriteBoundary.proposalRefusal("/x/y.txt", "/proj")
        assertTrue(stage.contains("Nothing was staged"))
        assertTrue(stage.contains("Nothing was written"))
        assertTrue(stage.contains("/x/y.txt"))
        val apply = WriteBoundary.applyRefusal("/x/y.txt", "/proj")
        assertTrue(apply.contains("not applied"))
        assertTrue(apply.contains("/proj"))
    }
}
