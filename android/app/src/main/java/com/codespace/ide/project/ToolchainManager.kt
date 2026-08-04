package com.codespace.ide.project

import android.content.Context
import android.util.Log
import com.codespace.ide.terminal.ProotInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Phase 12-C — Toolchain Manager
 *
 * Detects, validates, and reports status of development tools
 * available inside the Ubuntu proot environment:
 *   JDK · Gradle · Android SDK · Flutter · Node.js · Python
 *
 * Does NOT install tools — that belongs to Phase 11 (BuildEnvironment).
 * Reports status as a list of ToolStatus entries via StateFlow for UI binding.
 */
object ToolchainManager {

    private const val TAG = "ToolchainManager"

    enum class ToolId {
        JDK, GRADLE, ANDROID_SDK, ANDROID_BUILD_TOOLS, PLATFORM_TOOLS,
        FLUTTER, DART, NODEJS, NPM, PYTHON3, PIP,
    }

    enum class ToolHealth { OK, MISSING, BROKEN, UNKNOWN }

    data class ToolStatus(
        val id: ToolId,
        val displayName: String,
        val health: ToolHealth,
        val version: String?,          // detected version string or null
        val path: String?,             // detected path or null
        val note: String? = null,      // human-readable hint / error
        val installCmd: String? = null, // suggested terminal install command
    )

    data class ToolchainReport(
        val tools: List<ToolStatus>,
        val overallHealthy: Boolean,
        val criticalMissing: List<ToolId>,   // tools required for Android builds
    )

    private val _report = MutableStateFlow<ToolchainReport?>(null)
    val report: StateFlow<ToolchainReport?> = _report.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    // Tools required for a successful Android build
    private val ANDROID_CRITICAL = setOf(ToolId.JDK, ToolId.GRADLE, ToolId.ANDROID_SDK)

    /**
     * Run a full toolchain scan in the Ubuntu proot environment.
     * Results are emitted to [report] and also returned directly.
     */
    suspend fun scan(context: Context): ToolchainReport = withContext(Dispatchers.IO) {
        _scanning.value = true
        try {
            val results = mutableListOf<ToolStatus>()
            results += detectJdk(context)
            results += detectGradle(context)
            results += detectAndroidSdk(context)
            results += detectAndroidBuildTools(context)
            results += detectPlatformTools(context)
            results += detectFlutter(context)
            results += detectDart(context)
            results += detectNode(context)
            results += detectNpm(context)
            results += detectPython(context)
            results += detectPip(context)

            val criticalMissing = results
                .filter { it.id in ANDROID_CRITICAL && it.health != ToolHealth.OK }
                .map { it.id }

            val report = ToolchainReport(
                tools = results,
                overallHealthy = criticalMissing.isEmpty(),
                criticalMissing = criticalMissing,
            )
            _report.value = report
            report
        } finally {
            _scanning.value = false
        }
    }

    // ── Detectors ─────────────────────────────────────────────────────────────

    private suspend fun detectJdk(ctx: Context): ToolStatus {
        val out = exec(ctx, "java -version 2>&1 | head -1")
        return when {
            out.contains("version") -> ToolStatus(
                id = ToolId.JDK,
                displayName = "JDK",
                health = ToolHealth.OK,
                version = extractQuoted(out) ?: out.trim(),
                path = exec(ctx, "which java").trim().ifEmpty { null },
            )
            out.contains("not found") || out.isBlank() -> ToolStatus(
                id = ToolId.JDK, displayName = "JDK",
                health = ToolHealth.MISSING, version = null, path = null,
                note = "Install JDK 17+ via the Package Manager",
                installCmd = "pkg install openjdk-17",
            )
            else -> ToolStatus(
                id = ToolId.JDK, displayName = "JDK",
                health = ToolHealth.BROKEN, version = null, path = null,
                note = out.trim().take(120),
            )
        }
    }

    private suspend fun detectGradle(ctx: Context): ToolStatus {
        val out = exec(ctx, "gradle --version 2>&1 | grep '^Gradle' | head -1")
        return when {
            out.contains("Gradle") -> ToolStatus(
                id = ToolId.GRADLE, displayName = "Gradle",
                health = ToolHealth.OK,
                version = out.trim().removePrefix("Gradle").trim(),
                path = exec(ctx, "which gradle").trim().ifEmpty { null },
            )
            else -> {
                val which = exec(ctx, "which gradle 2>&1").trim()
                if (which.startsWith("/")) {
                    ToolStatus(
                        id = ToolId.GRADLE, displayName = "Gradle",
                        health = ToolHealth.BROKEN, version = null, path = which,
                        note = "Gradle found but failed to run",
                    )
                } else {
                    ToolStatus(
                        id = ToolId.GRADLE, displayName = "Gradle",
                        health = ToolHealth.MISSING, version = null, path = null,
                        note = "Install Gradle via the Package Manager (sdkman or apt)",
                        installCmd = "pkg install gradle",
                    )
                }
            }
        }
    }

    private suspend fun detectAndroidSdk(ctx: Context): ToolStatus {
        // Check ANDROID_HOME or common paths
        val home = exec(ctx, "echo \$ANDROID_HOME || echo \$ANDROID_SDK_ROOT").trim()
        val paths = listOf(home, "/opt/android-sdk", "/root/Android/Sdk", "/usr/local/android-sdk")
            .filter { it.isNotBlank() }

        for (path in paths) {
            val exists = exec(ctx, "test -d \"$path\" && echo yes || echo no").trim()
            if (exists == "yes") {
                val platforms = exec(ctx, "ls \"$path/platforms\" | head -3").trim()
                return ToolStatus(
                    id = ToolId.ANDROID_SDK, displayName = "Android SDK",
                    health = ToolHealth.OK,
                    version = platforms.lines().lastOrNull()?.trim(),
                    path = path,
                    note = if (platforms.isBlank()) "No platforms installed" else null,
                )
            }
        }
        return ToolStatus(
            id = ToolId.ANDROID_SDK, displayName = "Android SDK",
            health = ToolHealth.MISSING, version = null, path = null,
            note = "Set ANDROID_HOME or install via Build Environment panel",
            installCmd = "pkg install android-sdk",
        )
    }

    private suspend fun detectAndroidBuildTools(ctx: Context): ToolStatus {
        val out = exec(ctx, "aapt version 2>&1").trim()
        return if (out.contains("Android Asset Packaging Tool")) {
            ToolStatus(
                id = ToolId.ANDROID_BUILD_TOOLS, displayName = "Build Tools (aapt)",
                health = ToolHealth.OK,
                version = out.lines().firstOrNull()?.trim(),
                path = exec(ctx, "which aapt").trim().ifEmpty { null },
            )
        } else {
            ToolStatus(
                id = ToolId.ANDROID_BUILD_TOOLS, displayName = "Build Tools (aapt)",
                health = ToolHealth.MISSING, version = null, path = null,
                note = "Install Android Build Tools via Android SDK Manager",
                installCmd = "sdkmanager \"build-tools;34.0.0\"",
            )
        }
    }

    private suspend fun detectPlatformTools(ctx: Context): ToolStatus {
        val out = exec(ctx, "adb version 2>&1 | head -1").trim()
        return if (out.contains("Android Debug Bridge")) {
            ToolStatus(
                id = ToolId.PLATFORM_TOOLS, displayName = "Platform Tools (adb)",
                health = ToolHealth.OK,
                version = out.substringAfter("version").trim().ifEmpty { null },
                path = exec(ctx, "which adb").trim().ifEmpty { null },
            )
        } else {
            ToolStatus(
                id = ToolId.PLATFORM_TOOLS, displayName = "Platform Tools (adb)",
                health = ToolHealth.MISSING, version = null, path = null,
                note = "Install Android Platform Tools via SDK Manager",
                installCmd = "sdkmanager \"platform-tools\"",
            )
        }
    }

    private suspend fun detectFlutter(ctx: Context): ToolStatus {
        val out = exec(ctx, "flutter --version 2>&1 | head -2").trim()
        return if (out.contains("Flutter")) {
            ToolStatus(
                id = ToolId.FLUTTER, displayName = "Flutter",
                health = ToolHealth.OK,
                version = out.lines().firstOrNull()?.trim(),
                path = exec(ctx, "which flutter").trim().ifEmpty { null },
            )
        } else {
            ToolStatus(
                id = ToolId.FLUTTER, displayName = "Flutter",
                health = ToolHealth.MISSING, version = null, path = null,
                note = "Install Flutter SDK for Flutter projects",
                installCmd = "git clone https://github.com/flutter/flutter.git -b stable ~/flutter && export PATH=\$PATH:~/flutter/bin",
            )
        }
    }

    private suspend fun detectDart(ctx: Context): ToolStatus {
        val out = exec(ctx, "dart --version 2>&1").trim()
        return if (out.contains("Dart")) {
            ToolStatus(
                id = ToolId.DART, displayName = "Dart",
                health = ToolHealth.OK,
                version = out.trim(),
                path = exec(ctx, "which dart").trim().ifEmpty { null },
            )
        } else {
            ToolStatus(
                id = ToolId.DART, displayName = "Dart",
                health = ToolHealth.MISSING, version = null, path = null,
                installCmd = "pkg install dart",
            )
        }
    }

    private suspend fun detectNode(ctx: Context): ToolStatus {
        val out = exec(ctx, "node --version 2>&1").trim()
        return if (out.startsWith("v")) {
            ToolStatus(
                id = ToolId.NODEJS, displayName = "Node.js",
                health = ToolHealth.OK, version = out,
                path = exec(ctx, "which node").trim().ifEmpty { null },
            )
        } else {
            ToolStatus(
                id = ToolId.NODEJS, displayName = "Node.js",
                health = ToolHealth.MISSING, version = null, path = null,
                note = "Install Node.js for React Native and web projects",
                installCmd = "pkg install nodejs",
            )
        }
    }

    private suspend fun detectNpm(ctx: Context): ToolStatus {
        val out = exec(ctx, "npm --version 2>&1").trim()
        return if (out.matches(Regex("\\d+\\.\\d+.*"))) {
            ToolStatus(
                id = ToolId.NPM, displayName = "npm",
                health = ToolHealth.OK, version = out,
                path = exec(ctx, "which npm").trim().ifEmpty { null },
            )
        } else {
            ToolStatus(
                id = ToolId.NPM, displayName = "npm",
                health = ToolHealth.MISSING, version = null, path = null,
                installCmd = "pkg install nodejs",
            )
        }
    }

    private suspend fun detectPython(ctx: Context): ToolStatus {
        val out = exec(ctx, "python3 --version 2>&1").trim()
        return if (out.startsWith("Python")) {
            ToolStatus(
                id = ToolId.PYTHON3, displayName = "Python 3",
                health = ToolHealth.OK,
                version = out.removePrefix("Python").trim(),
                path = exec(ctx, "which python3").trim().ifEmpty { null },
            )
        } else {
            ToolStatus(
                id = ToolId.PYTHON3, displayName = "Python 3",
                health = ToolHealth.MISSING, version = null, path = null,
                note = "Install Python 3 for Python projects",
                installCmd = "pkg install python",
            )
        }
    }

    private suspend fun detectPip(ctx: Context): ToolStatus {
        val out = exec(ctx, "pip3 --version 2>&1").trim()
        return if (out.contains("pip")) {
            ToolStatus(
                id = ToolId.PIP, displayName = "pip",
                health = ToolHealth.OK,
                version = out.substringBefore(" from").trim(),
                path = exec(ctx, "which pip3").trim().ifEmpty { null },
            )
        } else {
            ToolStatus(
                id = ToolId.PIP, displayName = "pip",
                health = ToolHealth.MISSING, version = null, path = null,
                installCmd = "pip3 install --break-system-packages --upgrade pip",
            )
        }
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private suspend fun exec(ctx: Context, cmd: String): String =
        try { ProotInstaller.execOnce(ctx, cmd) } catch (e: Exception) { "" }

    private fun extractQuoted(s: String): String? =
        Regex("\"([^\"]+)\"").find(s)?.groupValues?.getOrNull(1)
}
