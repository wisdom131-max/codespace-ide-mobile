package com.codespace.ide.agent.subagents

import com.codespace.ide.chat.panel.ToolCategory
import com.codespace.ide.chat.panel.ToolMeta
import com.codespace.ide.chat.panel.ToolMetaIndex
import com.codespace.ide.chat.panel.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 3.4: delegation that cannot run away with the project.
 *
 * Each case here is one way a delegation feature goes wrong in practice: a parallel write, two writers
 * on one file, a duplicate request applying twice, a cancelled parent whose children keep running, a
 * nested agent that exceeds its parent, and a "completed" task with nothing to show for it.
 */
class DelegationSchedulerTest {

    private val meta = mapOf(
        "read_file" to ToolMeta(ToolCategory.FILES),
        "list_files" to ToolMeta(ToolCategory.FILES),
        "grep_search" to ToolMeta(ToolCategory.SEARCH),
        "write_file" to ToolMeta(ToolCategory.FILES, destructive = true),
        "edit_file" to ToolMeta(ToolCategory.EDIT, destructive = true),
        "run_command" to ToolMeta(ToolCategory.TERMINAL, destructive = true),
        "web_search" to ToolMeta(ToolCategory.WEB),
        "git_status" to ToolMeta(ToolCategory.GIT),
        "git_commit" to ToolMeta(ToolCategory.GIT, destructive = true),
    )

    private val permitted: Set<String> = meta.keys

    private fun lookup(name: String): ToolMeta? = meta[name]

    private fun board(allowed: Set<String> = permitted) =
        DelegationScheduler.Board(allowedTools = allowed)

    private fun readTask(id: String, title: String = "read " + id, reads: Set<String> = emptySet()) =
        DelegationScheduler.Request("researcher", title, reads = reads)

    private fun writeTask(title: String, path: String) =
        DelegationScheduler.Request("coder", title, writes = setOf(path))

    @Test
    fun independentReadOnlyWorkRunsInParallel() {
        val enqueued = DelegationScheduler.enqueue(
            board(),
            listOf(readTask("a"), readTask("b"), readTask("c")),
            permitted, ::lookup,
        )
        assertEquals(3, enqueued.queued.size)
        val wave = DelegationScheduler.wave(enqueued.board)
        assertEquals("three independent reads share the lane", 3, wave.size)
    }

    @Test
    fun theConcurrencyCapHolds() {
        val requests = (1..6).map { readTask("r" + it) }
        val enqueued = DelegationScheduler.enqueue(board(), requests, permitted, ::lookup)
        val wave = DelegationScheduler.wave(enqueued.board)
        assertEquals(DelegationScheduler.MAX_CONCURRENT, wave.size)
    }

    @Test
    fun aWriteNeverRunsBesideAnything() {
        val enqueued = DelegationScheduler.enqueue(
            board(),
            listOf(readTask("a"), readTask("b"), writeTask("edit build", "app/build.gradle")),
            permitted, ::lookup,
        )
        val wave = DelegationScheduler.wave(enqueued.board)
        assertTrue("reads go first so the write sees their results", wave.none { it.isWriter })
        var next = enqueued.board
        wave.forEach { next = DelegationScheduler.start(next, it.id, 1L) }
        val second = DelegationScheduler.wave(next)
        assertTrue("nothing starts while a read is running and a write is pending", second.isEmpty())

        var afterReads = next
        wave.forEach { afterReads = DelegationScheduler.complete(afterReads, it.id, "read it", true, 2L) }
        val third = DelegationScheduler.wave(afterReads)
        assertEquals(1, third.size)
        assertTrue(third.first().isWriter)

        val running = DelegationScheduler.start(afterReads, third.first().id, 3L)
        assertTrue(
            "a running write owns the project: nothing else starts",
            DelegationScheduler.wave(running).isEmpty(),
        )
    }

    @Test
    fun twoWritersOnOnePathAreConflictedNotRaced() {
        val first = DelegationScheduler.enqueue(board(), listOf(writeTask("edit A", "app/Main.kt")), permitted, ::lookup)
        val second = DelegationScheduler.enqueue(
            first.board,
            listOf(DelegationScheduler.Request("coder", "edit B", writes = setOf("app/Main.kt"))),
            permitted, ::lookup,
        )
        assertTrue("the second writer is queued or blocked, never started", second.queued.isEmpty())
        val blocked = second.board.of(DelegationScheduler.State.BLOCKED)
        assertEquals(1, blocked.size)
        assertTrue(blocked.first().note, blocked.first().note.contains("app/Main.kt"))
        assertTrue(blocked.first().note, blocked.first().note.contains("sa1"))
        assertTrue(DelegationScheduler.report(second.board).contains("blocked"))
    }

    @Test
    fun writesToDifferentPathsAreStillSerializedByTheScheduler() {
        val enqueued = DelegationScheduler.enqueue(
            board(),
            listOf(writeTask("edit A", "a.kt"), writeTask("edit B", "b.kt")),
            permitted, ::lookup,
        )
        assertEquals(2, enqueued.queued.size)
        val wave = DelegationScheduler.wave(enqueued.board)
        assertEquals("only one write at a time, in declaration order", 1, wave.size)
        assertEquals("edit A", wave.first().title)
    }

    @Test
    fun aReadDoesNotStartWhileAWriteToItsPathIsQueuedFirst() {
        val enqueued = DelegationScheduler.enqueue(
            board(),
            listOf(
                DelegationScheduler.Request("coder", "rewrite config", writes = setOf("gradle.properties")),
                DelegationScheduler.Request("review", "read config", reads = setOf("gradle.properties")),
            ),
            permitted, ::lookup,
        )
        val wave = DelegationScheduler.wave(enqueued.board)
        assertEquals("the queued write goes first", 1, wave.size)
        assertTrue(wave.first().isWriter)
    }

    @Test
    fun aDuplicateRequestDoesNotApplyTwice() {
        val request = writeTask("edit A", "app/Main.kt")
        val first = DelegationScheduler.enqueue(board(), listOf(request), permitted, ::lookup)
        val started = DelegationScheduler.start(first.board, first.queued.first().id, 1L)
        val done = DelegationScheduler.complete(started, first.queued.first().id, "wrote 12 lines", true, 2L)
        val again = DelegationScheduler.enqueue(done, listOf(request), permitted, ::lookup)
        assertEquals("no second task", 0, again.queued.size)
        assertEquals(1, again.deduped.size)
        assertTrue(again.deduped.first().note.contains("answered from the first"))
        assertEquals("still one task on the board", 1, again.board.tasks.size)
    }

    @Test
    fun aDuplicateOfAnInFlightTaskIsNotStarted() {
        val request = readTask("a")
        val first = DelegationScheduler.enqueue(board(), listOf(request), permitted, ::lookup)
        val started = DelegationScheduler.start(first.board, first.queued.first().id, 1L)
        val again = DelegationScheduler.enqueue(started, listOf(request), permitted, ::lookup)
        assertEquals(0, again.queued.size)
        assertTrue(again.deduped.first().note.contains("running"))
    }

    @Test
    fun cancellationReachesEverythingQueuedAndRunning() {
        val enqueued = DelegationScheduler.enqueue(
            board(),
            listOf(readTask("a"), readTask("b"), writeTask("w", "x.kt")),
            permitted, ::lookup,
        )
        val running = DelegationScheduler.start(enqueued.board, enqueued.queued.first().id, 1L)
        val cancelled = DelegationScheduler.cancel(running, "owner stopped the run", 2L)
        assertTrue(cancelled.cancelled)
        assertEquals(0, cancelled.of(DelegationScheduler.State.QUEUED).size)
        assertEquals(0, cancelled.of(DelegationScheduler.State.RUNNING).size)
        assertEquals(3, cancelled.of(DelegationScheduler.State.CANCELLED).size)
        assertTrue(DelegationScheduler.wave(cancelled).isEmpty())
        assertTrue(DelegationScheduler.report(cancelled).contains("owner stopped the run"))
    }

    @Test
    fun nothingIsAdmittedAfterCancellation() {
        val cancelled = DelegationScheduler.cancel(board(), "stop", 1L)
        val enqueued = DelegationScheduler.enqueue(cancelled, listOf(readTask("late")), permitted, ::lookup)
        assertEquals(0, enqueued.queued.size)
        assertEquals(1, enqueued.refused.size)
    }

    @Test
    fun aChildBoardIsStrictlySmallerThanItsParent() {
        val enqueued = DelegationScheduler.enqueue(board(), listOf(readTask("a")), permitted, ::lookup)
        val running = DelegationScheduler.start(enqueued.board, enqueued.queued.first().id, 1L)
        val child = DelegationScheduler.childBoard(running, running.tasks.first().id, running.tasks.first().tools)
        assertTrue(child is DelegationScheduler.Child.Ok)
        val childBoard = (child as DelegationScheduler.Child.Ok).board
        assertEquals(1, childBoard.budget.depth)
        assertTrue(childBoard.budget.agentsLeft < running.budget.agentsLeft)
        assertTrue(childBoard.allowedTools.containsAll(emptySet()))
        assertTrue(
            "the child's tools are the parent task's tools",
            running.tasks.first().tools.containsAll(childBoard.allowedTools),
        )
    }

    @Test
    fun nestedDelegationCannotExceedTheDepthLimit() {
        var current = board()
        val first = DelegationScheduler.enqueue(current, listOf(readTask("a")), permitted, ::lookup)
        current = DelegationScheduler.start(first.board, "sa1", 1L)
        val level1 = DelegationScheduler.childBoard(current, "sa1", current.task("sa1")!!.tools) as DelegationScheduler.Child.Ok
        val e1 = DelegationScheduler.enqueue(level1.board, listOf(readTask("b")), permitted, ::lookup)
        val r1 = DelegationScheduler.start(e1.board, "sa1", 2L)
        val level2 = DelegationScheduler.childBoard(r1, "sa1", r1.task("sa1")!!.tools) as DelegationScheduler.Child.Ok
        val e2 = DelegationScheduler.enqueue(level2.board, listOf(readTask("c")), permitted, ::lookup)
        val r2 = DelegationScheduler.start(e2.board, "sa1", 3L)
        val level3 = DelegationScheduler.childBoard(r2, "sa1", r2.task("sa1")!!.tools)
        assertTrue(level3 is DelegationScheduler.Child.Refused)
        assertTrue((level3 as DelegationScheduler.Child.Refused).reason.contains("levels deep"))
    }

    @Test
    fun aChildCannotWidenItsTools() {
        val enqueued = DelegationScheduler.enqueue(board(), listOf(readTask("a")), permitted, ::lookup)
        val running = DelegationScheduler.start(enqueued.board, "sa1", 1L)
        val parentTools = running.task("sa1")!!.tools
        val wider = parentTools + "run_command"
        val result = DelegationScheduler.childBoard(running, "sa1", wider)
        assertTrue(result is DelegationScheduler.Child.Refused)
        assertTrue((result as DelegationScheduler.Child.Refused).reason.contains("run_command"))
    }

    @Test
    fun onlyARunningTaskMayDelegate() {
        val enqueued = DelegationScheduler.enqueue(board(), listOf(readTask("a")), permitted, ::lookup)
        val queued = enqueued.board.task("sa1")!!
        val result = DelegationScheduler.childBoard(enqueued.board, "sa1", queued.tools)
        assertTrue(result is DelegationScheduler.Child.Refused)
        assertTrue((result as DelegationScheduler.Child.Refused).reason.contains("queued"))
    }

    @Test
    fun aCompletionWithoutEvidenceIsAFailureNotASuccess() {
        val enqueued = DelegationScheduler.enqueue(board(), listOf(readTask("a")), permitted, ::lookup)
        val running = DelegationScheduler.start(enqueued.board, "sa1", 1L)
        val done = DelegationScheduler.complete(running, "sa1", "   ", true, 2L)
        val task = done.task("sa1")!!
        assertEquals(DelegationScheduler.State.FAILED, task.state)
        assertTrue(task.note.contains("evidence"))
        assertFalse("no exception is not success", task.verified)
    }

    @Test
    fun aReadResultFromBeforeAProjectChangeIsStale() {
        val enqueued = DelegationScheduler.enqueue(board(), listOf(readTask("a")), permitted, ::lookup)
        val running = DelegationScheduler.start(enqueued.board, "sa1", 1L)
        val done = DelegationScheduler.complete(running, "sa1", "read 40 lines", true, 2L, epoch = 7)
        val task = done.task("sa1")!!
        assertEquals(DelegationScheduler.State.COMPLETED, task.state)
        assertFalse("the project moved under it", task.verified)
        assertTrue(task.note.contains("stale"))
        assertTrue(DelegationScheduler.report(done).contains("unverified"))
    }

    @Test
    fun aWriterIsNotCalledStaleByItsOwnChange() {
        val enqueued = DelegationScheduler.enqueue(board(), listOf(writeTask("w", "a.kt")), permitted, ::lookup)
        val running = DelegationScheduler.start(enqueued.board, "sa1", 1L)
        val done = DelegationScheduler.complete(running, "sa1", "wrote a.kt", true, 2L, epoch = 3)
        assertTrue(done.task("sa1")!!.verified)
    }

    @Test
    fun aTaskTiedToAnUnreadyStepDoesNotStart() {
        val enqueued = DelegationScheduler.enqueue(
            board(),
            listOf(DelegationScheduler.Request("coder", "do step", planStepId = "s2", writes = setOf("a.kt"))),
            permitted, ::lookup,
        )
        assertTrue(DelegationScheduler.wave(enqueued.board, readySteps = setOf("s1")).isEmpty())
        assertEquals(1, DelegationScheduler.wave(enqueued.board, readySteps = setOf("s1", "s2")).size)
    }

    @Test
    fun theTaskBudgetRefusesRatherThanGrowing() {
        var current = DelegationScheduler.Board(
            budget = DelegationScheduler.Budget(tasksLeft = 2),
            allowedTools = permitted,
        )
        val requests = (1..4).map { readTask("r" + it) }
        val enqueued = DelegationScheduler.enqueue(current, requests, permitted, ::lookup)
        assertEquals(2, enqueued.queued.size)
        assertEquals(2, enqueued.refused.size)
        assertTrue(enqueued.refused.first().second.contains("budget"))
        current = enqueued.board
        assertEquals(2, current.tasks.size)
    }

    @Test
    fun anUnknownRoleIsRefusedAndNothingIsQueued() {
        val enqueued = DelegationScheduler.enqueue(
            board(),
            listOf(DelegationScheduler.Request("wizard", "do magic")),
            permitted, ::lookup,
        )
        assertEquals(0, enqueued.queued.size)
        assertTrue(enqueued.refused.first().second.contains("wizard"))
        assertEquals(0, enqueued.board.tasks.size)
    }

    @Test
    fun everyQueuedTaskIsTraceableToItsRoleAndPlanStep() {
        val enqueued = DelegationScheduler.enqueue(
            board(),
            listOf(
                DelegationScheduler.Request("researcher", "look it up", planStepId = "s1"),
                DelegationScheduler.Request("coder", "write it", planStepId = "s2", writes = setOf("a.kt")),
            ),
            permitted, ::lookup,
        )
        val tasks = enqueued.board.tasks
        assertEquals(2, tasks.size)
        assertEquals("s1", tasks[0].planStepId)
        assertEquals("s2", tasks[1].planStepId)
        assertEquals("researcher", tasks[0].roleId)
        assertEquals("coder", tasks[1].roleId)
        assertTrue("results name their agent", DelegationScheduler.report(enqueued.board).isNotEmpty())
    }

    @Test
    fun theReportIsHonestAboutMixedOutcomes() {
        val enqueued = DelegationScheduler.enqueue(
            board(),
            listOf(readTask("ok"), readTask("bad")),
            permitted, ::lookup,
        )
        var b = DelegationScheduler.start(enqueued.board, "sa1", 1L)
        b = DelegationScheduler.start(b, "sa2", 1L)
        b = DelegationScheduler.complete(b, "sa1", "found 3 matches", true, 2L)
        b = DelegationScheduler.fail(b, "sa2", "path missing", 2L)
        val report = DelegationScheduler.report(b)
        assertTrue(report, report.contains("1 completed (1 verified"))
        assertTrue(report, report.contains("1 failed"))
        assertTrue(report, report.contains("path missing"))
    }

    @Test
    fun theRealRegistryStillNarrowsThroughDelegation() {
        val real = ToolRegistry.names().toSet()
        // Real metadata, not a fixture: the destructive flags here are the ones the product uses.
        val realMeta: (String) -> ToolMeta? = { name -> ToolRegistry.spec(name)?.let { ToolMetaIndex.metaFor(it) } }
        val enqueued = DelegationScheduler.enqueue(
            DelegationScheduler.Board(allowedTools = real),
            listOf(DelegationScheduler.Request("security", "review the diff", reads = setOf("app/Main.kt"))),
            real,
            metaOf = realMeta,
        )
        val task = enqueued.board.tasks.first()
        assertTrue("the reviewer keeps read tools", task.tools.contains("read_file"))
        assertFalse("the reviewer never gets a write", task.tools.contains("write_file"))
        assertTrue("scoped inside the registry", real.containsAll(task.tools))
    }
}
