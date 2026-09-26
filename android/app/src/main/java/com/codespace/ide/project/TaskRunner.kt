package com.codespace.ide.project

import android.content.Context
import com.codespace.ide.build.BuildRunner
import com.codespace.ide.build.GradleErrorParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Phase 12-G — Task Runner
 *
 * Defines a catalogue of one-tap build/project tasks and provides
 * a simple API for launching them. Execution delegates to BuildRunner.
 * Task history and status are exposed via StateFlow.
 */
object TaskRunner {

    enum class TaskId {
        BUILD_DEBUG, BUILD_RELEASE, CLEAN, LINT, TEST,
        INSTALL_DEBUG, BUNDLE_RELEASE, ASSEMBLE_ALL,
    }

    data class Task(
        val id: TaskId,
        val displayName: String,
        val description: String,
        val gradleTask: String,
        val icon: TaskIcon,
    )

    enum class TaskIcon { BUILD, RELEASE, CLEAN, LINT, TEST, INSTALL, BUNDLE, ALL }

    enum class RunState { IDLE, RUNNING, SUCCESS, FAILED }

    data class TaskRun(
        val taskId: TaskId,
        val state: RunState,
        val result: BuildRunner.BuildResult? = null,
    )

    val CATALOGUE: List<Task> = listOf(
        Task(TaskId.BUILD_DEBUG,     "Build Debug APK",    "Compile and package a debug APK",          "assembleDebug",   TaskIcon.BUILD),
        Task(TaskId.BUILD_RELEASE,   "Build Release APK",  "Compile and package a release APK",        "assembleRelease", TaskIcon.RELEASE),
        Task(TaskId.CLEAN,           "Clean Project",      "Delete all build outputs and caches",       "clean",           TaskIcon.CLEAN),
        Task(TaskId.LINT,            "Run Lint",           "Analyse code for errors and warnings",      "lint",            TaskIcon.LINT),
        Task(TaskId.TEST,            "Run Unit Tests",     "Execute JVM unit tests",                    "test",            TaskIcon.TEST),
        Task(TaskId.INSTALL_DEBUG,   "Install on Device",  "Build and install debug APK via ADB",       "installDebug",    TaskIcon.INSTALL),
        Task(TaskId.BUNDLE_RELEASE,  "Build Release AAB",  "Build a release Android App Bundle",        "bundleRelease",   TaskIcon.BUNDLE),
        Task(TaskId.ASSEMBLE_ALL,    "Assemble All",       "Build all variant APKs",                    "assemble",        TaskIcon.ALL),
    )

    private val _runs = MutableStateFlow<Map<TaskId, TaskRun>>(emptyMap())
    val runs: StateFlow<Map<TaskId, TaskRun>> = _runs.asStateFlow()

    /** Get the current run state for a specific task. */
    fun stateFor(id: TaskId): TaskRun =
        _runs.value[id] ?: TaskRun(id, RunState.IDLE)

    /** Whether any task is currently running. */
    val isAnyRunning: Boolean
        get() = _runs.value.values.any { it.state == RunState.RUNNING }

    /**
     * Execute a task against the given project path.
     * Updates StateFlow with running/success/failed state.
     * Caller is responsible for launching this in a coroutine scope.
     *
     * @return BuildRunner.BuildResult
     */
    suspend fun run(
        context: Context,
        taskId: TaskId,
        projectPath: String,
        onProblemsUpdate: ((List<GradleErrorParser.BuildProblem>) -> Unit)? = null,
    ): BuildRunner.BuildResult {
        val task = CATALOGUE.first { it.id == taskId }
        // P2c TrustState choke point: launching a build/task is a gated action —
        // prompt once via the global trust dialog for untrusted projects.
        if (!com.codespace.ide.security.TrustState.awaitTrusted(context, projectPath)) {
            val refused = BuildRunner.BuildResult(
                status = BuildRunner.BuildStatus.FAILED,
                output = "Project not trusted yet - task launch was cancelled at the trust prompt. Trust the project and run again.",
                durationMs = 0,
                errorCount = 1,
            )
            markDone(taskId, refused)
            return refused
        }
        // TG08 (2026-09-26): "Run Unit Tests" no longer assumes a gradle project.
        // JS/Python/Dart projects previously got a cryptic failing gradle wrapper —
        // the entry was Android-first in a multi-language IDE. The gradle `test`
        // invocation is now kept ONLY when a gradle wrapper/build file is present;
        // otherwise the task routes through the REAL per-language runners (the
        // F2/F4 pipeline: project scan -> TestStore targets -> TestRunManager.runBatch),
        // with an honest summary in the task result. Live per-test output streams to
        // the Output tab "test" channel exactly as in the Testing pane.
        if (taskId == TaskId.TEST) {
            val dir = java.io.File(projectPath)
            val isGradleProject = java.io.File(dir, "gradlew").exists() ||
                java.io.File(dir, "build.gradle").exists() ||
                java.io.File(dir, "build.gradle.kts").exists()
            if (!isGradleProject) {
                markRunning(taskId)
                try {
                    com.codespace.ide.testing.TestDiscoveryService.scanProject(context, dir)
                    val targets = com.codespace.ide.testing.TestStore.items.value.values
                        .filter { !it.suite }
                        .sortedWith(compareBy({ it.filePath }, { it.lineIndex }))
                        .map {
                            com.codespace.ide.testing.TestRunManager.BatchTarget(
                                it.testId, it.filePath, it.language, it.suite, it.lineIndex)
                        }
                    if (targets.isEmpty()) {
                        val none = BuildRunner.BuildResult(
                            status = BuildRunner.BuildStatus.FAILED,
                            output = "Run Unit Tests: no tests discovered in this project. (Gradle projects use the gradle test task; JS/Python/Dart projects run through their real per-language runners — the scan found no test files here.)",
                            durationMs = 0,
                            errorCount = 0,
                        )
                        markDone(taskId, none)
                        return none
                    }
                    val results = com.codespace.ide.testing.TestRunManager.runBatch(
                        context, projectPath, targets) { false }
                    val passed = results.count { it.status == com.codespace.ide.testing.TestRunManager.TestRunStatus.PASSED }
                    val failed = results.count {
                        it.status == com.codespace.ide.testing.TestRunManager.TestRunStatus.FAILED ||
                        it.status == com.codespace.ide.testing.TestRunManager.TestRunStatus.TIMED_OUT ||
                        it.status == com.codespace.ide.testing.TestRunManager.TestRunStatus.LAUNCH_FAILED
                    }
                    val skipped = results.count {
                        it.status == com.codespace.ide.testing.TestRunManager.TestRunStatus.UNTRUSTED ||
                        it.status == com.codespace.ide.testing.TestRunManager.TestRunStatus.UNSUPPORTED ||
                        it.status == com.codespace.ide.testing.TestRunManager.TestRunStatus.BUSY ||
                        it.status == com.codespace.ide.testing.TestRunManager.TestRunStatus.CANCELLED
                    }
                    val summary = "Run Unit Tests — " + targets.size + " tests via per-language runners: " +
                        passed + " passed, " + failed + " failed/errored, " + skipped +
                        " unsupported/untrusted/cancelled. (Gradle projects use the gradle test task.)"
                    val result = BuildRunner.BuildResult(
                        status = if (failed > 0) BuildRunner.BuildStatus.FAILED else BuildRunner.BuildStatus.SUCCESS,
                        output = summary,
                        durationMs = results.sumOf { it.durationMs },
                        errorCount = failed,
                    )
                    markDone(taskId, result)
                    return result
                } catch (ce: CancellationException) {
                    // Mirror PR13: a cancelled UI scope marks the run FAILED honestly, then rethrows.
                    withContext(NonCancellable) {
                        markDone(taskId, BuildRunner.BuildResult(
                            status = BuildRunner.BuildStatus.FAILED,
                            output = "Task interrupted: the UI that started this run was closed mid-run.",
                            durationMs = 0,
                            errorCount = 1,
                        ))
                    }
                    throw ce
                } catch (e: Exception) {
                    val failedResult = BuildRunner.BuildResult(
                        status = BuildRunner.BuildStatus.FAILED,
                        output = "Run Unit Tests error: ${e.message}",
                        durationMs = 0,
                        errorCount = 1,
                    )
                    markDone(taskId, failedResult)
                    return failedResult
                }
            }
        }

        markRunning(taskId)
        return try {
            val result = BuildRunner.runBuild(
                context = context,
                projectPath = projectPath,
                task = task.gradleTask,
            )
            markDone(taskId, result)
            val gradleProblems = GradleErrorParser.extractAllProblems(result.output)
            onProblemsUpdate?.invoke(gradleProblems)
            // CW1: task problems -> Problems panel (was dead path: publishBuildDiagnostics existed, never called)
            if (gradleProblems.isEmpty()) {
                com.codespace.ide.diagnostics.DiagnosticPublisher.clearBuildDiagnostics()
            } else {
                com.codespace.ide.diagnostics.DiagnosticPublisher.publishBuildDiagnostics(gradleProblems)
            }
            result
        } catch (ce: CancellationException) {
            // PR13 (P4a-2): UI-scope cancellation (the task panel's composition
            // scope dying mid-run) previously fell into the generic catch and
            // then returned "normally" from a cancelled coroutine — worse, if
            // the cancellation landed between markRunning and the try, the
            // RUNNING entry stayed FOREVER in this process-lifetime singleton
            // while nothing could ever clear it. Mark FAILED honestly and
            // rethrow so the caller's scope observes the cancellation.
            // NOTE: the gradle process itself may keep running (BuildRunner's
            // process handle) — that orphan is the TP06/DG13 family, not PR13.
            withContext(NonCancellable) {
                markDone(taskId, BuildRunner.BuildResult(
                    status = BuildRunner.BuildStatus.FAILED,
                    output = "Task interrupted: the UI that started this run was closed mid-run.",
                    durationMs = 0,
                    errorCount = 1,
                ))
            }
            throw ce
        } catch (e: Exception) {
            val failed = BuildRunner.BuildResult(
                status = BuildRunner.BuildStatus.FAILED,
                output = "Task error: ${e.message}",
                durationMs = 0,
                errorCount = 1,
            )
            markDone(taskId, failed)
            failed
        }
    }

    /**
     * PR13 (P4a-2): restore last-known run states from TaskRunStore at app
     * startup. A persisted RUNNING state means the app died mid-run — it is
     * converted to FAILED with an honest interruption message (a zombie tile
     * must never claim a run is still going). Terminal states restore as-is
     * (result details are not persisted — the tile shows the state, the log
     * area shows nothing until the next real run). IDLE is never persisted.
     */
    fun restorePersistedState(context: android.content.Context) {
        TaskRunStore.init(context)
        val restored = mutableMapOf<TaskId, TaskRun>()
        for ((id, state) in TaskRunStore.savedStates()) {
            val restoredRun = when (state) {
                RunState.RUNNING -> TaskRun(
                    id, RunState.FAILED,
                    BuildRunner.BuildResult(
                        status = BuildRunner.BuildStatus.FAILED,
                        output = "Run interrupted: the app restarted while this task was running. " +
                            "The gradle process may have been orphaned — check the terminal before re-running.",
                        durationMs = 0,
                        errorCount = 1,
                    ),
                )
                RunState.SUCCESS -> TaskRun(id, RunState.SUCCESS)
                RunState.FAILED -> TaskRun(id, RunState.FAILED)
                RunState.IDLE -> null
            } ?: continue
            restored[id] = restoredRun
            // Write the swept state back so the interruption is durable.
            TaskRunStore.saveState(id, restoredRun.state, System.currentTimeMillis())
        }
        if (restored.isNotEmpty()) _runs.value = restored
    }

    /** Reset a task's state back to IDLE. */
    fun reset(taskId: TaskId) {
        _runs.value = _runs.value - taskId
        TaskRunStore.saveState(taskId, RunState.IDLE, System.currentTimeMillis())
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun markRunning(id: TaskId) {
        _runs.value = _runs.value + (id to TaskRun(id, RunState.RUNNING))
        TaskRunStore.saveState(id, RunState.RUNNING, System.currentTimeMillis())
    }

    private fun markDone(id: TaskId, result: BuildRunner.BuildResult) {
        val state = if (result.status == BuildRunner.BuildStatus.SUCCESS) RunState.SUCCESS else RunState.FAILED
        _runs.value = _runs.value + (id to TaskRun(id, state, result))
        TaskRunStore.saveState(id, state, System.currentTimeMillis())
    }
}
