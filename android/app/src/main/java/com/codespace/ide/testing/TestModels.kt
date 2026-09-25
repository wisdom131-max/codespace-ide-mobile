package com.codespace.ide.testing

/**
 * F3 (F-TRACK TG03): core testing result model — VS Code's shape
 * (testResult.ts / testingStates.ts), simplified to what this app consumes.
 *
 * TestId: the F1 slash-path string ("<filePath>/<chain>/<name>") is the
 * single identity across lenses (F1), runs (F2) and results (F3/F4).
 */

/** The 7-state result model (VS Code TestResultState minus Unset). */
enum class TestResultState { QUEUED, RUNNING, PASSED, FAILED, ERRORED, SKIPPED, RETIRED }

object TestResultStates {
    /**
     * Priority comparator copied in semantics from VS Code testingStates.ts
     * statePriority: lower number = stronger state, wins the computed rollup.
     */
    private val priority = mapOf(
        TestResultState.ERRORED to 0,
        TestResultState.FAILED to 1,
        TestResultState.RUNNING to 2,
        TestResultState.QUEUED to 3,
        TestResultState.PASSED to 5,
        TestResultState.RETIRED to 6,
        TestResultState.SKIPPED to 7,
    )

    /** Strongest (most attention-demanding) of two states. */
    fun merge(a: TestResultState, b: TestResultState): TestResultState =
        if ((priority[b] ?: 9) < (priority[a] ?: 9)) b else a

    /** True when the state is a terminal outcome for a single run. */
    fun isTerminal(s: TestResultState): Boolean =
        s == TestResultState.PASSED || s == TestResultState.FAILED ||
        s == TestResultState.ERRORED || s == TestResultState.SKIPPED
}

/**
 * One test's latest recorded result, keyed by TestId in [TestResultStore].
 * ownState is this node's outcome; computedState rolls children up
 * (statePriority) so a suite shows FAILED when any child failed.
 */
data class TestResultItem(
    val testId: String,
    /** 0-based editor line of the test declaration (from the F1 lens); -1 = unknown. */
    val lineIndex: Int,
    val ownState: TestResultState,
    val computedState: TestResultState,
    val ownDurationMs: Long,
    /** Failure/error message for Problems bridging; null when passing. */
    val message: String?,
    /** True when a NEWER run did not include this test (VS Code retired semantics). */
    val retired: Boolean,
    val runId: Long,
)

/** One completed run's summary (VS Code TestRun shape). */
data class TestRun(
    val id: Long,
    val name: String,
    val completedAt: Long,
    val passed: Int,
    val failed: Int,
    val errored: Int,
    val skipped: Int,
)
