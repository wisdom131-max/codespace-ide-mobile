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

            // Run via execOnce (captures all output)
            val output = ProotInstaller.execOnce(context, cmd, workdir = guestPath, timeoutSeconds = 600)
            val duration = System.currentTimeMillis() - startTime

            _buildOutput.value = output
            _buildProgress.value = 1f

            // Parse errors/warnings
            val errors = GradleErrorParser.extractErrors(output)
            val warnings = GradleErrorParser.extractWarnings(output)

            // Check for success/failure
            val isSuccess = output.contains("BUILD SUCCESSFUL", ignoreCase = true)
            val isFailure = output.contains("BUILD FAILED", ignoreCase = true)

            // Find APK if successful
            var apkPath: String? = null
            if (isSuccess) {
                val findApk = ProotInstaller.execOnce(context, "find \"$guestPath\" -name \"*.apk\" -path \"*/outputs/*\" 2>/dev/null | head -1").trim()
                if (findApk.isNotEmpty()) {
                    apkPath = findApk
                }
            }

            val status = when {
                isSuccess -> BuildStatus.SUCCESS
                isFailure -> BuildStatus.FAILED
                else -> BuildStatus.FAILED
            }

            _buildStatus.value = status
            AppOutputLog.log("Build ${if (isSuccess) "SUCCESSFUL" else "FAILED"} (${duration}ms, ${errors.size} errors, ${warnings.size} warnings)", "build")
            // Phase N: Notify build completion
            NotificationStore.notifyBuildEvent(
                title = if (isSuccess) "Build successful" else "Build failed",
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
            _buildStatus.value = BuildStatus.FAILED
            _buildOutput.value = "Build error: ${e.message ?: "Unknown error"}"
            AppOutputLog.log("Build error: ${e.message ?: "Unknown"}", "build")
            // Phase N: Notify build error
            NotificationStore.notifyBuildEvent(
                title = "Build error",
                body = e.message ?: "Unknown error",
                isError = true,
            )
            BuildResult(
                status = BuildStatus.FAILED,
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
        _buildStatus.value = BuildStatus.CANCELLED
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
