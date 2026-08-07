package com.codespace.ide.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    const val AUTH     = "auth"
    const val HOME     = "home"
    const val PROJECT  = "project/{projectId}"
    const val SETTINGS = "settings"
    fun project(id: String) = "project/$id"
}

@Composable
fun CodeSpaceApp(tokenStore: SecureTokenStore, safeMode: Boolean = false) {
    val systemDark = isSystemInDarkTheme()
    val context    = LocalContext.current
    val prefs      = remember { context.getSharedPreferences("app_prefs", 0) }
    val sessionStateStore = remember { SessionStateStore(context) }

    var themeName by remember {
        mutableStateOf(
            prefs.getString("theme_name", if (systemDark) "Dark (Default)" else "Light (Default)")
                ?: "Light (Default)"
        )
    }
    fun saveTheme(name: String) {
        themeName = name
        prefs.edit().putString("theme_name", name).apply()
    }

    // ── App lock gate ───────────────────────────────────────────────────────
    // Only shown once per process launch when the user has enabled the lock.
    var appUnlocked by remember {
        val lockEnabled = tokenStore.biometricLockEnabled
        mutableStateOf(!lockEnabled || !tokenStore.hasPinRegistered)
    }

    CodeSpaceTheme(darkTheme = !themeName.contains("Light"), themeName = themeName) {

        if (!appUnlocked) {
            com.codespace.ide.ui.screens.PinLockScreen(
                tokenStore = tokenStore,
                onUnlocked = { appUnlocked = true },
            )
            return@CodeSpaceTheme
        }

        // ── Normal nav ───────────────────────────────────────────────────────
        // IMPORTANT: startDest must be computed only ONCE (remember with no keys).
        // If it is a plain val, every theme/state change recomposes CodeSpaceApp and
        // re-evaluates startDest, giving NavHost a new startDestination each time.
        // NavHost treats a changed startDestination as a brand-new graph and destroys
        // all remember() state (open editor tabs, scroll positions, terminal state, etc).
        val startDest = remember {
            when {
                tokenStore.refreshToken == null               -> Routes.AUTH
                sessionStateStore.lastProjectId() != null     -> Routes.project(sessionStateStore.lastProjectId()!!)
                else                                          -> Routes.HOME
            }
        }

        val nav = rememberNavController()
        // accessToken kept in memory only (not persisted — re-auth on cold start)
        var accessToken by remember { mutableStateOf(tokenStore.lastAccessToken ?: "") }
        NavHost(navController = nav, startDestination = startDest) {
            composable(Routes.AUTH) {
                AuthScreen(onAuthenticated = { result ->
                    tokenStore.refreshToken  = result.refreshToken
                    tokenStore.userRole      = result.role
                    tokenStore.lastAccessToken = result.accessToken
                    accessToken = result.accessToken
                    nav.navigate(Routes.HOME) { popUpTo(Routes.AUTH) { inclusive = true } }
                })
            }
            composable(Routes.HOME) {
                HomeScreen(
                    accessToken   = accessToken,
                    onOpenProject = { id ->
                        sessionStateStore.saveProjectId(id)
                        nav.navigate(Routes.project(id))
                    },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onSignOut = {
                        tokenStore.clear()
                        accessToken = ""
                        nav.navigate(Routes.AUTH) { popUpTo(0) { inclusive = true } }
                    },
                )
            }
            composable(Routes.PROJECT) { backStackEntry ->
                val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
                ProjectShellScreen(
                    projectId         = projectId,
                    isDark            = !themeName.contains("Light"),
                    currentTheme      = themeName,
                    onSelectTheme     = { saveTheme(it) },
                    onToggleTheme     = { saveTheme(if (themeName.contains("Light")) "Dark (Default)" else "Light (Default)") },
                    onBack            = {
                        if (!nav.popBackStack()) {
                            nav.navigate(Routes.HOME) {
                                popUpTo(nav.graph.startDestinationId) { inclusive = true }
                            }
                        }
                    },
                    onSignOut         = {
                        tokenStore.clear()
                        accessToken = ""
                        nav.navigate(Routes.AUTH) { popUpTo(0) { inclusive = true } }
                    },
                    onOpenSettings    = { nav.navigate(Routes.SETTINGS) },
                    tokenStore        = tokenStore,
                    sessionStateStore  = sessionStateStore,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    isDark             = !themeName.contains("Light"),
                    onToggleTheme      = { themeName = if (themeName.contains("Light")) "Dark (Default)" else "Light (Default)" },
                    onBack             = { nav.popBackStack() },
                    tokenStore         = tokenStore,
                    sessionStateStore  = sessionStateStore,
                )
            }
        }
    }
}

