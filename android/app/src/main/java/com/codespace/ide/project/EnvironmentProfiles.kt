package com.codespace.ide.project

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Phase 12-H — Environment Profiles
 *
 * Predefined per-stack development profiles. Each profile declares:
 *   - Which tools are required
 *   - Which tools are recommended
 *   - Suggested env variables
 *
 * The active profile is persisted in SharedPreferences.
 * Switching profiles updates the toolchain recommendations shown in the UI.
 */
object EnvironmentProfiles {

    private const val PREFS = "env_profiles"
    private const val KEY_ACTIVE = "active_profile"

    enum class ProfileId {
        ANDROID, FLUTTER, WEB, PYTHON, NODEJS, GENERIC
    }

    data class Profile(
        val id: ProfileId,
        val displayName: String,
        val description: String,
        val requiredTools: List<ToolchainManager.ToolId>,
        val recommendedTools: List<ToolchainManager.ToolId>,
        val suggestedEnvVars: Map<String, String>,  // var name -> description
    )

    val PROFILES: List<Profile> = listOf(
        Profile(
            id = ProfileId.ANDROID,
            displayName = "Android Development",
            description = "Build and test Android apps with Kotlin/Java",
            requiredTools = listOf(
                ToolchainManager.ToolId.JDK,
                ToolchainManager.ToolId.GRADLE,
                ToolchainManager.ToolId.ANDROID_SDK,
            ),
            recommendedTools = listOf(
                ToolchainManager.ToolId.ANDROID_BUILD_TOOLS,
                ToolchainManager.ToolId.PLATFORM_TOOLS,
            ),
            suggestedEnvVars = mapOf(
                "ANDROID_HOME" to "Path to your Android SDK",
                "JAVA_HOME"    to "Path to your JDK installation",
            ),
        ),
        Profile(
            id = ProfileId.FLUTTER,
            displayName = "Flutter Development",
            description = "Build cross-platform apps with Flutter and Dart",
            requiredTools = listOf(
                ToolchainManager.ToolId.FLUTTER,
                ToolchainManager.ToolId.DART,
            ),
            recommendedTools = listOf(
                ToolchainManager.ToolId.JDK,
                ToolchainManager.ToolId.ANDROID_SDK,
            ),
            suggestedEnvVars = mapOf(
                "FLUTTER_ROOT" to "Path to Flutter SDK",
            ),
        ),
        Profile(
            id = ProfileId.WEB,
            displayName = "Web Development",
            description = "HTML, CSS, JavaScript, and TypeScript projects",
            requiredTools = listOf(
                ToolchainManager.ToolId.NODEJS,
                ToolchainManager.ToolId.NPM,
            ),
            recommendedTools = emptyList(),
            suggestedEnvVars = mapOf(
                "NODE_ENV" to "Set to 'development' or 'production'",
            ),
        ),
        Profile(
            id = ProfileId.NODEJS,
            displayName = "Node.js Development",
            description = "Backend APIs and services with Node.js",
            requiredTools = listOf(
                ToolchainManager.ToolId.NODEJS,
                ToolchainManager.ToolId.NPM,
            ),
            recommendedTools = emptyList(),
            suggestedEnvVars = mapOf(
                "PORT" to "Port for your server to listen on",
                "NODE_ENV" to "Set to 'development' or 'production'",
            ),
        ),
        Profile(
            id = ProfileId.PYTHON,
            displayName = "Python Development",
            description = "Python 3 scripts, packages, and services",
            requiredTools = listOf(
                ToolchainManager.ToolId.PYTHON3,
            ),
            recommendedTools = listOf(
                ToolchainManager.ToolId.PIP,
            ),
            suggestedEnvVars = mapOf(
                "PYTHONPATH" to "Additional module search paths",
            ),
        ),
        Profile(
            id = ProfileId.GENERIC,
            displayName = "General Purpose",
            description = "No specific stack — all tools optional",
            requiredTools = emptyList(),
            recommendedTools = emptyList(),
            suggestedEnvVars = emptyMap(),
        ),
    )

    private val _activeProfile = MutableStateFlow(PROFILES.first { it.id == ProfileId.GENERIC })
    val activeProfile: StateFlow<Profile> = _activeProfile.asStateFlow()

    /** Load the persisted active profile. Call once on startup. */
    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE, null) ?: return@withContext
        val profile = PROFILES.firstOrNull { it.id.name == saved } ?: return@withContext
        _activeProfile.value = profile
    }

    /** Switch to a new profile and persist. */
    suspend fun setActive(context: Context, id: ProfileId) = withContext(Dispatchers.IO) {
        val profile = PROFILES.firstOrNull { it.id == id } ?: return@withContext
        _activeProfile.value = profile
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE, id.name).apply()
    }

    /**
     * Validate whether the current toolchain satisfies the active profile's requirements.
     * @param report latest toolchain report from ToolchainManager
     * @return list of required tools that are missing or broken
     */
    fun missingRequired(report: ToolchainManager.ToolchainReport): List<ToolchainManager.ToolStatus> {
        val active = _activeProfile.value
        return report.tools.filter { tool ->
            tool.id in active.requiredTools && tool.health != ToolchainManager.ToolHealth.OK
        }
    }

    fun profileFor(id: ProfileId): Profile = PROFILES.first { it.id == id }
}
