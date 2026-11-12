package com.codespace.ide.agent

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4.B: independent reads may run together, bounded; everything else runs alone and in plan order;
 * answers come back keyed by index so the transcript never depends on which call finished first.
 */
class ToolExecutionCoordinatorTest {

    private val reads = ToolExecutionCoordinator.MAX_PARALLEL_READS

    @Test fun writesAndCommandsAreNeverRunTogether() {
        val names = listOf("write_file", "run_command", "git_commit", "mcp__srv__do")
        names.forEach { assertFalse("$it must stay sequential", ToolExecutionCoordinator.parallelSafe(it)) }
    }

    @Test fun theKnownReadToolsAreTheOnlySafeOnes() {
        assertEquals(ToolExecutionCoordinator.PARALLEL_SAFE, setOf(
            "read_file", "grep_search", "list_dir", "file_exists", "find_files",
            "read_project_structure", "zip_list", "checksum", "get_diagnostics", "package_tree"))
    }

    @Test fun runsPreserveOrderAndSplitAroundAWrites() {
        val names = listOf("read_file", "grep_search", "write_file", "list_dir")
        val runs = ToolExecutionCoordinator.runs(listOf(0, 1, 2, 3), names)
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3)), runs)
        assertEquals("nothing may be lost", listOf(0, 1, 2, 3), runs.flatten())
    }

    @Test fun aSingleReadIsItsOwnRun() {
        val runs = ToolExecutionCoordinator.runs(listOf(0), listOf("read_file"))
        assertEquals(listOf(listOf(0)), runs)
    }

    @Test fun resultsComeBackByIndexNotByCompletionOrder() = runBlocking {
        // The first call is the slow one: completion order is 1,0 but the map must not care.
        val answers = ToolExecutionCoordinator.collect(listOf(listOf(0, 1))) { idx ->
            if (idx == 0) delay(60) else delay(5)
            "answer-$idx"
        }
        assertEquals(listOf(0, 1), answers.keys.toList())
        assertEquals("answer-0", answers[0])
        assertEquals("answer-1", answers[1])
    }

    @Test fun concurrencyIsBounded() = runBlocking {
        val live = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val indices = (0 until 8).toList()
        ToolExecutionCoordinator.collect(listOf(indices)) { _ ->
            val now = live.incrementAndGet()
            peak.updateAndGet { p -> maxOf(p, now) }
            delay(20)
            live.decrementAndGet()
        }
        assertTrue("peak was ${peak.get()}, ceiling is $reads", peak.get() <= reads)
        assertTrue("nothing actually ran together", peak.get() > 1)
    }

    @Test fun everyCallInTheBatchStillRunsExactlyOnce() = runBlocking {
        val seen = java.util.Collections.synchronizedList(ArrayList<Int>())
        ToolExecutionCoordinator.collect(
            ToolExecutionCoordinator.runs(listOf(0, 1, 2, 3, 4), listOf(
                "read_file", "read_file", "write_file", "read_file", "read_file")),
        ) { idx -> seen.add(idx); idx }
        assertEquals(listOf(0, 1, 2, 3, 4), seen.sorted())
    }

    @Test fun aFailureCancelsTheRestOfItsWaveInsteadOfLeavingWorkRunning() = runBlocking {
        val finished = java.util.Collections.synchronizedList(ArrayList<Int>())
        val failure = runCatching {
            ToolExecutionCoordinator.collect(listOf(listOf(0, 1, 2))) { idx ->
                when (idx) {
                    0 -> throw IllegalStateException("boom")
                    else -> { delay(200); finished.add(idx); idx }
                }
            }
        }
        assertTrue("the failure must surface", failure.isFailure)
        assertTrue("a sibling kept running after the wave failed: $finished", finished.isEmpty())
    }

    @Test fun aWriteBetweenReadsIsNeverOverlappedWithThem() = runBlocking {
        val overlap = AtomicInteger(0)
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        ToolExecutionCoordinator.collect(
            ToolExecutionCoordinator.runs(listOf(0, 1, 2), listOf("read_file", "write_file", "read_file")),
        ) { idx ->
            if (idx == 1) order.add("write") else { order.add("read"); delay(10) }
            idx
        }
        assertEquals("write must not be sandwiched inside a read wave", listOf("read", "write", "read"), order)
        assertEquals(0, overlap.get())
    }
}
