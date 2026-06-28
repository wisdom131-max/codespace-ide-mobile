package com.codespace.ide.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
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
import com.codespace.ide.ui.screens.AuthScreen
import com.codespace.ide.ui.screens.HomeScreen
import com.codespace.ide.ui.screens.ProjectShellScreen
import com.codespace.ide.ui.screens.SettingsScreen

object Routes {
    const val AUTH = "auth"
    const val HOME = "home"
    const val PROJECT = "project/{projectId}"
    const val SETTINGS = "settings"
    fun project(id: String) = "project/$id"
}

@Composable
fun CodeSpaceApp(tokenStore: SecureTokenStore) {
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", 0) }
    val sessionStateStore = remember { SessionStateStore(context) }
    var themeName by remember {
        mutableStateOf(prefs.getString("theme_name", if (systemDark) "Dark (Default)" else "Light (Default)") ?: "Light (Default)")
    }
    fun saveTheme(name: String) {
        themeName = name
        prefs.edit().putString("theme_name", name).apply()
    }
    val startDest = when {
        tokenStore.refreshToken == null -> Routes.AUTH
        sessionStateStore.lastProjectId() != null -> Routes.project(sessionStateStore.lastProjectId()!!)
        else -> Routes.HOME
    }

    CodeSpaceTheme(
        darkTheme = !themeName.contains("Light"),
        themeName = themeName,
    ) {
        val nav = rememberNavController()
        NavHost(navController = nav, startDestination = startDest) {
            composable(Routes.AUTH) {
                AuthScreen(onAuthenticated = { result ->
                    tokenStore.refreshToken = result.refreshToken
                    tokenStore.userRole     = result.role
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
