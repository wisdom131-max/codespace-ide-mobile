package com.codespace.ide.build

import android.content.Context
import android.util.Log
import com.codespace.ide.diagnostics.AppOutputLog
import com.codespace.ide.data.NotificationStore
import com.codespace.ide.terminal.ProotInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 11-H: Gradle build executor with live stdout/stderr streaming.
 *
 * Runs build commands inside Ubuntu proot via ProotInstaller.execOnce() for synchronous builds,
 * or via direct process builder for streaming builds (gradlew).
 *
 * All execution runs off the UI thread. Progress is exposed via StateFlow for UI binding.
 */
object BuildRunner {

    private const val TAG = "BuildRunner"

    enum class BuildStatus { IDLE, VALIDATING, BUILDING, SUCCESS, FAILED, CANCELLED }

    data class BuildResult(
        val status: BuildStatus,
        val output: String,
        val durationMs: Long,
        val apkPath: String? = null,
        val errorCount: Int = 0,
        val warningCount: Int = 0,
    )

    private val _buildStatus = MutableStateFlow(BuildStatus.IDLE)
    val buildStatus: StateFlow<BuildStatus> = _buildStatus.asStateFlow()

    private val _buildOutput = MutableStateFlow("")
    val buildOutput: StateFlow<String> = _buildOutput.asStateFlow()

    private val _buildProgress = MutableStateFlow(0f)
    val buildProgress: StateFlow<Float> = _buildProgress.asStateFlow()

    private var currentProcess: Process? = null

    /**
     * Check if a project has a Gradle wrapper (gradlew) or build.gradle.
     */
    fun isGradleProject(projectPath: String): Boolean {
        val dir = File(projectPath)
        return File(dir, "build.gradle").exists() ||
               File(dir, "build.gradle.kts").exists() ||
               File(dir, "settings.gradle").exists() ||
               File(dir, "settings.gradle.kts").exists() ||
               File(dir, "gradlew").exists()
    }

    /**
     * Validate project before building.
     * Checks: gradle files exist, gradlew is executable, local.properties if needed.
     */
    suspend fun validateProject(context: Context, projectPath: String): List<String> {
        val issues = mutableListOf<String>()
        val dir = File(projectPath)

        if (!dir.exists()) {
            issues.add("Project directory does not exist: $projectPath")
            return issues
        }

        if (!isGradleProject(projectPath)) {
            issues.add("Not a Gradle project (no build.gradle or settings.gradle found)")
            return issues
        }

        // Check gradlew exists and is executable
        val gradlew = File(dir, "gradlew")
        if (!gradlew.exists()) {
            issues.add("gradlew not found — will use system gradle if available")
        }

        // Check build.gradle or build.gradle.kts
        val hasBuildFile = File(dir, "build.gradle").exists() || File(dir, "build.gradle.kts").exists()
        if (!hasBuildFile) {
            issues.add("No build.gradle or build.gradle.kts found")
        }

        // Check settings.gradle
        val hasSettings = File(dir, "settings.gradle").exists() || File(dir, "settings.gradle.kts").exists()
        if (!hasSettings) {
            issues.add("No settings.gradle found — Gradle may not find subprojects")
        }

        return issues
    }

    /**
     * Run a Gradle build with live output streaming.
     *
     * @param context Android context
     * @param projectPath Host path to the project directory
     * @param task Gradle task to run (e.g. "assembleDebug", "assembleRelease")
     * @return BuildResult with full output and status
     */
    suspend fun runBuild(
        context: Context,
        projectPath: String,
        task: String = "assembleDebug",
    ): BuildResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        _buildStatus.value = BuildStatus.BUILDING
        _buildOutput.value = "Building: $task\n"
        _buildProgress.value = 0f
        AppOutputLog.log("Build started: $task", "build")
        // Phase N: Notify build start
        NotificationStore.notifyBuildEvent(
            title = "Build started",
            body = "Running Gradle task: $task",
            progress = NotificationStore.ProgressInfo(indeterminate = true, statusMessage = "Building..."),
        )

        try {
            // Map host path to guest path for proot
            val guestPath = ProotInstaller.hostToGuestPath(context, projectPath) ?: "/host-files/projects/${File(projectPath).name}"

            // Build command — use gradlew if available, else system gradle
            val gradlewExists = ProotInstaller.execOnce(context, "test -f \"$guestPath/gradlew\" && echo yes || echo no").trim()
            val cmd = if (gradlewExists == "yes") {
                "cd \"$guestPath\" && chmod +x gradlew && ./gradlew $task --no-daemon --console=plain 2>&1"
            } else {
                "cd \"$guestPath\" && gradle $task --no-daemon --console=plain 2>&1"
            }

            // PR01+PR03 (P3a): run via the TYPED executor.
            // - onProcess stores the live Process so cancelBuild() actually
            //   destroys the running gradle (the old code never assigned
            //   currentProcess — cancelBuild() destroyed null while the build
            //   kept running in proot).
            // - onLine streams output LIVE to the build panel (the old code
            //   wrote _buildOutput only at start and completion, so a
            //   multi-minute build showed nothing while running).
            // - Status comes from the EXIT CODE, not from grepping
            //   "BUILD SUCCESSFUL"/"BUILD FAILED" markers which the old
            //   2000-line capture cap could swallow (PR03: a truncated failed
            //   build fell into the else-FAILED branch and a truncated
            //   successful one lost its marker).
            // - maxLines raised to 10000 so late compile errors survive;
            //   truncation is surfaced with a header line, not hidden.
            val outBuf = StringBuilder("Building: $task\n")
            var streamed = 0
            val result = ProotInstaller.execTyped(
                context, cmd, workdir = guestPath, timeoutSeconds = 600,
                maxLines = 10000,
                onLine = { line ->
                    synchronized(outBuf) { outBuf.append(line).append('\n') }
                    streamed++
                    if (streamed % 25 == 0) _buildOutput.value = outBuf.toString()
                },
                onProcess = { proc -> currentProcess = proc },
            )
            val duration = System.currentTimeMillis() - startTime
            val output = buildString {
                if (result.timedOut) append("[Timed out after 600s]\n")
                if (result.truncated) append("[output truncated at 10000 lines — status is by exit code]\n")
                append(result.stdout)
            }
            _buildOutput.value = output
            _buildProgress.value = 1f

            // Parse errors/warnings
            val errors = GradleErrorParser.extractErrors(output)
            val warnings = GradleErrorParser.extractWarnings(output)

            // PR01: CANCELLED is sticky — cancelBuild() destroys the process,
            // the executor then returns a failed result, and this completing
            // coroutine must NOT re-label the user's cancel as SUCCESS/FAILED.
            val wasCancelled = _buildStatus.value == BuildStatus.CANCELLED
            val status = when {
                wasCancelled -> BuildStatus.CANCELLED
                result.succeeded -> BuildStatus.SUCCESS
                else -> BuildStatus.FAILED
            }
            val isSuccess = status == BuildStatus.SUCCESS

            // Find APK if successful
            var apkPath: String? = null
            if (isSuccess) {
                val findApk = ProotInstaller.execOnce(context, "find \"$guestPath\" -name \"*.apk\" -path \"*/outputs/*\" 2>/dev/null | head -1").trim()
                if (findApk.isNotEmpty()) {
                    apkPath = findApk
                }
            }

            _buildStatus.value = status
            AppOutputLog.log("Build ${status} (${duration}ms, ${errors.size} errors, ${warnings.size} warnings)", "build")
            // Phase N: Notify build completion
            NotificationStore.notifyBuildEvent(
                title = when (status) { BuildStatus.SUCCESS -> "Build successful"; BuildStatus.CANCELLED -> "Build cancelled"; else -> "Build failed" },
                body = "${errors.size} errors, ${warnings.size} warnings (${duration}ms)" +
                    if (apkPath != null) " — APK ready" else "",
                isError = !isSuccess,
                actions = if (!isSuccess) listOf(
                    NotificationStore.NotificationAction("view_logs", "View Logs"),
                ) else emptyList(),
            )

            BuildResult(
                status = status,
                output = output,
                durationMs = duration,
                apkPath = apkPath,
                errorCount = errors.size,
                warningCount = warnings.size,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Build failed", e)
            val duration = System.currentTimeMillis() - startTime
            // PR01: a cancellation that surfaces as an exception must not be
            // re-labeled FAILED either — CANCELLED stays sticky.
            if (_buildStatus.value != BuildStatus.CANCELLED) _buildStatus.value = BuildStatus.FAILED
            _buildOutput.value = "Build error: ${e.message ?: "Unknown error"}"
            AppOutputLog.log("Build error: ${e.message ?: "Unknown"}", "build")
            // Phase N: Notify build error
            NotificationStore.notifyBuildEvent(
                title = "Build error",
                body = e.message ?: "Unknown error",
                isError = true,
            )
            BuildResult(
                status = _buildStatus.value,
                output = "Build error: ${e.message ?: "Unknown error"}",
                durationMs = duration,
                errorCount = 1,
            )
        }
    }

    /**
     * Cancel any running build.
     */
    fun cancelBuild() {
        currentProcess?.destroyForcibly()
        currentProcess = null
        // PR01: only a RUNNING build can be cancelled — never overwrite a
        // terminal SUCCESS/FAILED from a build that already completed.
        if (_buildStatus.value == BuildStatus.BUILDING || _buildStatus.value == BuildStatus.VALIDATING) {
            _buildStatus.value = BuildStatus.CANCELLED
        }
        // Phase N: Notify build cancelled
        NotificationStore.notifyBuildEvent(
            title = "Build cancelled",
            body = "Build was cancelled by user",
        )
    }

    /**
     * Reset state to idle.
     */
    fun reset() {
        _buildStatus.value = BuildStatus.IDLE
        _buildOutput.value = ""
        _buildProgress.value = 0f
    }
}
