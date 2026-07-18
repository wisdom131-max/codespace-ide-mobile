package com.codespace.ide.ui.panes

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.terminal.McpShellProfile
import com.codespace.ide.terminal.ProotInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Colours ──────────────────────────────────────────────────────────────────
private val PkgBg      = Color(0xFF1E1E1E)
private val PkgSurface = Color(0xFF252526)
private val PkgBorder  = Color(0xFF3C3C3C)
private val PkgText    = Color(0xFFD4D4D4)
private val PkgMuted   = Color(0xFF858585)
private val PkgAccent  = Color(0xFF007ACC)
private val PkgGreen   = Color(0xFF4EC994)
private val PkgRed     = Color(0xFFF44747)
private val PkgYellow  = Color(0xFFFFCC00)

// ─── Data ─────────────────────────────────────────────────────────────────────
data class PkgInfo(val name: String, val description: String, val category: String)

data class PkgOperation(
    val packageName: String,
    val action: String,
    val output: MutableList<String> = mutableListOf(),
    var done: Boolean = false,
    var success: Boolean = false,
    @Transient var process: Process? = null,
    // P25-3: holds the live subprocess so the Cancel button can kill it
    @Transient var cancelRef: java.util.concurrent.atomic.AtomicReference<Process?>? = null,
)

// ─── Featured packages ────────────────────────────────────────────────────────
private val FEATURED_PACKAGES = listOf(
    PkgInfo("git",         "Distributed version control system",   "vcs"),
    PkgInfo("python",      "Python 3 interpreter",                 "lang"),
    PkgInfo("nodejs",      "JavaScript runtime (Node.js)",         "lang"),
    PkgInfo("curl",        "Command-line HTTP client",             "net"),
    PkgInfo("wget",        "Non-interactive network downloader",   "net"),
    PkgInfo("vim",         "Vi Improved text editor",              "editor"),
    PkgInfo("neovim",      "Hyperextensible Vim-based editor",     "editor"),
    PkgInfo("tmux",        "Terminal multiplexer",                 "util"),
    PkgInfo("htop",        "Interactive process viewer",           "util"),
    PkgInfo("ffmpeg",      "Audio/video converter",                "media"),
    PkgInfo("imagemagick", "Image manipulation toolkit",           "media"),
    PkgInfo("openssh",     "SSH client & server",                  "net"),
    PkgInfo("rsync",       "Fast remote file sync",                "net"),
    PkgInfo("zip",         "Zip compression utility",              "util"),
    PkgInfo("unzip",       "Zip extraction utility",               "util"),
    PkgInfo("jq",          "Lightweight JSON processor",           "util"),
    PkgInfo("ripgrep",     "Fast regex grep replacement",          "search"),
    PkgInfo("fzf",         "Fuzzy finder for the terminal",        "search"),
    PkgInfo("clang",       "C/C++ compiler (LLVM)",                "lang"),
    PkgInfo("rust",        "Rust programming language toolchain",  "lang"),
    PkgInfo("golang",      "Go programming language",              "lang"),
    PkgInfo("ruby",        "Ruby interpreter",                     "lang"),
    PkgInfo("perl",        "Perl interpreter",                     "lang"),
    PkgInfo("sqlite",      "Self-contained SQL database engine",   "db"),
    PkgInfo("mariadb",     "MySQL-compatible database server",     "db"),
    PkgInfo("redis",       "In-memory data structure store",       "db"),
    PkgInfo("nginx",       "HTTP and reverse proxy server",        "net"),
    PkgInfo("tmate",       "Terminal sharing via SSH",             "net"),
    PkgInfo("nmap",        "Network discovery and security tool",  "net"),
    PkgInfo("gdb",         "GNU debugger",                         "dev"),
    PkgInfo("make",        "Build automation tool",                "dev"),
    PkgInfo("cmake",       "Cross-platform build system",          "dev"),
    PkgInfo("strace",      "System call tracer",                   "dev"),
    PkgInfo("proot",       "User-space chroot replacement",        "sys"),
    PkgInfo("termux-api",  "Access Android device APIs",           "sys"),
)

// ─── Install history (SharedPrefs) ───────────────────────────────────────────
private const val PREFS_HISTORY = "pkg_install_history"
private const val KEY_HISTORY   = "log"
private const val MAX_HISTORY   = 200

private fun appendHistory(context: Context, action: String, pkg: String, success: Boolean) {
    val prefs    = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
    val existing = prefs.getString(KEY_HISTORY, "") ?: ""
    val stamp    = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    val mark     = if (success) "+" else "x"
    val entry    = "[$mark] $action $pkg — $stamp"
    val lines    = existing.split("\n").filter { it.isNotBlank() }.toMutableList()
    lines.add(0, entry)
    if (lines.size > MAX_HISTORY) lines.subList(MAX_HISTORY, lines.size).clear()
    prefs.edit().putString(KEY_HISTORY, lines.joinToString("\n")).apply()
}

private fun loadHistory(context: Context): List<String> {
    val prefs = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
    val raw   = prefs.getString(KEY_HISTORY, "") ?: ""
    return raw.split("\n").filter { it.isNotBlank() }
}

// ─── ExtensionsPanel ─────────────────────────────────────────────────────────
@Composable
internal fun ExtensionsPanel() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var searchQuery     by remember { mutableStateOf("") }
    var installedPkgs   by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searchResults   by remember { mutableStateOf<List<PkgInfo>>(FEATURED_PACKAGES) }
    var activeOperation by remember { mutableStateOf<PkgOperation?>(null) }
    var showInstalled   by remember { mutableStateOf(false) }
    var showHistory     by remember { mutableStateOf(false) }
    var installHistory  by remember { mutableStateOf<List<String>>(emptyList()) }
    var isSearching     by remember { mutableStateOf(false) }

    // ── apt runner ────────────────────────────────────────────────────────────
    fun runPkg(pkg: String, action: String) {
        if (activeOperation?.done == false) return
        val op = PkgOperation(pkg, action)
        activeOperation = op
        scope.launch(Dispatchers.IO) {
            try {
                val cmdStr = when (action) {
                    "install"     -> "apt-get install -y $pkg 2>&1"
                    "remove"      -> "apt-get remove -y $pkg 2>&1"
                    "update"      -> "apt-get upgrade -y $pkg 2>&1"
                    "upgrade-all" -> "apt-get upgrade -y 2>&1"
                    else          -> "apt-get install -y $pkg 2>&1"
                }
                val cancelRef = java.util.concurrent.atomic.AtomicReference<Process?>(null)
                op.cancelRef = cancelRef  // P25-3: expose to Cancel button
                val result = ProotInstaller.execOnceWithProcess(
                    context, cmdStr, timeoutSeconds = 120L, logToOutput = true
                ) { proc -> cancelRef.set(proc) }
                op.output.addAll(result.lines())
                op.success = !result.startsWith("Exit code") && !result.startsWith("Error") && !result.startsWith("Timed out")
                op.done    = true
                appendHistory(context, action, pkg, op.success)
                if (op.success && action == "install") installedPkgs = installedPkgs + pkg
                if (op.success && action == "remove")  installedPkgs = installedPkgs - pkg
                scope.launch(Dispatchers.Main) {
                    activeOperation = op.copy(cancelRef = op.cancelRef) // P25-3: preserve cancelRef through copy
                    installHistory  = loadHistory(context)
                }
            } catch (e: Exception) {
                op.output.add("Error: ${e.message}")
                op.done = true; op.success = false
                scope.launch(Dispatchers.Main) { activeOperation = op.copy(cancelRef = op.cancelRef) } // P25-3: preserve cancelRef through copy
            }
        }
    }

    // ── upgrade-all ───────────────────────────────────────────────────────────
    fun upgradeAll() { runPkg("(all)", "upgrade-all") }

    // ── load installed via dpkg ───────────────────────────────────────────────
    fun loadInstalled() {
        scope.launch(Dispatchers.IO) {
            try {
                val out = ProotInstaller.execOnce(context, "dpkg --list 2>/dev/null | awk '/^ii/{print \$2}'")
                val pkgs = out.lines().filter { it.isNotBlank() }.toSet()
                withContext(Dispatchers.Main) { installedPkgs = pkgs }
            } catch (_: Exception) {}
        }
    }

    // ── apt-cache search ──────────────────────────────────────────────────────
    fun doSearch(q: String) {
        if (q.isBlank()) { searchResults = FEATURED_PACKAGES; return }
        isSearching = true
        scope.launch(Dispatchers.IO) {
            try {
                val raw = ProotInstaller.execOnce(context, "apt-cache search ${q.trim()} 2>/dev/null | head -40", timeoutSeconds = 30L)
                val lines = raw.lines()
                val results = lines.mapNotNull { line ->
                    val parts = line.split(" - ", limit = 2)
                    if (parts.size == 2) PkgInfo(parts[0].trim(), parts[1].trim(), "search") else null
                }
                withContext(Dispatchers.Main) {
                    searchResults = results.ifEmpty {
                        FEATURED_PACKAGES.filter {
                            it.name.contains(q, ignoreCase = true) ||
                            it.description.contains(q, ignoreCase = true)
                        }
                    }
                    isSearching = false
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    searchResults = FEATURED_PACKAGES.filter {
                        it.name.contains(q, ignoreCase = true) ||
                        it.description.contains(q, ignoreCase = true)
                    }
                    isSearching = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        loadInstalled()
        installHistory = loadHistory(context)
    }
    LaunchedEffect(searchQuery) { delay(400); doSearch(searchQuery) }

    // ── UI ────────────────────────────────────────────────────────────────────
    Column(Modifier.fillMaxSize().background(PkgBg)) {

        // Header
        Row(
            Modifier.fillMaxWidth().background(PkgSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("EXTENSIONS", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp, color = PkgMuted, modifier = Modifier.weight(1f))
            // Browse / Installed toggle
            TextButton(
                onClick = { showHistory = false; showInstalled = !showInstalled },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) {
                Text(if (showInstalled && !showHistory) "Browse" else "Installed",
                    fontSize = 11.sp, color = if (showInstalled && !showHistory) PkgAccent else PkgMuted)
            }
            // History toggle
            TextButton(
                onClick = { showHistory = !showHistory; showInstalled = false },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text("History", fontSize = 11.sp, color = if (showHistory) PkgAccent else PkgMuted)
            }
            // Upgrade all
            IconButton(onClick = { upgradeAll() }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.SystemUpdate, "Upgrade all",
                    tint = PkgMuted, modifier = Modifier.size(16.dp))
            }
            // Refresh package lists
            IconButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        try {
                            ProotInstaller.execOnce(context, "apt-get update -y 2>&1", timeoutSeconds = 60L)
                        } catch (_: Exception) {}
                    }
                },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(Icons.Default.Refresh, "Update lists",
                    tint = PkgMuted, modifier = Modifier.size(16.dp))
            }
        }

        when {
            // ── History tab ───────────────────────────────────────────────────
            showHistory -> {
                if (installHistory.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No installation history yet", color = PkgMuted, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(installHistory) { entry ->
                            val isOk = entry.startsWith("[+]")
                            Text(
                                entry,
                                fontSize = 11.sp,
                                color    = if (isOk) PkgGreen else PkgRed,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                            HorizontalDivider(color = PkgBorder, thickness = 0.5.dp)
                        }
                    }
                }
            }

            // ── Installed tab ─────────────────────────────────────────────────
            showInstalled -> {
                if (installedPkgs.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No packages detected", color = PkgMuted, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(installedPkgs.sorted(), key = { it }) { name ->
                            val isBusy = activeOperation?.packageName == name && activeOperation?.done == false
                            val pkg = FEATURED_PACKAGES.firstOrNull { it.name == name }
                                ?: PkgInfo(name, "Installed package", "installed")
                            PkgRow(pkg, isInstalled = true, isBusy = isBusy,
                                onInstall = {}, onRemove = { runPkg(name, "remove") })
                            HorizontalDivider(color = PkgBorder, thickness = 0.5.dp)
                        }
                    }
                }
            }

            // ── Browse tab (default) ──────────────────────────────────────────
            else -> {
                // Search bar
                Row(
                    Modifier.fillMaxWidth().padding(8.dp)
                        .background(PkgSurface, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, null, tint = PkgMuted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(color = PkgText, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(PkgAccent),
                        singleLine = true,
                        decorationBox = { inner ->
                            if (searchQuery.isEmpty())
                                Text("Search packages (apt-cache)…", fontSize = 13.sp, color = PkgMuted)
                            inner()
                        },
                    )
                    if (isSearching) CircularProgressIndicator(
                        modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = PkgAccent)
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(searchResults, key = { it.name }) { pkg ->
                        val isInstalled = pkg.name in installedPkgs
                        val isBusy = activeOperation?.packageName == pkg.name &&
                            activeOperation?.done == false
                        PkgRow(pkg, isInstalled, isBusy,
                            onInstall = { runPkg(pkg.name, "install") },
                            onRemove  = { runPkg(pkg.name, "remove") })
                        HorizontalDivider(color = PkgBorder, thickness = 0.5.dp)
                    }
                }
            }
        }

        // ── Operation output strip ────────────────────────────────────────────
        val op = activeOperation
        if (op != null) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 180.dp)
                    .background(Color(0xFF0D0D0D)).padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dot = when { !op.done -> PkgYellow; op.success -> PkgGreen; else -> PkgRed }
                    Box(Modifier.size(8.dp).background(dot, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${op.action.uppercase()} ${op.packageName}" +
                        if (op.done) (if (op.success) " — done" else " — failed") else "…",
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = dot, modifier = Modifier.weight(1f),
                    )
                    if (!op.done) {
                        TextButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    // P25-3: cancel the actual running process via cancelRef (op.process was always null)
                                try { op.cancelRef?.get()?.destroyForcibly() } catch (_: Exception) {}
                                op.done = true; op.success = false
                                op.output.add("[Cancelled by user]")
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) { Text("Cancel", fontSize = 11.sp, color = PkgRed) }
                    } else {
                        IconButton(onClick = { activeOperation = null },
                            modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, null, tint = PkgMuted,
                                modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                val scroll = rememberScrollState(Int.MAX_VALUE)
                Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                    op.output.takeLast(60).forEach { line ->
                        Text(line, fontSize = 10.sp, color = PkgMuted,
                            fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}

// ─── Package row ──────────────────────────────────────────────────────────────
@Composable
private fun PkgRow(
    pkg: PkgInfo,
    isInstalled: Boolean,
    isBusy: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(PkgSurface, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center) {
            Text(pkg.name.take(1).uppercase(), fontSize = 13.sp,
                fontWeight = FontWeight.Bold, color = PkgAccent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(pkg.name, fontSize = 13.sp, color = PkgText, fontWeight = FontWeight.Medium)
            Text(pkg.description, fontSize = 11.sp, color = PkgMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        when {
            isBusy      -> CircularProgressIndicator(modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp, color = PkgAccent)
            isInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("OK", fontSize = 10.sp, color = PkgGreen, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onRemove,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text("Remove", fontSize = 11.sp, color = PkgRed)
                }
            }
            else        -> TextButton(onClick = onInstall,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text("Install", fontSize = 11.sp, color = PkgAccent)
            }
        }
    }
}

// ─── McpPanel ─────────────────────────────────────────────────────────────────
@Composable
internal fun McpPanel() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var serverRunning   by remember { mutableStateOf(false) }
    var toolCount       by remember { mutableStateOf(0) }
    var bashrcInstalled by remember { mutableStateOf(false) }
    val agentApiUrl     = "http://localhost:8765"

    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                // health check
                serverRunning = try {
                    val conn = java.net.URL("$agentApiUrl/health")
                        .openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 1000; conn.readTimeout = 1000
                    val ok = conn.responseCode == 200
                    conn.disconnect(); ok
                } catch (_: Exception) { false }
                // tool count
                try {
                    val agentJson = File(context.filesDir, "termux-prefix/root/.agent.json")
                    if (agentJson.exists())
                        toolCount = agentJson.readText().split("\"name\"").size - 1
                } catch (_: Exception) {}
                // bashrc check
                bashrcInstalled = try {
                    val bashrc = File(context.filesDir, "termux-prefix/root/.bashrc")
                    bashrc.exists() && bashrc.readText().contains("AGENT_API_URL")
                } catch (_: Exception) { false }
            }
            delay(5000)
        }
    }

    Column(Modifier.fillMaxWidth().background(PkgSurface).padding(12.dp)) {
        Text("MCP / AGENT TOOLS", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp, color = PkgMuted, modifier = Modifier.padding(bottom = 8.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(
                if (serverRunning) PkgGreen else PkgRed, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(if (serverRunning) "Agent API running" else "Agent API offline",
                    fontSize = 12.sp, color = if (serverRunning) PkgGreen else PkgMuted)
                Text(agentApiUrl, fontSize = 10.sp, color = PkgMuted,
                    fontFamily = FontFamily.Monospace)
            }
            TextButton(
                onClick = { scope.launch(Dispatchers.IO) {
                    try { McpShellProfile.install(context) } catch (_: Exception) {}
                }},
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) { Text(if (serverRunning) "Restart" else "Start", fontSize = 11.sp, color = PkgAccent) }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = PkgBorder, thickness = 0.5.dp)
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth()) {
            McpStat("Tools",         if (toolCount > 0) toolCount.toString() else "—")
            McpStat("Shell profile", if (bashrcInstalled) "installed" else "missing")
            McpStat("Port",          "8765")
        }

        if (!bashrcInstalled) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { scope.launch(Dispatchers.IO) {
                    try { McpShellProfile.install(context) } catch (_: Exception) {}
                }},
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp),
                shape = RoundedCornerShape(4.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(PkgAccent)),
            ) {
                Icon(Icons.Default.Terminal, null, tint = PkgAccent,
                    modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Install shell profile", fontSize = 12.sp, color = PkgAccent)
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("agent_read", "agent_write", "agent_run", "agent_git", "agent_search")
                .forEach { tool ->
                    Text(tool, fontSize = 9.sp, color = PkgAccent,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(Color(0xFF0D2137), RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp))
                }
        }
    }
}

@Composable
private fun McpStat(label: String, value: String) {
    Column(Modifier.padding(end = 16.dp)) {
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PkgText)
        Text(label, fontSize = 10.sp, color = PkgMuted)
    }
}
