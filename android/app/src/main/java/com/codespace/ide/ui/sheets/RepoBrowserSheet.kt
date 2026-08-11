package com.codespace.ide.ui.sheets

import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.data.SecureTokenStore
import com.codespace.ide.domain.Project
import com.codespace.ide.domain.ProjectKind
import com.codespace.ide.terminal.ProotInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

// ──────────────────────────────────────────────────────────────────────────────
// RepoBrowserSheet  (item #11 in the 2026-07-06 backlog)
// Lets the user browse their GitHub repos and clone one into the Ubuntu proot
// rootfs (/root/repos/<name>) without leaving the app.
//
// Tokens: already stored by the GitHub device-code flow in SettingsScreen.
// Auth:   same "Authorization: Basic base64(x-access-token:<token>)" header
//         already proven working in SourceControlPane.runGit().
// Clone:  routes through ProotInstaller.execOnce() — git only exists inside
//         the Ubuntu proot, never on the bare Android host PATH.
// ──────────────────────────────────────────────────────────────────────────────

private data class GhRepo(
    val name: String,
    val fullName: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
    val cloneUrl: String,
    val description: String,
)

private val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoBrowserSheet(
    onDismiss: () -> Unit,
    onProjectCreated: (Project) -> Unit,
) {
    val context     = LocalContext.current
    val scope       = rememberCoroutineScope()
    val orientation = LocalConfiguration.current.orientation

    val token = remember { SecureTokenStore(context).githubToken }

    // ── Not signed in ─────────────────────────────────────────────────────────
    if (token.isNullOrBlank()) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Connect GitHub in Settings first",
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDismiss) { Text("Dismiss") }
                Spacer(Modifier.height(32.dp))
            }
        }
        return
    }

    // ── State ──────────────────────────────────────────────────────────────────
    var repos          by remember { mutableStateOf<List<GhRepo>>(emptyList()) }
    var searchQuery    by remember { mutableStateOf("") }
    var loading        by remember { mutableStateOf(true) }
    var errorMsg       by remember { mutableStateOf<String?>(null) }
    var repoToClone    by remember { mutableStateOf<GhRepo?>(null) }
    var targetPath     by remember { mutableStateOf("") }
    var cloneProgress  by remember { mutableStateOf<String?>(null) }

    // ── Fetch repos ────────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        loading  = true
        errorMsg = null
        withContext(Dispatchers.IO) {
            try {
                val resp = http.newCall(
                    Request.Builder()
                        .url("https://api.github.com/user/repos?sort=updated&per_page=50&affiliation=owner,collaborator")
                        .header("Authorization", "Bearer $token")
                        .header("Accept", "application/vnd.github.v3+json")
                        .build()
                ).execute()
                if (!resp.isSuccessful) {
                    errorMsg = "GitHub returned ${resp.code} — check your token in Settings"
                    return@withContext
                }
                val arr = JSONArray(resp.body?.string() ?: "[]")
                repos = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    GhRepo(
                        name          = o.optString("name", ""),
                        fullName      = o.optString("full_name", ""),
                        isPrivate     = o.optBoolean("private", false),
                        defaultBranch = o.optString("default_branch", "main"),
                        cloneUrl      = o.optString("clone_url", ""),
                        description   = o.optString("description", ""),
                    )
                }
            } catch (e: Exception) {
                errorMsg = e.localizedMessage ?: "Network error"
            } finally {
                loading = false
            }
        }
    }

    // ── Clone dialog (rotation-safe) ──────────────────────────────────────────
    key(orientation) {
        repoToClone?.let { repo ->
            AlertDialog(
                onDismissRequest = { repoToClone = null },
                title = { Text("Clone repository") },
                text = {
                    Column {
                        Text(repo.fullName,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value         = targetPath,
                            onValueChange = { targetPath = it },
                            label         = { Text("Destination (inside Ubuntu)") },
                            modifier      = Modifier.fillMaxWidth(),
                            singleLine    = true,
                        )
                        Text(
                            "Path is inside /root — the Ubuntu proot rootfs.",
                            style    = MaterialTheme.typography.labelSmall,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val dest = targetPath.trim()
                        if (dest.isBlank()) return@Button
                        repoToClone   = null
                        cloneProgress = "Cloning ${repo.name}…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    val basic = Base64.encodeToString(
                                        "x-access-token:$token".toByteArray(),
                                        Base64.NO_WRAP,
                                    )
                                    // Same auth-header pattern as SourceControlPane.runGit()
                                    // safe.directory=* avoids "detected dubious ownership" on
                                    // /sdcard-hosted repos once opened (UID mismatch with proot root).
                                    val cmd = "git -c safe.directory='*' -c http.extraheader=\"Authorization: Basic $basic\" " +
                                              "clone '${repo.cloneUrl.replace("'", "'\\''")}' '$dest'"
                                    ProotInstaller.execOnce(context, cmd, null, 180L)
                                } catch (e: Exception) {
                                    "ERROR: ${e.localizedMessage}"
                                }
                            }
                            cloneProgress = null
                            if (result.startsWith("ERROR:") || result.contains("fatal:")) {
                                errorMsg = result.take(200)
                            } else {
                                // Build host-side absolute path (rootfs + guest path)
                                val hostPath = ProotInstaller.rootfsDir(context).absolutePath +
                                    if (dest.startsWith("/")) dest else "/$dest"
                                onProjectCreated(
                                    Project(
                                        id            = System.currentTimeMillis().toString(),
                                        name          = repo.name,
                                        kind          = ProjectKind.GIT,
                                        pathOrUrl     = hostPath,
                                        defaultBranch = repo.defaultBranch,
                                        lastOpened    = System.currentTimeMillis(),
                                    )
                                )
                                onDismiss()
                            }
                        }
                    }) { Text("Clone") }
                },
                dismissButton = {
                    TextButton(onClick = { repoToClone = null }) { Text("Cancel") }
                },
            )
        }
    }

    // ── Bottom sheet ───────────────────────────────────────────────────────────
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "Clone from GitHub",
                style    = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            OutlinedTextField(
                value         = searchQuery,
                onValueChange = { searchQuery = it },
                label         = { Text("Search repos") },
                modifier      = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                singleLine    = true,
                shape         = RoundedCornerShape(8.dp),
            )

            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator()
                    errorMsg != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(errorMsg ?: "", color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp))
                        TextButton(onClick = { errorMsg = null; loading = true }) { Text("Retry") }
                    }
                    else -> {
                        val filtered = repos.filter {
                            it.name.contains(searchQuery, ignoreCase = true) ||
                            it.fullName.contains(searchQuery, ignoreCase = true)
                        }
                        if (filtered.isEmpty()) {
                            Text("No repos found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(filtered, key = { it.fullName }) { repo ->
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                targetPath  = "/root/repos/${repo.name}"
                                                repoToClone = repo
                                            }
                                            .padding(vertical = 10.dp, horizontal = 4.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(repo.name,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier   = Modifier.weight(1f, fill = false),
                                                maxLines   = 1,
                                                overflow   = TextOverflow.Ellipsis)
                                            if (repo.isPrivate) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(
                                                    color  = MaterialTheme.colorScheme.errorContainer,
                                                    shape  = RoundedCornerShape(4.dp),
                                                ) {
                                                    Text("\uD83D\uDD12 Private",
                                                        style    = MaterialTheme.typography.labelSmall,
                                                        color    = MaterialTheme.colorScheme.onErrorContainer,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                }
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceVariant,
                                                shape = RoundedCornerShape(4.dp),
                                            ) {
                                                Text(repo.defaultBranch,
                                                    style    = MaterialTheme.typography.labelSmall,
                                                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                            }
                                        }
                                        if (repo.description.isNotBlank()) {
                                            Text(repo.description,
                                                style    = MaterialTheme.typography.bodySmall,
                                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(top = 2.dp))
                                        }
                                        HorizontalDivider(
                                            Modifier.padding(top = 10.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Clone progress overlay
            cloneProgress?.let { msg ->
                Surface(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(msg,
                            color    = MaterialTheme.colorScheme.onSecondaryContainer,
                            style    = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
