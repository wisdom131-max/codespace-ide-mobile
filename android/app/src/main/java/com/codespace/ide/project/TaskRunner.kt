package com.codespace.ide.project

import android.content.Context
import com.codespace.ide.build.BuildRunner
import com.codespace.ide.build.GradleErrorParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        markRunning(taskId)
        return try {
            val result = BuildRunner.runBuild(
                context = context,
                projectPath = projectPath,
                task = task.gradleTask,
            )
            markDone(taskId, result)
            onProblemsUpdate?.invoke(GradleErrorParser.extractAllProblems(result.output))
            result
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

    /** Reset a task's state back to IDLE. */
    fun reset(taskId: TaskId) {
        _runs.value = _runs.value - taskId
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun markRunning(id: TaskId) {
        _runs.value = _runs.value + (id to TaskRun(id, RunState.RUNNING))
    }

    private fun markDone(id: TaskId, result: BuildRunner.BuildResult) {
        val state = if (result.status == BuildRunner.BuildStatus.SUCCESS) RunState.SUCCESS else RunState.FAILED
        _runs.value = _runs.value + (id to TaskRun(id, state, result))
    }
}
