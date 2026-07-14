package com.codespace.ide.build

import android.content.Context
import android.util.Log
import com.codespace.ide.terminal.ProotInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 11-A/B/C: Detect and validate the Android build environment inside Ubuntu proot.
 *
 * Reuses ProotInstaller.execOnce() to run detection commands inside the proot environment.
 * All detection runs off the UI thread via coroutines.
 *
 * Detected tools: JDK (java/javac), Gradle, Android SDK (sdkmanager), Platform Tools (adb),
 * Build Tools (aapt2, zipalign, apksigner).
 */
object BuildEnvironment {

    private const val TAG = "BuildEnvironment"

    enum class ToolStatus { OK, MISSING, BROKEN, UNKNOWN }

    data class ToolInfo(
        val name: String,
        val displayName: String,
        val version: String,
        val path: String,
        val status: ToolStatus,
        val details: String = "",
    )

    data class EnvironmentReport(
        val tools: List<ToolInfo>,
        val overallHealthy: Boolean,
        val missingTools: List<String>,
        val recommendations: List<String>,
    )

    /**
     * Run a command inside Ubuntu proot and return trimmed output.
     * Returns empty string on failure.
     */
    private suspend fun execInProot(context: Context, command: String, timeout: Long = 30): String =
        withContext(Dispatchers.IO) {
            try {
                val result = ProotInstaller.execOnce(context, command, timeoutSeconds = timeout)
                result.trim()
            } catch (e: Exception) {
                Log.w(TAG, "exec failed: $command — ${e.message}")
                ""
            }
        }

    /**
     * Detect a single tool by running `which <binary>` and `<binary> --version`.
     */
    private suspend fun detectTool(
        context: Context,
        binary: String,
        displayName: String,
        versionCmd: String,
    ): ToolInfo {
        // Check if binary exists
        val whichPath = execInProot(context, "which $binary 2>/dev/null")
        if (whichPath.isEmpty()) {
            return ToolInfo(
                name = binary,
                displayName = displayName,
                version = "",
                path = "",
                status = ToolStatus.MISSING,
                details = "Binary not found in PATH",
            )
        }

        // Get version
        val versionOutput = execInProot(context, versionCmd, timeout = 15)
        val version = parseVersion(versionOutput)

        return ToolInfo(
            name = binary,
            displayName = displayName,
            version = version.ifEmpty { "unknown" },
            path = whichPath,
            status = if (version.isNotEmpty()) ToolStatus.OK else ToolStatus.BROKEN,
            details = if (version.isEmpty()) "Binary exists but version check failed" else "",
        )
    }

    /**
     * Full environment validation — detects all build tools in parallel-friendly order.
     * Runs off the UI thread.
     */
    suspend fun validateEnvironment(context: Context): EnvironmentReport =
        withContext(Dispatchers.IO) {
            val tools = mutableListOf<ToolInfo>()

            // JDK
            val javaInfo = detectTool(context, "java", "Java Runtime", "java -version 2>&1")
            tools.add(javaInfo)

            val javacInfo = detectTool(context, "javac", "Java Compiler", "javac -version 2>&1")
            tools.add(javacInfo)

            // Check JAVA_HOME
            val javaHome = execInProot(context, "echo \$JAVA_HOME")
            if (javaHome.isNotEmpty() && javaHome != "\$JAVA_HOME") {
                tools.add(ToolInfo(
                    name = "JAVA_HOME",
                    displayName = "JAVA_HOME",
                    version = "",
                    path = javaHome,
                    status = if (javaInfo.status == ToolStatus.OK) ToolStatus.OK else ToolStatus.UNKNOWN,
                    details = "Environment variable set",
                ))
            } else {
                tools.add(ToolInfo(
                    name = "JAVA_HOME",
                    displayName = "JAVA_HOME",
                    version = "",
                    path = "",
                    status = ToolStatus.MISSING,
                    details = "JAVA_HOME not set — Gradle may fail to find JDK",
                ))
            }

            // Gradle
            val gradleInfo = detectTool(context, "gradle", "Gradle", "gradle --version 2>&1 | head -5")
            tools.add(gradleInfo)

            // Android SDK manager
            val sdkManagerInfo = detectTool(context, "sdkmanager", "Android SDK Manager", "sdkmanager --version 2>&1")
            tools.add(sdkManagerInfo)

            // ADB (Platform Tools)
            val adbInfo = detectTool(context, "adb", "Android Debug Bridge", "adb --version 2>&1 | head -2")
            tools.add(adbInfo)

            // AAPT2 (Build Tools)
            val aapt2Info = detectTool(context, "aapt2", "AAPT2", "aapt2 version 2>&1")
            tools.add(aapt2Info)

            // Zipalign
            val zipalignInfo = detectTool(context, "zipalign", "Zipalign", "zipalign 2>&1 | head -1")
            tools.add(zipalignInfo)

            // Apksigner
            val apksignerInfo = detectTool(context, "apksigner", "APK Signer", "apksigner --version 2>&1 | head -1")
            tools.add(apksignerInfo)

            // ANDROID_HOME / ANDROID_SDK_ROOT
            val androidHome = execInProot(context, "echo \$ANDROID_HOME")
            val androidSdkRoot = execInProot(context, "echo \$ANDROID_SDK_ROOT")
            val sdkEnvSet = (androidHome.isNotEmpty() && androidHome != "\$ANDROID_HOME") ||
                           (androidSdkRoot.isNotEmpty() && androidSdkRoot != "\$ANDROID_SDK_ROOT")
            tools.add(ToolInfo(
                name = "ANDROID_HOME",
                displayName = "Android SDK Home",
                version = "",
                path = androidHome.ifEmpty { androidSdkRoot },
                status = if (sdkEnvSet) ToolStatus.OK else ToolStatus.MISSING,
                details = if (sdkEnvSet) "SDK root environment variable set" else "ANDROID_HOME/ANDROID_SDK_ROOT not set",
            ))

            // Build recommendations
            val missing = tools.filter { it.status == ToolStatus.MISSING }.map { it.displayName }
            val broken = tools.filter { it.status == ToolStatus.BROKEN }.map { it.displayName }

            val recommendations = mutableListOf<String>()
            if ("Java Runtime" in missing || "Java Compiler" in missing) {
                recommendations.add("Install JDK: apt-get install -y openjdk-17-jdk-headless")
            }
            if ("Gradle" in missing) {
                recommendations.add("Install Gradle: apt-get install -y gradle  (or use project's gradlew wrapper)")
            }
            if ("Android SDK Manager" in missing) {
                recommendations.add("Install Android SDK command-line tools from developer.android.com")
            }
            if ("Android Debug Bridge" in missing) {
                recommendations.add("Install Platform Tools: apt-get install -y adb  (or download from Android SDK)")
            }
            if ("AAPT2" in missing || "Zipalign" in missing || "APK Signer" in missing) {
                recommendations.add("Install Android Build Tools via sdkmanager")
            }
            if ("JAVA_HOME" in missing) {
                recommendations.add("Set JAVA_HOME: export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64")
            }
            if ("Android SDK Home" in missing) {
                recommendations.add("Set ANDROID_HOME: export ANDROID_HOME=/opt/android-sdk")
            }

            EnvironmentReport(
                tools = tools,
                overallHealthy = missing.isEmpty() && broken.isEmpty(),
                missingTools = missing,
                recommendations = recommendations,
            )
        }

    /**
     * Quick check — is the environment ready to build Android projects?
     * Returns (ready, missingCritical) where critical = JDK + Gradle.
     */
    suspend fun isBuildReady(context: Context): Pair<Boolean, List<String>> {
        val report = validateEnvironment(context)
        val criticalMissing = report.missingTools.filter { it in listOf("Java Runtime", "Java Compiler", "Gradle") }
        return Pair(criticalMissing.isEmpty(), criticalMissing)
    }

    /**
     * Parse version string from command output.
     * Handles common formats: "openjdk version \"17.0.1\"", "Gradle 8.4", etc.
     */
    private fun parseVersion(output: String): String {
        if (output.isEmpty()) return ""
        val lines = output.lines()
        for (line in lines) {
            // Java version: openjdk version "17.0.1"
            val javaMatch = Regex("""version "([^"]+)"""").find(line)
            if (javaMatch != null) return javaMatch.groupValues[1]

            // Gradle version: Gradle 8.4
            val gradleMatch = Regex("""Gradle\s+([\d.]+)""").find(line)
            if (gradleMatch != null) return gradleMatch.groupValues[1]

            // Generic version number
            val genericMatch = Regex("""\b(\d+\.\d+(?:\.\d+)?)\b""").find(line)
            if (genericMatch != null) return genericMatch.groupValues[1]
        }
        return ""
    }
}
