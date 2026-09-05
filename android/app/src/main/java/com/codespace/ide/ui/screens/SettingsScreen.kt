package com.codespace.ide.ui.screens

import com.codespace.ide.BuildConfig

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Intent
import android.net.Uri
import androidx.biometric.BiometricManager
import com.codespace.ide.ui.screens.PinRegistrationDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.codespace.ide.data.GitHubAuth
import com.codespace.ide.data.SecureTokenStore
import com.codespace.ide.data.SessionStateStore
import com.codespace.ide.domain.AiProviderId
import com.codespace.ide.terminal.BackupManager
import com.codespace.ide.terminal.ProotInstaller
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Restore
import androidx.compose.ui.unit.sp
import com.codespace.ide.editor.FeatureToggleStore
import com.codespace.ide.editor.FormatterConfig
import com.codespace.ide.util.WorkspaceManager
import androidx.compose.foundation.layout.Box

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onBack: () -> Unit,
    tokenStore: SecureTokenStore,
    sessionStateStore: SessionStateStore? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    // Rotation fix (#8): key on orientation so raw AlertDialog windows get a fresh,
    // correctly-sized window on rotate.
    val orientation = LocalConfiguration.current.orientation

    // ── GitHub sign-in (Device Flow) state ──────────────────────────────────
    var githubUsername by remember { mutableStateOf(tokenStore.githubUsername) }
    var githubDeviceCode by remember { mutableStateOf<GitHubAuth.DeviceCode?>(null) }
    var githubStatus by remember { mutableStateOf("") } // "", "waiting", "error:<msg>"
    var githubJob by remember { mutableStateOf<Job?>(null) }

    // ── AI provider key state ────────────────────────────────────────────────
    val keyMap = remember {
        mutableStateMapOf<AiProviderId, String>().apply {
            AiProviderId.entries.forEach { provider ->
                put(provider, tokenStore.aiKey(provider.name) ?: "")
            }
        }
    }
    val visibleMap = remember {
        mutableStateMapOf<AiProviderId, Boolean>().apply {
            AiProviderId.entries.forEach { put(it, false) }
        }
    }
    var activeProvider by remember {
        mutableStateOf(
            AiProviderId.entries.firstOrNull {
                tokenStore.aiKey(it.name) != null
            } ?: AiProviderId.CLAUDE
        )
    }
    var savedMsg by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf<String?>(null) }

    // ── App lock state ──────────────────────────────────────────────────────
    var biometricEnabled by remember { mutableStateOf(tokenStore.biometricLockEnabled) }
    var showPinRegistration by remember { mutableStateOf(false) }

    // Check if the device supports biometric (fingerprint/face) for optional unlock
    val biometricManager = remember { BiometricManager.from(context) }
    val biometricAvailable = remember {
        biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    // ── GitHub device-code dialog ────────────────────────────────────────────
    githubDeviceCode?.let { device ->
        key(orientation) {
        AlertDialog(
            onDismissRequest = { /* must Cancel explicitly — polling is still running */ },
            title = { Text("Connect GitHub") },
            text = {
                Column {
                    Text("1. Open this on any device:")
                    Text(device.verificationUri, style = MaterialTheme.typography.bodyMedium)
                    Text("2. Enter this code:", modifier = Modifier.padding(top = 12.dp))
                    Text(
                        device.userCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    Text("Waiting for you to approve…", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(device.userCode)) }) {
                        Text("Copy code")
                    }
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(device.verificationUri))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text("Open GitHub") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    githubJob?.cancel()
                    githubDeviceCode = null
                    githubStatus = ""
                }) { Text("Cancel") }
            },
        )
        }
    }

    // ── Clear-data dialog ────────────────────────────────────────────────────
    if (showClearDialog != null) {
        key(orientation) {
        AlertDialog(
            onDismissRequest = { showClearDialog = null },
            title = { Text("Clear ${showClearDialog}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        when (showClearDialog) {
                            "Terminal History" -> context.getSharedPreferences("terminal_history", Context.MODE_PRIVATE).edit().clear().apply()
                            "Workspace Memory" -> sessionStateStore?.clearAllWorkspaceMemory()
                            "AI Chat History"  -> context.getSharedPreferences("ai_chat_history", Context.MODE_PRIVATE).edit().clear().apply()
                            "Projects"         -> context.getSharedPreferences("projects", Context.MODE_PRIVATE).edit().clear().apply()
                            "All Data" -> {
                                context.getSharedPreferences("terminal_history", Context.MODE_PRIVATE).edit().clear().apply()
                                context.getSharedPreferences("ai_chat_history", Context.MODE_PRIVATE).edit().clear().apply()
                                context.getSharedPreferences("projects", Context.MODE_PRIVATE).edit().clear().apply()
                            }
                        }
                        showClearDialog = null
                        savedMsg = "✓ Cleared!"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = null }) { Text("Cancel") }
            }
        )
        }
    }

    // ── PIN registration dialog ─────────────────────────────────────────────
    if (showPinRegistration) {
        PinRegistrationDialog(
            onPinSet = { pin ->
                tokenStore.pinHash = tokenStore.hashPin(pin)
                tokenStore.biometricLockEnabled = true
                biometricEnabled = true
                showPinRegistration = false
                savedMsg = "✓ App lock enabled — PIN set"
            },
            onDismiss = {
                showPinRegistration = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Appearance ───────────────────────────────────────────────────
            Text("Appearance", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            ListItem(
                headlineContent = { Text("Dark mode") },
                trailingContent = {
                    Switch(checked = isDark, onCheckedChange = { onToggleTheme() })
                },
            )
            HorizontalDivider()

            // ── Security ─────────────────────────────────────────────────────
            Text("Security", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))

            ListItem(
                headlineContent = { Text("App lock") },
                supportingContent = {
                    Text(
                        when {
                            biometricEnabled && biometricAvailable -> "PIN + fingerprint required on every launch"
                            biometricEnabled -> "PIN required on every launch"
                            else -> "Off — anyone who opens the app gets straight in"
                        }
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = if (biometricEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = biometricEnabled,
                        onCheckedChange = { checked ->
                            if (checked) {
                                // Enabling — must register a PIN first
                                showPinRegistration = true
                            } else {
                                // Disabling — just turn it off
                                biometricEnabled = false
                                tokenStore.biometricLockEnabled = false
                                tokenStore.pinHash = null
                                savedMsg = "✓ App lock disabled"
                            }
                        }
                    )
                },
            )
            HorizontalDivider()

            // ── Accounts ────────────────────────────────────────────────────
            Text("Accounts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            if (githubUsername != null) {
                ListItem(
                    headlineContent = { Text("GitHub") },
                    supportingContent = { Text("✓ Connected as $githubUsername") },
                    trailingContent = {
                        TextButton(onClick = {
                            tokenStore.githubToken = null
                            tokenStore.githubUsername = null
                            githubUsername = null
                            savedMsg = "✓ Signed out of GitHub"
                        }) { Text("Sign out") }
                    },
                )
            } else {
                ListItem(
                    headlineContent = { Text("GitHub") },
                    supportingContent = {
                        Text(
                            when {
                                githubStatus == "waiting" -> "Waiting for you to approve on github.com…"
                                githubStatus.startsWith("error:") -> githubStatus.removePrefix("error:")
                                else -> "Not connected — needed for Source Control push/pull"
                            }
                        )
                    },
                    trailingContent = {
                        if (githubStatus == "waiting") {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                        } else {
                            Button(onClick = {
                                githubStatus = "waiting"
                                githubJob = scope.launch {
                                    try {
                                        val device = GitHubAuth.requestDeviceCode()
                                        githubDeviceCode = device
                                        val token = GitHubAuth.pollForToken(device)
                                        val username = GitHubAuth.fetchUsername(token)
                                        tokenStore.githubToken = token
                                        tokenStore.githubUsername = username
                                        githubUsername = username
                                        githubDeviceCode = null
                                        githubStatus = ""
                                        savedMsg = "✓ Connected to GitHub as $username"
                                    } catch (e: Exception) {
                                        githubDeviceCode = null
                                        githubStatus = "error:${e.message ?: "Sign-in failed"}"
                                    }
                                }
                            }) { Text("Sign in") }
                        }
                    },
                )
            }
            HorizontalDivider()

            // ── AI Providers ─────────────────────────────────────────────────
            Text("AI Providers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            AiProviderId.entries.forEach { provider ->
                val key     = keyMap[provider] ?: ""
                val visible = visibleMap[provider] ?: false
                val isActive = activeProvider == provider
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ListItem(
                        headlineContent = { Text(provider.displayName) },
                        supportingContent = { Text(if (isActive) "✓ Active" else "Tap switch to activate") },
                        trailingContent = {
                            Switch(checked = isActive, onCheckedChange = { if (it) activeProvider = provider })
                        },
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { keyMap[provider] = it },
                        label = {
                            Text("${provider.displayName} API Key")
                        },
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { visibleMap[provider] = !visible }) {
                                Icon(
                                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        singleLine = true,
                    )
                }
                HorizontalDivider()
            }

            Button(
                onClick = {
                    AiProviderId.entries.forEach { provider ->
                        val key = keyMap[provider] ?: ""
                        tokenStore.setAiKey(provider.name, key.ifBlank { null })
                    }
                    tokenStore.setAiKey("active", activeProvider.name)
                    savedMsg = "✓ Saved!"
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("Save API Keys") }

            if (savedMsg.isNotEmpty()) {
                Text(
                    savedMsg,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            HorizontalDivider()

            // ── Container Backup ─────────────────────────────────────────────
            Text("Container Backup", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            var backupInfo by remember { mutableStateOf(BackupManager.backupInfo()) }
            var backupStatus by remember { mutableStateOf("") }
            var backupRunning by remember { mutableStateOf(false) }
            var showRestoreConfirm by remember { mutableStateOf(false) }

            ListItem(
                headlineContent = { Text("Ubuntu container (Node, ffmpeg, projects, etc.)") },
                supportingContent = {
                    Text(
                        backupStatus.ifEmpty {
                            backupInfo ?: "No backup yet — every app reinstall wipes your container without one"
                        }
                    )
                },
            )
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Button(
                    enabled = !backupRunning,
                    onClick = {
                        backupRunning = true
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                BackupManager.backupPrefs(context)
                                BackupManager.createBackup(context) { msg -> backupStatus = msg }
                            }
                            backupInfo = BackupManager.backupInfo()
                            backupRunning = false
                        }
                    },
                ) { Text(if (backupRunning) "Backing up…" else "Back up now") }
                if (backupInfo != null) {
                    OutlinedButton(
                        enabled = !backupRunning,
                        onClick = { showRestoreConfirm = true },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Restore") }
                }
            }

            if (showRestoreConfirm) {
                key(orientation) {
                AlertDialog(
                    onDismissRequest = { showRestoreConfirm = false },
                    title = { Text("Restore container?") },
                    text = { Text("This replaces your current Ubuntu container with the last backup. Anything installed or changed since that backup will be lost.") },
                    confirmButton = {
                        TextButton(onClick = {
                            showRestoreConfirm = false
                            backupRunning = true
                            scope.launch {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    BackupManager.restorePrefs(context)
                                    BackupManager.restoreBackup(context) { msg -> backupStatus = msg }
                                }
                                backupRunning = false
                            }
                        }) { Text("Restore") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel") }
                    },
                )
                }
            }

            HorizontalDivider()

            // ── Reset Ubuntu Container ────────────────────────────────────────
            Text("Ubuntu Container", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))

            var showResetConfirm by remember { mutableStateOf(false) }
            var resetStatus by remember { mutableStateOf("") }
            var resetRunning by remember { mutableStateOf(false) }

            ListItem(
                headlineContent = { Text("Reinstall Ubuntu rootfs") },
                supportingContent = {
                    Text(
                        resetStatus.ifEmpty {
                            "Wipe and reinstall the Ubuntu container from scratch. " +
                            "Use this if Terminal won't start or LSP reports 'rootfs not installed'. " +
                            "⚠ All installed packages, files, and settings inside Ubuntu will be deleted."
                        }
                    )
                },
            )
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                OutlinedButton(
                    enabled = !resetRunning,
                    onClick = { showResetConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(if (resetRunning) "Reinstalling…" else "Reinstall Ubuntu") }
            }

            if (showResetConfirm) {
                key(orientation) {
                AlertDialog(
                    onDismissRequest = { showResetConfirm = false },
                    title = { Text("Reinstall Ubuntu?") },
                    text = {
                        Text(
                            "This will completely wipe your Ubuntu container and reinstall from scratch.\n\n" +
                            "Everything inside Ubuntu will be deleted:\n" +
                            "• All installed packages (Node.js, Python, etc.)\n" +
                            "• All files in /root and /home\n" +
                            "• All language server installations\n" +
                            "• Shell history and profile customisations\n\n" +
                            "Your project files (outside Ubuntu) are NOT affected.\n\n" +
                            "Back up your container first if you have important data inside it."
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showResetConfirm = false
                                resetRunning = true
                                scope.launch {
                                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        try {
                                            // Wipe rootfs dir and version marker
                                            val rootfsDir = ProotInstaller.rootfsDir(context)
                                            rootfsDir.deleteRecursively()
                                            val versionFile = java.io.File(context.filesDir, ".ubuntu_version")
                                            versionFile.delete()
                                            // Reinstall
                                            ProotInstaller.install(context) { msg ->
                                                resetStatus = msg
                                            }
                                        } catch (e: Exception) {
                                            resetStatus = "Error: ${e.message}"
                                        }
                                    }
                                    resetRunning = false
                                    if (!resetStatus.startsWith("Error")) {
                                        resetStatus = "Reinstall complete ✓ — open Terminal to finish setup"
                                    }
                                }
                            }
                        ) { Text("Reinstall", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
                    },
                )
                }
            }

            HorizontalDivider()

            // ── Workspace Memory ─────────────────────────────────────────────
            Text("Workspace Memory", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))

            var workspaceRestoreEnabled by remember {
                mutableStateOf(sessionStateStore?.workspaceRestoreEnabled ?: true)
            }
            ListItem(
                headlineContent = { Text("Restore previous session on project open") },
                supportingContent = { Text("Reopens your last files, cursor positions and layout") },
                trailingContent = {
                    Switch(
                        checked = workspaceRestoreEnabled,
                        onCheckedChange = { checked ->
                            workspaceRestoreEnabled = checked
                            sessionStateStore?.let { it.workspaceRestoreEnabled = checked }
                        }
                    )
                }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Clear Workspace Memory") },
                supportingContent = { Text("Forgets all saved file positions, layouts and sessions") },
                trailingContent = {
                    OutlinedButton(
                        onClick = { showClearDialog = "Workspace Memory" },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                    ) { Text("Clear") }
                }
            )
            HorizontalDivider()

            // ── P41-R: Formatter Selection ──────────────────────────────────
            // Feature Toggles moved to In-Project Settings (gear menu → In-Project Settings)
            Text("Formatter Selection", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            Text(
                "Choose your preferred code formatter for each language.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )

            // Show formatter pickers for languages with multiple options
            FormatterConfig.availableFormatters.entries
                .filter { it.value.size > 1 }
                .sortedBy { it.key.displayName }
                .forEach { (lang, options) ->
                    val currentFormatter = remember { FormatterConfig.getSelectedFormatter(context, lang) }
                    var expanded by remember { mutableStateOf(false) }
                    ListItem(
                        headlineContent = { Text(lang.displayName) },
                        supportingContent = { Text("Formatter: ${currentFormatter.name}") },
                        trailingContent = {
                            Box {
                                OutlinedButton(onClick = { expanded = true }) {
                                    Text(currentFormatter.name, fontSize = 12.sp)
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    options.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.name) },
                                            onClick = {
                                                FormatterConfig.setSelectedFormatter(context, lang, option.name)
                                                expanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    )
                    HorizontalDivider()
                }

            // Languages with built-in fallback only
            Text(
                "Built-in fallback (indentation + trailing whitespace) is used for languages without an external formatter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            HorizontalDivider()

            // ── Clear Data ───────────────────────────────────────────────────
            Text("Clear Data", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))

            listOf("Terminal History", "AI Chat History", "Projects").forEach { item ->
                ListItem(
                    headlineContent = { Text(item) },
                    trailingContent = {
                        OutlinedButton(
                            onClick = { showClearDialog = item },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                        ) { Text("Clear") }
                    }
                )
                HorizontalDivider()
            }

            // ── Deleted / Orphaned Projects ──────────────────────────────────────
            DeletedProjectsSection(context)

            OutlinedButton(
                onClick = { showClearDialog = "All Data" },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("Clear All Data") }

            // ── Version ─────────────────────────────────────────────────────────
            // P32: Show version code + git hash so crash reports can be verified
            // against the exact build. versionCode auto-increments from git rev-list count.
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Text(
                "VN Code",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp),
            )
            Text(
                "Build ${BuildConfig.VERSION_CODE} (${BuildConfig.GIT_HASH}) — v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF717171),
                modifier = Modifier.padding(start = 16.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun DeletedProjectsSection(context: android.content.Context) {
    var refreshKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // P29: Load trashed projects from WorkspaceManager project-level trash bin
    val trashedProjects by produceState<List<WorkspaceManager.TrashedProject>>(emptyList(), refreshKey) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            WorkspaceManager.listTrashedProjects(context)
        }
    }

    HorizontalDivider()
    Text(
        "Deleted Projects (Recycle Bin)",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )

    if (trashedProjects.isEmpty()) {
        ListItem(
            headlineContent = {
                Text("Recycle bin is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            leadingContent = {
                Icon(Icons.Default.CheckCircle, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
        )
    } else {
        Column(Modifier.fillMaxWidth()) {
            trashedProjects.forEach { entry ->
                val deletedDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(entry.deletedAtMs))
                var showRestoreConfirm by remember(entry.trashedDir.absolutePath) { mutableStateOf(false) }
                var showDeleteConfirm by remember(entry.trashedDir.absolutePath) { mutableStateOf(false) }

                ListItem(
                    headlineContent = { Text(entry.name) },
                    supportingContent = {
                        Text(
                            "Deleted $deletedDate \u2022 ${WorkspaceManager.formatSize(entry.sizeBytes)}",
                            fontSize = 11.sp
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.FolderOff, null,
                            tint = MaterialTheme.colorScheme.error)
                    },
                    trailingContent = {
                        Row {
                            // Restore button
                            OutlinedButton(
                                onClick = { showRestoreConfirm = true },
                                modifier = Modifier.padding(end = 4.dp),
                            ) {
                                Icon(Icons.Default.Restore, null, modifier = Modifier.padding(end = 4.dp))
                                Text("Restore")
                            }
                            // Delete Forever button
                            OutlinedButton(
                                onClick = { showDeleteConfirm = true },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                            ) {
                                Icon(Icons.Default.DeleteForever, null, modifier = Modifier.padding(end = 4.dp))
                                Text("Delete")
                            }
                        }
                    },
                )
                HorizontalDivider()

                // Restore confirmation dialog
                if (showRestoreConfirm) {
                    AlertDialog(
                        onDismissRequest = { showRestoreConfirm = false },
                        title = { Text("Restore \"${entry.name}\"?") },
                        text = {
                            Text("The project will be moved back to your projects list. " +
                                "If a project with the same name exists, it will be restored with _restored suffix.")
                        },
                        confirmButton = {
                            Button(onClick = {
                                scope.launch {
                                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        WorkspaceManager.restoreTrashedProject(context, entry)
                                    }
                                    refreshKey++
                                }
                                showRestoreConfirm = false
                            }) { Text("Restore") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel") }
                        },
                    )
                }

                // Delete Forever confirmation dialog
                if (showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text("Permanently delete \"${entry.name}\"?") },
                        text = {
                            Text("This cannot be undone. All files in this project will be " +
                                "permanently deleted and the project name will be freed for reuse.")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    scope.launch {
                                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            WorkspaceManager.purgeTrashedProject(context, entry)
                                        }
                                        refreshKey++
                                    }
                                    showDeleteConfirm = false
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                            ) { Text("Delete Forever") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                        },
                    )
                }
            }

            // Empty trash button at the bottom
            if (trashedProjects.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                WorkspaceManager.emptyProjectTrash(context)
                            }
                            refreshKey++
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) { Text("Empty Recycle Bin") }
            }
        }
    }
    HorizontalDivider()
}
