package com.codespace.ide.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.codespace.ide.data.SecureTokenStore
import com.codespace.ide.data.SessionStateStore
import com.codespace.ide.recovery.RecoveryManager
import com.codespace.ide.recovery.SafeMode
import com.codespace.ide.ui.screens.AuthScreen
import com.codespace.ide.ui.screens.HomeScreen
import com.codespace.ide.ui.screens.ProjectShellScreen
import com.codespace.ide.ui.screens.SettingsScreen
import kotlinx.coroutines.delay

object Routes {
    const val AUTH = "auth"
    const val HOME = "home"
    const val PROJECT = "project/{projectId}"
    const val SETTINGS = "settings"
    fun project(id: String) = "project/$id"
}

@Composable
fun CodeSpaceApp(tokenStore: SecureTokenStore) {
    // ALWAYS call remember() unconditionally at the top of a @Composable (Compose rules of hooks)
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", 0) }
    val sessionStateStore = remember { SessionStateStore(context) }
    var themeName by remember {
        mutableStateOf(prefs.getString("theme_name", if (systemDark) "Dark (Default)" else "Light (Default)") ?: "Light (Default)")
    }
    var safeModeActive by remember {
        mutableStateOf(RecoveryManager.isSafeModeRequired(context))
    }

    // Set the singleton SafeMode active flag
    remember(safeModeActive) {
        SafeMode.active = safeModeActive
        Unit
    }

    LaunchedEffect(Unit) {
        RecoveryManager.recordCrashStart(context)
        // 8-second delay before resetting crash counter
        delay(8000L)
        RecoveryManager.recordCleanStart(context)
    }

    fun saveTheme(name: String) {
        themeName = name
        prefs.edit().putString("theme_name", name).apply()
    }
    val startDest = when {
        tokenStore.refreshToken == null -> Routes.AUTH
        // In safe mode, navigate directly to HOME to skip auto-opening the last project
        safeModeActive -> Routes.HOME
        sessionStateStore.lastProjectId() != null -> Routes.project(sessionStateStore.lastProjectId()!!)
        else -> Routes.HOME
    }

    CodeSpaceTheme(
        darkTheme = !themeName.contains("Light"),
        themeName = themeName,
    ) {
        val nav = rememberNavController()
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier.fillMaxSize()
        ) {
            if (safeModeActive) {
                androidx.compose.foundation.layout.Row(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .androidx.compose.foundation.background(androidx.compose.ui.graphics.Color(0xFFD32F2F))
                        .padding(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Text(
                        text = "Safe mode — app crashed repeatedly. Terminal auto-start disabled.",
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = androidx.compose.ui.Modifier.weight(1f)
                    )
                    androidx.compose.material3.Button(
                        onClick = {
                            RecoveryManager.clearSafeMode(context)
                            safeModeActive = false
                            SafeMode.active = false
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = androidx.compose.ui.graphics.Color.White,
                            contentColor = androidx.compose.ui.graphics.Color(0xFFD32F2F)
                        )
                    ) {
                        androidx.compose.material3.Text("Exit Safe Mode")
                    }
                }
            }
            androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier.weight(1f)
            ) {
                NavHost(navController = nav, startDestination = startDest) {
                    composable(Routes.AUTH) {
                        AuthScreen(onAuthenticated = { token ->
                            tokenStore.refreshToken = token
                            nav.navigate(Routes.HOME) {
                                popUpTo(Routes.AUTH) { inclusive = true }
                            }
                        })
                    }
                    composable(Routes.HOME) {
                        HomeScreen(
                            onOpenProject = { id ->
                                sessionStateStore.saveProjectId(id)
                                nav.navigate(Routes.project(id))
                            },
                            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                        )
                    }
                    composable(Routes.PROJECT) { backStackEntry ->
                        val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
                        ProjectShellScreen(
                            projectId      = projectId,
                            isDark         = !themeName.contains("Light"),
                            currentTheme   = themeName,
                            onSelectTheme  = { saveTheme(it) },
                            onToggleTheme  = { saveTheme(if (themeName.contains("Light")) "Dark (Default)" else "Light (Default)") },
                            onBack         = { nav.popBackStack() },
                            tokenStore     = tokenStore,
                            sessionStateStore = sessionStateStore,
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            isDark        = !themeName.contains("Light"),
                            onToggleTheme = { themeName = if (themeName.contains("Light")) "Dark (Default)" else "Light (Default)" },
                            onBack        = { nav.popBackStack() },
                            tokenStore    = tokenStore,
                        )
                    }
                }
            }
        }
    }
}
