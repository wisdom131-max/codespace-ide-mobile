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
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
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
import com.codespace.ide.chat.ChatProvider
import com.codespace.ide.chat.ChatProviderRegistry
import com.codespace.ide.terminal.BackupManager
import com.codespace.ide.terminal.TerminalService
import com.codespace.ide.terminal.BackupTar
import com.codespace.ide.terminal.ProotInstaller
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.unit.sp
import com.codespace.ide.editor.FeatureToggleStore
import com.codespace.ide.editor.FormatterConfig
import com.codespace.ide.editor.KeyBindingRegistry
import com.codespace.ide.editor.settings.JsonSettingsStore
import com.codespace.ide.data.NotificationStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

    // ── AI provider keys: fully extracted to AiKeysSection.kt (credential UX
    // redesign phases 1-3) — masked key status, per-provider auto-save, malformed-
    // token rejection, paste-to-route, live model check, no global Save button.
    var savedMsg by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf<String?>(null) }
    // SK10 (2026-09-26): unified settings search — one box that finds every setting
    // across all three surfaces (this screen, In-Project Settings, the shell theme
    // picker). See SETTINGS_SEARCH_INDEX at the bottom of this file.
    var searchQuery by remember { mutableStateOf("") }
    // SK12 (2026-09-26): settings restore — importJson (schema-validating, typed)
    // was reachable from NO UI since the day it was written; this picker wires it.
    val settingsImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                if (text != null && JsonSettingsStore.importJson(text)) {
                    savedMsg = "✓ Settings restored from backup"
                } else {
                    savedMsg = "✗ Could not restore — invalid settings backup"
                }
            } catch (e: Exception) {
                savedMsg = "✗ Could not restore: ${e.message}"
            }
        }
    }

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
            text = {
                // SK05 (2026-09-26): the All Data button used to overclaim — it cleared
                // only 3 prefs. The dialog now states exactly what it clears and what
                // it deliberately does not touch.
                if (showClearDialog == "All Data") {
                    Text("Clears: terminal & chat history, project list, app settings, keybindings, notifications, workspace memory. NOT cleared: files on disk (projects, trash, version history), the Ubuntu container, and container backups.")
                } else {
                    Text("This cannot be undone.")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // SK06 (2026-09-26): .apply() fire-and-forget DELETED on this
                        // screen's clear paths — commit() returns a typed Boolean that is
                        // read back and reported honestly instead of an unconditional "✓".
                        var clearOk = true
                        when (showClearDialog) {
                            "Terminal History" -> clearOk = context.getSharedPreferences("terminal_history", Context.MODE_PRIVATE).edit().clear().commit()
                            "Workspace Memory" -> sessionStateStore?.clearAllWorkspaceMemory()
                            "AI Chat History"  -> clearOk = context.getSharedPreferences("ai_chat_history", Context.MODE_PRIVATE).edit().clear().commit()
                            "Projects"         -> clearOk = context.getSharedPreferences("projects", Context.MODE_PRIVATE).edit().clear().commit()
                            "All Data" -> {
                                // SK05 (2026-09-26): the button used to clear only 3 prefs
                                // while claiming "All Data" — settings.json, keybindings,
                                // notifications and workspace memory all silently survived it.
                                // It now clears every in-app store that holds user data.
                                val results = mutableListOf<Boolean>()
                                results += context.getSharedPreferences("terminal_history", Context.MODE_PRIVATE).edit().clear().commit()
                                results += context.getSharedPreferences("ai_chat_history", Context.MODE_PRIVATE).edit().clear().commit()
                                results += context.getSharedPreferences("projects", Context.MODE_PRIVATE).edit().clear().commit()
                                KeyBindingRegistry.resetAllBindings()      // legacy prefs + JSON overrides
                                JsonSettingsStore.resetToDefaults()       // settings.json
                                NotificationStore.permanentlyDeleteAll()  // notifications, undo-proof
                                sessionStateStore?.clearAllWorkspaceMemory()
                                clearOk = results.all { it }
                            }
                        }
                        showClearDialog = null
                        savedMsg = if (clearOk) "✓ Cleared!" else "✗ Clear failed — restart the app and try again"
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
                tokenStore.setPin(pin) // SK04 (P4f): PBKDF2 + per-install salt
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
            // SK10 (2026-09-26): unified settings search — filters the whole screen and
            // indexes settings that live on the OTHER two surfaces too (gear menu →
            // In-Project Settings, and the shell theme picker). VS Code finds everything
            // from one box; this is that box for this app.
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search settings…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            if (searchQuery.isBlank()) {
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
                                tokenStore.clearPin()
                                // SK06: read back the persisted store instead of an unconditional "✓".
                                savedMsg = if (!tokenStore.biometricLockEnabled) "✓ App lock disabled" else "✗ Could not disable app lock — try again"
                            }
                        }
                    )
                },
            )
            // C12 s1-d (owner ruling 2026-10-08): saved-chat secret scrub ships
            // OFF — the owner enables it AFTER the foundation checkpoints pass;
            // the enable-and-verify is its own round checkpoint. Enabling writes
            // the flag; the actual one-time redaction pass runs at the NEXT app
            // start (scrubSessionsOnce reads this flag), with the one-time
            // "N saved items had secrets removed" notice.
            val scrubPrefs = remember { context.getSharedPreferences("copilot_chat", Context.MODE_PRIVATE) }
            var scrubEnabled by remember { mutableStateOf(scrubPrefs.getBoolean("c12_scrub_enabled", false)) }
            ListItem(
                headlineContent = { Text("Saved-chat secret scrub") },
                supportingContent = {
                    Text(
                        if (scrubEnabled) "On — saved chat copies get secret-looking tokens removed at the next app start"
                        else "Off — saved chat copies are kept as-is"
                    )
                },
                trailingContent = {
                    Switch(
                        checked = scrubEnabled,
                        onCheckedChange = { checked ->
                            scrubEnabled = checked
                            scrubPrefs.edit().putBoolean("c12_scrub_enabled", checked).apply()
                        },
                    )
                },
            )
            // P4 (BATCH3): OPT-IN 402 key failover — OFF by default. Credits are
            // per ACCOUNT (e.g. Hugging Face monthly credits), so switching keys
            // only helps when the pool holds keys from DIFFERENT accounts; the
            // toggle says that so nobody expects a same-account second key to work.
            // One pass through the pool, no loops; never shows key values.
            var failover402Enabled by remember { mutableStateOf(com.codespace.ide.chat.Chat402FailoverSetting.isEnabled()) }
            ListItem(
                headlineContent = { Text("Switch to next key on 402 (out of credits)") },
                supportingContent = {
                    Text(
                        if (failover402Enabled) "On \u2014 when a provider answers 'out of credits' (402), the next key in the pool is tried once, and the chat shows 'Switched to key N'. Credits are per ACCOUNT, so this only helps with keys from different accounts."
                        else "Off \u2014 a 402 (provider account out of credits) stops the request as-is. Turn on to try the next key once; only helps with keys from different accounts."
                    )
                },
                trailingContent = {
                    Switch(
                        checked = failover402Enabled,
                        onCheckedChange = { checked ->
                            failover402Enabled = checked
                            com.codespace.ide.chat.Chat402FailoverSetting.setEnabled(context, checked)
                        },
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
                            // SK06: read back the persisted store instead of an unconditional "✓".
                            savedMsg = if (tokenStore.githubToken == null) "✓ Signed out of GitHub" else "✗ Sign-out did not persist — try again"
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

            // ── AI Providers (credential UX redesign phases 1-3) ────────────────
            AiKeysSection(tokenStore = tokenStore)

            HorizontalDivider()

            // ── Language Servers (P1e, read-only status) ─────────────────────
            LspServersStatusSection()

            HorizontalDivider()

            // ── Container Backup ─────────────────────────────────────────────
            Text("Container Backup", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            var backupInfo by remember { mutableStateOf(BackupManager.backupInfo()) }
            var backupStatus by remember { mutableStateOf("") }
            // Advisor item 3 (2026-10-11): armed when createBackup reports
            // SHRINK_CONFIRMATION_NEEDED — the NEXT "Back up now" tap confirms.
            var confirmShrinkBackup by remember { mutableStateOf(false) }
            var backupRunning by remember { mutableStateOf(false) }
            var showRestoreConfirm by remember { mutableStateOf(false) }
            // Advisor item 3 (2026-10-11): .prev is a SEPARATE, clearly-labelled
            // restore choice — never a silent fallback.
            var showPrevRestoreConfirm by remember { mutableStateOf(false) }
            var prevBackupInfo by remember { mutableStateOf(BackupManager.previousBackupInfo()) }
            // Bug 3 (owner round 2026-10-14): "Restore automatically" — default ON;
            // drives the 5-second notice on the Ubuntu tab.
            var autoRestore by remember { mutableStateOf(BackupManager.autoRestoreEnabled(context)) }
            // Bug 4 (owner round 2026-10-14): a SAF-picked backup — Checked and
            // restored FROM THE STREAM (no 200+ MB copy). Any file, any name:
            // validated by content (gzip), never by MIME or extension.
            var pickedUri by remember { mutableStateOf<android.net.Uri?>(null) }
            var pickedReport by remember { mutableStateOf("") }
            var showPickedRestoreConfirm by remember { mutableStateOf(false) }
            // Advisor follow-up (2026-10-15): a picked file from a NON-LOCAL
            // provider (Drive, Dropbox...) is read TWICE over the owner's
            // connection — he gets a Continue/Cancel warning BEFORE anything is read.
            var showCloudWarn by remember { mutableStateOf(false) }
            var cloudWarnUri by remember { mutableStateOf<android.net.Uri?>(null) }
            // Advisor 4 (2026-10-15, approved): restore-over-healthy — a picked-file
            // restore over a WORKING container asks to back the current one up
            // first (timestamped file) and refuses while shells run.
            var showRestoreOverHealthy by remember { mutableStateOf(false) }
            var rohShells by remember { mutableStateOf(0) }
            var pickedName by remember { mutableStateOf("picked file") }
            var pickedBytes by remember { mutableStateOf(-1L) }
            // Advisor 4d: after a restore-over-healthy with "Back up current first",
            // the pre-restore container stays available as Restore previous container.
            var preRestoreBackup by remember { mutableStateOf<File?>(null) }
            var showPreRestoreConfirm by remember { mutableStateOf(false) }
            // The device's OWN storage/Downloads/Media providers read locally —
            // everything else (Drive, Dropbox, OneDrive...) streams over the
            // network and gets the warning dialog first.
            val isLocalProvider: (android.net.Uri) -> Boolean = { uri ->
                // Advisor follow-up (2026-10-15, cloud detection): file:// Uris and
                // ANY Android FRAMEWORK provider (com.android.*) read locally — the
                // device's own storage, Downloads and Media providers, which is what
                // Google Files and most system pickers hand over. A vendor file
                // manager (e.g. Transsion's on the owner's TECNO) may use its own
                // authority: unknown authorities get the warning (Continue exists),
                // and the authority is LOGGED below so the device round tells us the
                // exact string to whitelist.
                uri.scheme == "file" || run {
                    val a = uri.authority ?: ""
                    a == "com.android.externalstorage.documents" ||
                        a == "com.android.providers.downloads.documents" ||
                        a == "media" ||
                        a == "com.android.providers.media.module" ||
                        a.startsWith("com.android.")
                }
            }
            val runPickedCheck: (android.net.Uri) -> Unit = { uri: android.net.Uri ->
                run {
                    // Advisor follow-up (2026-10-15): always log WHO provided the
                    // picked file — the owner's TECNO Files app authority is unknown
                    // until a device round reports this line.
                    val pickedAuthority = runCatching { uri.authority ?: "(no authority)" }.getOrDefault("(unreadable)")
                    com.codespace.ide.diagnostics.AppOutputLog.log(
                        "[CHECK-BACKUP] Picked from provider: $pickedAuthority — " +
                            if (isLocalProvider(uri)) "LOCAL (device storage, no data charge)" else "NON-LOCAL (cloud/network, confirmed with Continue)",
                        "backup",
                    )
                    pickedUri = uri
                    backupRunning = true
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            // CHECK BACKUP ON THE PICKED FILE (owner round 2026-10-14,
                            // previously owed): stream-inspect entries, counts and the
                            // gate binaries — then the confirm offers the restore.
                            try {
                                val nm = BackupManager.queryPickedName(context, uri) ?: "picked file"
                                val bytes = BackupManager.queryPickedSize(context, uri)
                                pickedName = nm
                                pickedBytes = bytes
                                val lines = ArrayList<String>()
                                lines.add("=== Check picked backup: $nm ===")
                                lines.add("  Size: ${if (bytes >= 0) "$bytes bytes (${bytes / (1024 * 1024)} MB)" else "not reported"}")
                                val report = context.contentResolver.openInputStream(uri)?.use { s ->
                                    BackupTar.inspectStream(s)
                                }
                                if (report == null) {
                                    lines.add("  ERROR: could not open the picked file.")
                                } else {
                                    lines.add("  Entries: ${report.totalEntries} — ${report.dirs} directories, ${report.files} files, ${report.symlinks.size} symlinks")
                                    lines.add("  Critical: bash ${if (report.hasBash) "ok" else "MISSING"}, env ${if (report.hasEnv) "ok" else "MISSING"}")
                                    lines.add("  Top-level: ${report.topLevel.joinToString().take(120)}")
                                    if (report.error != null) lines.add("  ERROR: ${report.error}")
                                }
                                pickedReport = lines.joinToString("\n")
                            } catch (e: Exception) {
                                val mime = try { context.contentResolver.getType(uri) ?: "unknown" } catch (_: Exception) { "unknown" }
                                val bytes = BackupManager.queryPickedSize(context, uri)
                                pickedReport = "Not a readable backup archive: ${e.message}. " +
                                    "Detected MIME type \"$mime\", ${if (bytes >= 0) "$bytes bytes" else "unknown size"}. " +
                                    "Expected container-backup.tar.gz (a .tar.gz archive)."
                            }
                            com.codespace.ide.diagnostics.AppOutputLog.log(pickedReport, "terminal")
                            backupStatus = pickedReport.lineSequence().firstOrNull() ?: "Check finished — see the Output panel"
                        }
                        backupRunning = false
                        showPickedRestoreConfirm = true
                    }
                }
            }
            // Advisor 4 (2026-10-15): the ONE picked-file restore runner — used by the
            // direct path (no healthy container) and both restore-over-healthy
            // choices. backUpFirst: create a TIMESTAMPED safety copy of the
            // current container right before it is replaced (advisor 4a: never
            // touches container-backup.tar.gz, its .prev, or the restore source).
            val runPickedRestore: (Boolean) -> Unit = { backUpFirst: Boolean ->
                run {
                    val uri = pickedUri
                    if (uri != null) {
                        backupRunning = true
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                var safeFile: File? = null
                                if (backUpFirst) {
                                    val safe = BackupManager.createTimestampedBackup(
                                        context,
                                        { msg -> backupStatus = msg },
                                        neverOverwrite = listOf(BackupManager.backupFile(), BackupManager.previousBackupFile()),
                                    )
                                    if (safe.outcome == BackupManager.TimestampedOutcome.SAVED && safe.file != null) {
                                        safeFile = safe.file
                                    } else {
                                        pickedUri = null
                                        backupStatus = "Pre-restore backup did not complete — the restore was NOT started. Your container and backups were NOT touched."
                                        backupRunning = false
                                        return@withContext
                                    }
                                }
                                pickedUri = null
                                val rres = BackupManager.restoreBackup(context, { msg -> backupStatus = msg }, sourceUri = uri, explicit = true)
                                preRestoreBackup = if (rres.ok) safeFile else null
                                if (!rres.ok) {
                                    backupStatus = "Picked-file restore failed: " + rres.message.take(200)
                                } else {
                                    backupStatus = if (safeFile != null)
                                        "Restore complete. The pre-restore container is kept as " + safeFile.name + " (Restore previous container below)."
                                    else
                                        "Restore complete."
                                }
                            }
                            backupInfo = BackupManager.backupInfo()
                            backupRunning = false
                        }
                    }
                }
            }
            val pickBackupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    if (isLocalProvider(uri)) {
                        runPickedCheck(uri)
                    } else {
                        cloudWarnUri = uri
                        showCloudWarn = true
                    }
                }
            }

            // RG05 (2026-09-26): prompt surface — armed by BackupManager.onAppStart on
            // version change, fresh-install restore, or a missing/stale (>7d) container
            // backup. Container backup used to be manual-only: one forgotten button and
            // a CI-rebuild uninstall wiped the whole Ubuntu container.
            var backupPrompt by remember { mutableStateOf(BackupManager.containerPromptPending(context)) }
            if (backupPrompt) {
                ContainerBackupPromptBanner(
                    onBackupNow = {
                        BackupManager.setContainerPrompt(context, false)
                        backupPrompt = false
                        backupRunning = true
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val prefsBackup = BackupManager.backupPrefs(context)
                                if (!prefsBackup.ok) {
                                    backupStatus = "Prefs backup failed for ${prefsBackup.failedNames.size} file(s) — see logcat (BackupManager)"
                                }
                                val outcome = BackupManager.createBackup(
                                    context,
                                    { msg -> backupStatus = msg },
                                    forceOverwriteSmaller = confirmShrinkBackup,
                                )
                                confirmShrinkBackup = outcome == BackupManager.CreateBackupOutcome.SHRINK_CONFIRMATION_NEEDED
                            }
                            backupInfo = BackupManager.backupInfo()
                            backupRunning = false
                        }
                    },
                    onDismiss = {
                        BackupManager.setContainerPrompt(context, false)
                        backupPrompt = false
                    },
                )
            }

            // ── Advisor Part 4 (2026-10-16): staged Link tap mode (diagnostics) ──
            var linkTapMode by remember { mutableStateOf(com.codespace.ide.terminal.LinkTapModeStore.current(context)) }
            var showLinkTapModeDialog by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text("Link tap mode (terminal diagnostics)") },
                supportingContent = {
                    Text(
                        com.codespace.ide.terminal.LinkTapModeStore.label(linkTapMode) +
                            ". Stages are logged to the crumb file line by line — if a tap ever closes the app, the last line shows the step it died on.",
                    )
                },
                trailingContent = {
                    TextButton(onClick = { showLinkTapModeDialog = true }) { Text("Change") }
                },
            )
            if (showLinkTapModeDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showLinkTapModeDialog = false },
                    title = { Text("Link tap mode") },
                    text = {
                        androidx.compose.foundation.layout.Column {
                            com.codespace.ide.terminal.LinkTapModeStore.Mode.values().forEach { m ->
                                TextButton(onClick = {
                                    linkTapMode = m
                                    com.codespace.ide.terminal.LinkTapModeStore.set(context, m)
                                    showLinkTapModeDialog = false
                                }) {
                                    Text(
                                        (if (m == linkTapMode) "✓ " else "   ") + com.codespace.ide.terminal.LinkTapModeStore.label(m),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showLinkTapModeDialog = false }) { Text("Close") }
                    },
                )
            }
            // BATCH6-P3 (advisor D5): manual repair — the auto-heal is capped at
            // 3/day, so a corrupted install whose budget is spent still has an
            // owner-initiated path (this clears the budget and re-runs the
            // staged, verified install; progress lands in Output).
            ListItem(
                headlineContent = { Text("Repair language-server install (jdtls)") },
                supportingContent = {
                    Text(
                        "Re-downloads and verifies the Java language server: integrity-checked, " +
                            "atomic swap, working install kept on failure. Can take 5-15 minutes on a " +
                            "slow connection — progress lines appear in Output.",
                    )
                },
                trailingContent = {
                    TextButton(onClick = {
                        com.codespace.ide.lsp.VerifiedInstall.allowManualRepair(context, com.codespace.ide.domain.Language.JAVA.name)
                        android.widget.Toast.makeText(context, "Repair started — see Output", android.widget.Toast.LENGTH_LONG).show()
                        Thread {
                            // BATCH9-P1a: the repair STOPS THE RUNNING JAVA SERVER FIRST
                            // (advisor P1a: jdtls repair stops the live server before the
                            // /opt swap). A synchronous stop would block this daemon thread
                            // for the whole 5s shutdown chain, so the repair thread AWAITS
                            // the async stop with a bounded 45s cap and reports honestly if
                            // the stop did not finish (install may then swap under a live
                            // server — visible in Output, not silent).
                            try {
                                com.codespace.ide.lsp.LspManager.stopServerAsync(
                                    com.codespace.ide.domain.Language.JAVA, "jdtls-repair",
                                ).get(45, java.util.concurrent.TimeUnit.SECONDS)
                                com.codespace.ide.diagnostics.AppOutputLog.log("[LSP] Repair: Java server stopped — swapping the jdtls install", "lsp")
                            } catch (_: Exception) {
                                com.codespace.ide.diagnostics.AppOutputLog.log("[LSP] Repair: Java server stop timed out — proceeding; the install may swap under a live server", "lsp")
                            }
                            com.codespace.ide.lsp.LspManager.installServer(context, com.codespace.ide.domain.Language.JAVA)
                        }.apply { isDaemon = true; name = "ManualJdtlsRepair"; start() }
                    }) { Text("Repair") }
                },
            )
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
                                val prefsBackup = BackupManager.backupPrefs(context)
                                if (!prefsBackup.ok) {
                                    backupStatus = "Prefs backup failed for ${prefsBackup.failedNames.size} file(s) — see logcat (BackupManager)"
                                }
                                val outcome = BackupManager.createBackup(
                                    context,
                                    { msg -> backupStatus = msg },
                                    forceOverwriteSmaller = confirmShrinkBackup,
                                )
                                confirmShrinkBackup = outcome == BackupManager.CreateBackupOutcome.SHRINK_CONFIRMATION_NEEDED
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
            if (prevBackupInfo != null) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    OutlinedButton(
                        enabled = !backupRunning,
                        onClick = { showPrevRestoreConfirm = true },
                    ) { Text("Restore previous backup") }
                }
            }
            // ── Advisor 2b retention (approved 2026-10-16) ──────────────────────
            var pruningEnabled by remember { mutableStateOf(BackupManager.pruningEnabled(context)) }
            ListItem(
                headlineContent = { Text("Keep old safety backups") },
                supportingContent = {
                    Text(
                        if (!pruningEnabled)
                            "ON (keep all): every safety backup is kept until you remove it — watch free space."
                        else
                            "OFF: only the newest 3 safety backups are kept; older ones are removed with a notice first. The main backup and the previous one are never removed."
                    )
                },
                trailingContent = {
                    androidx.compose.material3.Switch(
                        checked = !pruningEnabled,
                        onCheckedChange = { keepAll ->
                            pruningEnabled = !keepAll
                            BackupManager.setPruningEnabled(context, !keepAll)
                        },
                    )
                },
            )
            // Advisor 2b condition: the backup list shows SIZES and DATES.
            val backupListItems = remember(backupInfo, backupRunning) {
                BackupManager.allBackupFiles().map { f ->
                    val mb = f.length() / (1024 * 1024)
                    val date = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.US).format(java.util.Date(f.lastModified()))
                    f to (mb.toString() + " MB · " + date)
                }
            }
            if (backupListItems.isNotEmpty()) {
                ListItem(
                    headlineContent = { Text("Stored backups (" + backupListItems.size + ")") },
                    supportingContent = {
                        androidx.compose.foundation.layout.Column {
                            backupListItems.forEach { (f, info) ->
                                Text(f.name + " — " + info, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                )
            }
            // Advisor 4d (2026-10-15): the pre-restore container stays available.
            val preRestoreFile = preRestoreBackup
            if (preRestoreFile != null && preRestoreFile.exists()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    OutlinedButton(
                        enabled = !backupRunning,
                        onClick = {
                            rohShells = TerminalService.liveSessionCount()
                            showPreRestoreConfirm = true
                        },
                    ) { Text("Restore previous container") }
                    Text(
                        "\u2014 " + preRestoreFile.name + ", " +
                            (preRestoreFile.length() / (1024 * 1024)) + " MB",
                        modifier = Modifier.padding(start = 10.dp, top = 14.dp),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (showRestoreOverHealthy) {
                RestoreOverHealthyDialog(
                    shellsRunning = rohShells,
                    pickedDesc = if (pickedBytes >= 0) pickedName + " (" + (pickedBytes / (1024 * 1024)) + " MB)" else pickedName,
                    currentBackupInfo = backupInfo,
                    onShellsClosed = {
                        val closed = TerminalService.finishAllLiveSessions()
                        com.codespace.ide.diagnostics.AppOutputLog.log(
                            "[RESTORE] closed $closed terminal session(s) for a restore-over-healthy", "backup",
                        )
                        rohShells = 0
                    },
                    onBackUpFirst = { showRestoreOverHealthy = false; runPickedRestore(true) },
                    onRestoreWithoutBackup = { showRestoreOverHealthy = false; runPickedRestore(false) },
                    onDismiss = { showRestoreOverHealthy = false; pickedUri = null },
                )
            }
            if (showPreRestoreConfirm) {
                PreRestoreConfirmDialog(
                    shellsRunning = rohShells,
                    desc = (if (preRestoreFile != null) preRestoreFile.name + " (" + ((preRestoreFile.length()) / (1024 * 1024)) + " MB)" else "the pre-restore container"),
                    onShellsClosed = {
                        val closed = TerminalService.finishAllLiveSessions()
                        com.codespace.ide.diagnostics.AppOutputLog.log(
                            "[RESTORE] closed $closed terminal session(s) for a previous-container restore", "backup",
                        )
                        rohShells = 0
                    },
                    onRestore = {
                        showPreRestoreConfirm = false
                        val f = preRestoreBackup
                        if (f != null && f.exists()) {
                            backupRunning = true
                            scope.launch {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val rres = BackupManager.restoreBackup(context, { msg -> backupStatus = msg }, sourceFile = f, explicit = true)
                                    if (!rres.ok) {
                                        backupStatus = "Previous-container restore failed: " + rres.message.take(200)
                                    } else {
                                        backupStatus = "Previous container restored from " + f.name + "."
                                    }
                                }
                                backupInfo = BackupManager.backupInfo()
                                backupRunning = false
                            }
                        }
                    },
                    onDismiss = { showPreRestoreConfirm = false },
                )
            }
            // ADVISOR 2026-10-13 (item 4c): READ-ONLY check — reads the archive and
            // prints top-level entries, symlinks, counts and what the gate would
            // say, WITHOUT writing anything. Output goes to the Output panel.
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                OutlinedButton(
                    enabled = !backupRunning,
                    onClick = {
                        backupRunning = true
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                backupStatus = "Checking backup (read-only)..."
                                val f = BackupManager.backupFile()
                                val r = BackupTar.inspect(f)
                                if (!r.ok) {
                                    backupStatus = "Check failed: the archive could not be read — ${r.error ?: "unknown error"}"
                                } else {
                                    val lines = mutableListOf<String>()
                                    lines.add("=== Check backup: ${f.name} ===")
                                    lines.add("Size: %.1f MB (modified %s)".format(
                                        r.sizeBytes / (1024.0 * 1024.0),
                                        java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
                                            .format(java.util.Date(r.modifiedMs))))
                                    lines.add("Top-level entries: ${r.topLevel.joinToString(", ")}")
                                    lines.add("Counts: ${r.totalEntries} entries — ${r.dirs} directories, ${r.files} files, ${r.symlinks.size} symlinks")
                                    if (r.symlinks.isNotEmpty()) {
                                        lines.add("Symlinks (first 12): " + r.symlinks.take(12).joinToString("; ") { "${it.name} -> ${it.target}" })
                                    }
                                    lines.add("Critical: usr/bin/bash ${if (r.hasBash) "present" else "MISSING"}, usr/bin/env ${if (r.hasEnv) "present" else "MISSING"}")
                                    if (r.rejected.isNotEmpty()) lines.add("Rejected entries: ${r.rejected.take(5).joinToString("; ")}")
                                    val gate = if (r.hasBash && r.hasEnv)
                                        "the gate would PASS (bash and env found — lib64 is NOT required on arm64)"
                                    else
                                        "the gate would REFUSE (missing ${if (!r.hasBash) "usr/bin/bash" else "usr/bin/env"} — restore would need the nothing-to-lose path or a different file)"
                                    lines.add("Gate verdict: $gate")
                                    lines.forEach { com.codespace.ide.diagnostics.AppOutputLog.log("[CHECK-BACKUP] $it", "backup") }
                                    backupStatus = "Check backup: ${r.totalEntries} entries, bash ${if (r.hasBash) "ok" else "MISSING"}, env ${if (r.hasEnv) "ok" else "MISSING"} — full report in the Output panel"
                                }
                            }
                            backupRunning = false
                        }
                    },
                    modifier = Modifier.padding(end = 8.dp),
                ) { Text("Check backup") }
                if (prevBackupInfo != null) {
                    OutlinedButton(
                        enabled = !backupRunning,
                        onClick = {
                            backupRunning = true
                            scope.launch {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    backupStatus = "Checking previous backup (read-only)..."
                                    val f = BackupManager.previousBackupFile()
                                    val r = BackupTar.inspect(f)
                                    if (!r.ok) {
                                        backupStatus = "Check failed: the archive could not be read — ${r.error ?: "unknown error"}"
                                    } else {
                                        val lines = mutableListOf<String>()
                                        lines.add("=== Check backup: ${f.name} ===")
                                        lines.add("Size: %.1f MB (modified %s)".format(
                                            r.sizeBytes / (1024.0 * 1024.0),
                                            java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
                                                .format(java.util.Date(r.modifiedMs))))
                                        lines.add("Top-level entries: ${r.topLevel.joinToString(", ")}")
                                        lines.add("Counts: ${r.totalEntries} entries — ${r.dirs} directories, ${r.files} files, ${r.symlinks.size} symlinks")
                                        lines.add("Critical: usr/bin/bash ${if (r.hasBash) "present" else "MISSING"}, usr/bin/env ${if (r.hasEnv) "present" else "MISSING"}")
                                        val gate = if (r.hasBash && r.hasEnv)
                                            "the gate would PASS (bash and env found — lib64 is NOT required on arm64)"
                                        else
                                            "the gate would REFUSE (missing ${if (!r.hasBash) "usr/bin/bash" else "usr/bin/env"})"
                                        lines.add("Gate verdict: $gate")
                                        lines.forEach { com.codespace.ide.diagnostics.AppOutputLog.log("[CHECK-BACKUP] $it", "backup") }
                                        backupStatus = "Check previous: ${r.totalEntries} entries, bash ${if (r.hasBash) "ok" else "MISSING"}, env ${if (r.hasEnv) "ok" else "MISSING"} — full report in the Output panel"
                                    }
                                }
                                backupRunning = false
                            }
                        },
                    ) { Text("Check previous") }
                }
            }
            // Bug 4 (owner round 2026-10-14): choose ANY file (*/*) — checked by
            // CONTENT, restored from the stream; your stored backups are never
            // touched and no 200+ MB copy is made.
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                OutlinedButton(
                    enabled = !backupRunning,
                    onClick = { runCatching { pickBackupLauncher.launch(arrayOf("*/*")) } },
                ) { Text("Choose backup file... (check + restore)") }
            }
            Text(
                "Any file, any name — validated by content, not by type or extension. It is read directly; your stored backups are not touched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            // Bug 3 (owner round 2026-10-14): the auto-restore toggle — default ON.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Restore automatically", Modifier.weight(1f))
                Switch(
                    checked = autoRestore,
                    onCheckedChange = { on -> autoRestore = on; BackupManager.setAutoRestore(context, on) },
                )
            }

            // Bug 4: the picked file was Checked — now the explicit restore confirm.
            if (showCloudWarn && cloudWarnUri != null) {
                androidx.compose.runtime.key(orientation) {
                AlertDialog(
                    onDismissRequest = { showCloudWarn = false; cloudWarnUri = null },
                    title = { Text("Cloud file \u2014 read it anyway?") },
                    text = {
                        val nm = runCatching { BackupManager.queryPickedName(context, cloudWarnUri!!) }.getOrNull() ?: "the picked file"
                        val sz = runCatching { BackupManager.queryPickedSize(context, cloudWarnUri!!) }.getOrNull() ?: -1L
                        Text(
                            "\u201C$nm\u201D${if (sz >= 0) " (${sz / (1024 * 1024)} MB)" else ""} comes from a cloud provider (Drive or similar), not from this device\u2019s storage.\n\n" +
                                "Checking and restoring reads it TWICE over your connection \u2014 that can use a lot of mobile data and may be slow. Nothing is copied to the device; if the connection drops, the restore stops with an error and nothing is changed.\n\n" +
                                "Continue?"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val u = cloudWarnUri
                            showCloudWarn = false
                            cloudWarnUri = null
                            if (u != null) runPickedCheck(u)
                        }) { Text("Continue") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCloudWarn = false; cloudWarnUri = null }) { Text("Cancel") }
                    },
                )
                }
            }

            if (showPickedRestoreConfirm) {
                androidx.compose.runtime.key(orientation) {
                AlertDialog(
                    onDismissRequest = { showPickedRestoreConfirm = false; pickedUri = null },
                    title = { Text("Restore the picked file?") },
                    text = {
                        Text(
                            (pickedReport.take(700)) + "\n\n" +
                                "It REPLACES your current Ubuntu container; everything changed since that backup will be lost. Close Ubuntu terminal tabs first. Your stored backups are not touched."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showPickedRestoreConfirm = false
                            // Advisor 4 (2026-10-15): a restore over a WORKING container
                            // goes through the restore-over-healthy gate (back up
                            // current first, refuse while shells run) instead of
                            // replacing it silently. pickedUri stays — the gate owns it.
                            val healthy = runCatching { ProotInstaller.rootfsHealthy(context) }.getOrDefault(false)
                            if (healthy) {
                                rohShells = TerminalService.liveSessionCount()
                                showRestoreOverHealthy = true
                            } else {
                                runPickedRestore(false)
                            }
                        }) { Text("Restore it") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPickedRestoreConfirm = false; pickedUri = null }) { Text("Cancel") }
                    },
                )
                }
            }

            if (showRestoreConfirm) {
                key(orientation) {
                AlertDialog(
                    onDismissRequest = { showRestoreConfirm = false },
                    title = { Text("Restore container?") },
                    text = {
                        // Advisor item 2 (2026-10-11): SIZE primary; date labelled
                        // "modified" (Files can reset it on copy — never an
                        // integrity check).
                        val info = BackupManager.backupInfo()
                        Text(
                            "Restore this backup: ${info ?: "unreadable"}\n\n" +
                                "It REPLACES your current Ubuntu container. Everything installed or changed in Ubuntu since that backup will be LOST (new packages, container-side files).\n\n" +
                                "Close your Ubuntu terminal tabs first — running shells break when the container is replaced."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showRestoreConfirm = false
                            backupRunning = true
                            scope.launch {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    BackupManager.restorePrefs(context)
                                    val rres = BackupManager.restoreBackup(context, onProgress = { msg -> backupStatus = msg }, explicit = true)
                                    if (!rres.ok) backupStatus = "Restore failed: " + rres.message.take(200)
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

            if (showPrevRestoreConfirm) {
                AlertDialog(
                    onDismissRequest = { showPrevRestoreConfirm = false },
                    title = { Text("Restore PREVIOUS backup?") },
                    text = {
                        Text(
                            "This restores container-backup.prev — the generation before your current backup (${prevBackupInfo ?: "unknown"}).\n\n" +
                                "It REPLACES your current Ubuntu container; everything installed or changed since that backup will be lost. Close Ubuntu terminal tabs first. This is a separate file; your current backup is untouched."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showPrevRestoreConfirm = false
                            backupRunning = true
                            scope.launch {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val rres = BackupManager.restoreBackup(context, { msg -> backupStatus = msg }, usePrevious = true, explicit = true)
                                    if (!rres.ok) backupStatus = "Previous-backup restore failed: " + rres.message.take(200)
                                }
                                backupRunning = false
                            }
                        }) { Text("Restore previous") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPrevRestoreConfirm = false }) { Text("Cancel") }
                    },
                )
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
                                            com.codespace.ide.terminal.ContainerSafety.deleteTreeSafe(rootfsDir)
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
            // SK14 (2026-09-26): cross-link — formatter CHOICE lives here, but Format on
            // Save and the TypeScript version live in In-Project Settings (gear menu).
            // The two surfaces used to contradict silently with no pointer between them.
            Text(
                "Format on Save and the TypeScript version are set in In-Project Settings (gear menu). This picker only chooses which formatter runs.",
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

            // ── Settings Backup (SK12) ─────────────────────────────────────────
            // SK12 (2026-09-26): exportJson/importJson (schema-validating, typed) had
            // NO callers since they were written — there was no settings backup/restore
            // path in any UI, and container backup does not cover settings.json.
            Text("Settings Backup", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            Text(
                "Back up app settings (incl. keybindings) as JSON, or restore from a backup file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp)) {
                OutlinedButton(onClick = {
                    val json = JsonSettingsStore.exportJson()
                    clipboard.setText(AnnotatedString(json))
                    savedMsg = "✓ Settings JSON copied to clipboard — paste it somewhere safe"
                }) { Text("Export to clipboard") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { settingsImportLauncher.launch("application/json") }) { Text("Import from file…") }
            }
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
                "Build ${BuildConfig.VERSION_CODE} (${BuildConfig.GIT_HASH}) — v${BuildConfig.VERSION_NAME}" +
                    " — CI run ${BuildConfig.CI_RUN_NUMBER} @ ${BuildConfig.BUILD_TIME}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF717171),
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
            )
            // BATCH10-P00: the PROTECTED build/lifecycle history — the last lines
            // Output "Clear" can never erase (startup stamp + [LSP-LIFECYCLE]).
            // Shows the tail so a saved screenshot of Settings answers "which
            // build, what lifecycle hops happened" even after a Clear.
            val protectedTail = com.codespace.ide.diagnostics.AppOutputLog.protectedLines
                .toList().takeLast(10)
            if (protectedTail.isNotEmpty()) {
                Text(
                    "Protected build/lifecycle log (last ${protectedTail.size} of " +
                        "${com.codespace.ide.diagnostics.AppOutputLog.protectedLines.size} — survives Clear):",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF717171),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
                protectedTail.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF717171),
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            }
            // SK10: search results branch — every surface indexed in one list.
            else {
                val q = searchQuery.lowercase()
                val matches = SETTINGS_SEARCH_INDEX.filter {
                    it.name.lowercase().contains(q) || it.section.lowercase().contains(q)
                }
                Text(
                    "${matches.size} setting" + (if (matches.size == 1) "" else "s") + " matching \"$searchQuery\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF717171),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                matches.forEach { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = {
                            Text("Section: ${entry.section} — ${entry.surface}", style = MaterialTheme.typography.bodySmall)
                        },
                        modifier = Modifier.clickable { searchQuery = "" },
                    )
                    HorizontalDivider()
                }
                if (matches.isEmpty()) {
                    ListItem(
                        headlineContent = { Text("No settings match here") },
                        supportingContent = {
                            Text("In-Project Settings (gear menu) has its own search.", style = MaterialTheme.typography.bodySmall)
                        },
                        modifier = Modifier.clickable { searchQuery = "" },
                    )
                }
            }
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

/**
 * RG05 (2026-09-26): the container-backup prompt banner. Extracted to its own composable
 * (JVM 64KB bytecode limit — no inline additions to the big SettingsScreen body).
 */
/**
 * Advisor 4c (2026-10-15, approved): the restore-over-healthy gate, extracted
 * (64KB rule). While shells run it refuses and offers to close them; the main
 * content names BOTH backups (the picked file with size, the current saved
 * backup with size and date) and what exactly will be lost.
 */
@androidx.compose.runtime.Composable
private fun RestoreOverHealthyDialog(
    shellsRunning: Int,
    pickedDesc: String,
    currentBackupInfo: String?,
    onShellsClosed: () -> Unit,
    onBackUpFirst: () -> Unit,
    onRestoreWithoutBackup: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (shellsRunning > 0) "Ubuntu terminals are running"
                else "Restore over your working Ubuntu?"
            )
        },
        text = {
            if (shellsRunning > 0) {
                Text(
                    "$shellsRunning Ubuntu terminal session(s) are running — a restore replaces the container they depend on, so they must be closed first. Closing them here is safe for your files: projects live outside the container.",
                )
            } else {
                Text(
                    "Backup to restore: $pickedDesc.\n\n" +
                        "Current container's saved backup: " + (currentBackupInfo ?: "none yet") + " (not touched).\n\n" +
                        "Everything changed inside Ubuntu since that backup will be lost: installed tools and packages, files under /root, terminal history. Your project files on shared storage are not touched.",
                )
            }
        },
        confirmButton = {
            if (shellsRunning > 0) {
                TextButton(onClick = onShellsClosed) { Text("Close them and continue") }
            } else {
                TextButton(onClick = onBackUpFirst) { Text("Back up current first") }
            }
        },
        dismissButton = {
            if (shellsRunning > 0) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            } else {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = onRestoreWithoutBackup) { Text("Restore without backing up") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        },
    )
}

/**
 * Advisor 4d (2026-10-15): confirm for "Restore previous container" — the
 * pre-restore safety copy taken by the back-up-first choice. Same shells gate.
 */
@androidx.compose.runtime.Composable
private fun PreRestoreConfirmDialog(
    shellsRunning: Int,
    desc: String,
    onShellsClosed: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (shellsRunning > 0) "Ubuntu terminals are running"
                else "Restore the previous container?"
            )
        },
        text = {
            if (shellsRunning > 0) {
                Text(
                    "$shellsRunning Ubuntu terminal session(s) are running — close them first (safe here: projects live outside the container).",
                )
            } else {
                Text(
                    "Restores $desc — the container as it was right before the last restore. Everything changed inside Ubuntu since then will be lost. Your project files on shared storage are not touched.",
                )
            }
        },
        confirmButton = {
            if (shellsRunning > 0) {
                TextButton(onClick = onShellsClosed) { Text("Close them and continue") }
            } else {
                TextButton(onClick = onRestore) { Text("Restore it") }
            }
        },
        dismissButton = {
            if (shellsRunning > 0) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ContainerBackupPromptBanner(onBackupNow: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("Container backup recommended", style = MaterialTheme.typography.titleSmall)
            Text(
                "App updated, freshly installed, or the container backup is missing or older than a week. " +
                    "Prefs and settings are backed up automatically; the Ubuntu container itself is not.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = onBackupNow) { Text("Back up now") }
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        }
    }
}

// ── SK10 (2026-09-26): the unified settings index ──────────────────────────
// Settings live on three surfaces: this screen, In-Project Settings (gear menu),
// and the shell theme picker. This index makes all of them findable from the one
// search box at the top of the screen. Keep it in sync when adding a setting.

private data class SettingsIndexEntry(val name: String, val section: String, val surface: String)

private val SETTINGS_SEARCH_INDEX = listOf(
    SettingsIndexEntry("Dark mode", "Appearance", "Settings"),
    SettingsIndexEntry("Theme picker (Dracula, AMOLED, Nord…)", "Appearance", "Shell gear menu"),
    SettingsIndexEntry("App lock (PIN / biometric)", "Security", "Settings"),
    SettingsIndexEntry("GitHub account", "Accounts", "Settings"),
    SettingsIndexEntry("AI provider keys", "AI Providers", "Settings"),
    SettingsIndexEntry("Container backup & restore", "Container Backup", "Settings"),
    SettingsIndexEntry("Language servers status", "Language Servers", "Settings"),
    SettingsIndexEntry("Reinstall Ubuntu rootfs", "Ubuntu Container", "Settings"),
    SettingsIndexEntry("Settings backup (export / import JSON)", "Settings Backup", "Settings"),
    SettingsIndexEntry("Restore previous session on project open", "Workspace Memory", "Settings"),
    SettingsIndexEntry("Formatter selection", "Formatter Selection", "Settings"),
    SettingsIndexEntry("Clear data", "Clear Data", "Settings"),
    SettingsIndexEntry("Deleted projects (recycle bin)", "Clear Data", "Settings"),
    SettingsIndexEntry("Keybindings editor", "Keybindings", "In-Project Settings (gear menu)"),
    SettingsIndexEntry("AI Agent Flow", "AI Agent", "In-Project Settings (gear menu)"),
    SettingsIndexEntry("Gemini image model", "AI Agent", "In-Project Settings (gear menu)"),
    SettingsIndexEntry("Format on Save", "Text Editor", "In-Project Settings (gear menu)"),
    SettingsIndexEntry("TypeScript version", "Text Editor", "In-Project Settings (gear menu)"),
    SettingsIndexEntry("Cursor blinking", "Text Editor", "In-Project Settings (gear menu)"),
    SettingsIndexEntry("Extra coding keys toolbar", "Editor Keyboard", "In-Project Settings (gear menu)"),
    SettingsIndexEntry("Task completion notifications", "Notifications", "In-Project Settings (gear menu)"),
    SettingsIndexEntry("Terminal notifications", "Notifications", "In-Project Settings (gear menu)"),
    SettingsIndexEntry("App WakeLock", "Shell", "Shell gear menu"),
)
