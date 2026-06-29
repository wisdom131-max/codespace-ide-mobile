package com.codespace.ide.ui

import android.app.Activity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
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
fun CodeSpaceApp(tokenStore: SecureTokenStore) {
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

    // ── Biometric gate ───────────────────────────────────────────────────────
    // Only shown once per process launch when the user has enabled the setting.
    var biometricUnlocked by remember {
        // If lock is disabled → treat as already unlocked
        mutableStateOf(!tokenStore.biometricLockEnabled)
    }

    CodeSpaceTheme(darkTheme = !themeName.contains("Light"), themeName = themeName) {

        if (!biometricUnlocked) {
            BiometricGate(
                onUnlocked  = { biometricUnlocked = true },
                onDismissed = { /* stays on lock screen */ },
            )
            return@CodeSpaceTheme
        }

        // ── Normal nav ───────────────────────────────────────────────────────
        val startDest = when {
            tokenStore.refreshToken == null                    -> Routes.AUTH
            sessionStateStore.lastProjectId() != null          -> Routes.project(sessionStateStore.lastProjectId()!!)
            else                                               -> Routes.HOME
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
                    onBack            = { nav.popBackStack() },
                    tokenStore        = tokenStore,
                    sessionStateStore  = sessionStateStore,
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

// ── Biometric gate composable ────────────────────────────────────────────────

@Composable
private fun BiometricGate(
    onUnlocked: () -> Unit,
    onDismissed: () -> Unit,
) {
    val context  = LocalContext.current
    val activity = context as? FragmentActivity

    fun showPrompt() {
        if (activity == null) return

        val executor = ContextCompat.getMainExecutor(context)
        val prompt   = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User cancelled or too many attempts — stay on gate
                    onDismissed()
                }
                override fun onAuthenticationFailed() {
                    // Wrong finger/face — BiometricPrompt handles retry automatically
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Visual Node Code")
            .setSubtitle("Verify it's you to continue")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(promptInfo)
    }

    // Auto-show prompt on composition
    LaunchedEffect(Unit) { showPrompt() }

    // Full-screen lock UI (shown while prompt is up or after dismissal)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Fingerprint,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Visual Node Code",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Authenticate to continue",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = { showPrompt() }) {
                Icon(Icons.Default.Fingerprint, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Try Again")
            }
        }
    }
}
