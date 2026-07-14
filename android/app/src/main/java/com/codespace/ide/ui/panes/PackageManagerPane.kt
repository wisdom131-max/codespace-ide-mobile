package com.codespace.ide.ui.panes

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ─── Colour palette (matches IDE dark theme) ──────────────────────────────────
private val PkgBg         = Color(0xFF1E1E1E)
private val PkgSurface    = Color(0xFF252526)
private val PkgBorder     = Color(0xFF3C3C3C)
private val PkgText       = Color(0xFFD4D4D4)
private val PkgMuted      = Color(0xFF858585)
private val PkgAccent     = Color(0xFF007ACC)
private val PkgGreen      = Color(0xFF4EC994)
private val PkgRed        = Color(0xFFF44747)
private val PkgYellow     = Color(0xFFFFCC00)

// ─── Popular Termux packages (curated list shown before any search) ───────────
private val FEATURED_PACKAGES = listOf(
    PkgInfo("git",          "Distributed version control system",    "vcs"),
    PkgInfo("python",       "Python 3 interpreter",                  "lang"),
    PkgInfo("nodejs",       "JavaScript runtime (Node.js)",          "lang"),
    PkgInfo("curl",         "Command-line HTTP client",              "net"),
    PkgInfo("wget",         "Non-interactive network downloader",    "net"),
    PkgInfo("vim",          "Vi Improved text editor",               "editor"),
    PkgInfo("neovim",       "Hyperextensible Vim-based editor",      "editor"),
    PkgInfo("tmux",         "Terminal multiplexer",                  "util"),
    PkgInfo("htop",         "Interactive process viewer",            "util"),
    PkgInfo("ffmpeg",       "Audio/video converter",                 "media"),
    PkgInfo("imagemagick",  "Image manipulation toolkit",            "media"),
    PkgInfo("openssh",      "SSH client & server",                   "net"),
    PkgInfo("rsync",        "Fast remote file sync",                 "net"),
    PkgInfo("zip",          "Zip compression utility",               "util"),
    PkgInfo("unzip",        "Zip extraction utility",                "util"),
    PkgInfo("jq",           "Lightweight JSON processor",            "util"),
    PkgInfo("ripgrep",      "Fast regex grep replacement",           "search"),
    PkgInfo("fzf",          "Fuzzy finder for the terminal",         "search"),
    PkgInfo("clang",        "C/C++ compiler (LLVM)",                 "lang"),
    PkgInfo("rust",         "Rust programming language toolchain",   "lang"),
    PkgInfo("golang",       "Go programming language",               "lang"),
    PkgInfo("ruby",         "Ruby interpreter",                      "lang"),
    PkgInfo("perl",         "Perl interpreter",                      "lang"),
    PkgInfo("sqlite",       "Self-contained SQL database engine",    "db"),
    PkgInfo("mariadb",      "MySQL-compatible database server",      "db"),
    PkgInfo("redis",        "In-memory data structure store",        "db"),
    PkgInfo("nginx",        "HTTP and reverse proxy server",         "net"),
    PkgInfo("tmate",        "Terminal sharing via SSH",              "net"),
    PkgInfo("nmap",         "Network discovery and security tool",   "net"),
    PkgInfo("gdb",          "GNU debugger",                          "dev"),
    PkgInfo("make",         "Build automation tool",                 "dev"),
    PkgInfo("cmake",        "Cross-platform build system",           "dev"),
    PkgInfo("strace",       "System call tracer",                    "dev"),
    PkgInfo("proot",        "User-space chroot replacement",         "sys"),
    PkgInfo("termux-api",   "Access Android device APIs",            "sys"),
)

data class PkgInfo(val name: String, val description: String, val category: String)

// ─── State for a running install/remove operation ─────────────────────────────
data class PkgOperation(
    val packageName: String,
    val action: String,           // "install" | "remove" | "update" | "upgrade-all"
    val output: MutableList<String> = mutableListOf(),
    var done: Boolean = false,
    var success: Boolean = false,
    @Transient var process: Process? = null,   // held for SIGINT cancel
)

// ─── Install history (SharedPreferences log) ─────────────────────────────────
private const val PREFS_HISTORY = "pkg_install_history"
private const val KEY_HISTORY   = "log"          // newline-delimited  "action|pkg|timestamp"
private const val MAX_HISTORY   = 200

private fun appendHistory(context: Context, action: String, pkg: String, success: Boolean) {
    val prefs = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
    val existing = prefs.getString(KEY_HISTORY, "") ?: ""
    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        .format(java.util.Date())
    val entry = "${if (success) "✓" else "✗"} $action $pkg — $stamp"
    val lines = existing.split("
").filter { it.isNotBlank() }.toMutableList()
    lines.add(0, entry)
    if (lines.size > MAX_HISTORY) lines.subList(MAX_HISTORY, lines.size).clear()
    prefs.edit().putString(KEY_HISTORY, lines.joinToString("
")).apply()
}

private fun loadHistory(context: Context): List<String> {
    val prefs = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_HISTORY, "") ?: ""
    return raw.split("
").filter { it.isNotBlank() }
}

// ─── Main Extensions (Package Manager) panel ─────────────────────────────────
@Composable
internal fun ExtensionsPanel() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var searchQuery      by remember { mutableStateOf("") }
    var installedPkgs    by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searchResults    by remember { mutableStateOf<List<PkgInfo>>(FEATURED_PACKAGES) }
    var activeOperation  by remember { mutableStateOf<PkgOperation?>(null) }
    var showInstalled    by remember { mutableStateOf(false) }
    var lastUpdated      by remember { mutableStateOf("") }
    var errorMsg         by remember { mutableStateOf("") }
    var isSearching      by remember { mutableStateOf(false) }
    var showHistory      by remember { mutableStateOf(false) }
    var installHistory   by remember { mutableStateOf<List<String>>(emptyList()) }

    // ── Helpers ──────────────────────────────────────────────────────────────
    fun runPkg(pkg: String, action: String) {
        if (activeOperation?.done == false) return   // one op at a time
        val op = PkgOperation(pkg, action)
        activeOperation = op
        scope.launch(Dispatchers.IO) {
            try {
                val cmd = when (action) {
                    "install" -> arrayOf("bash", "-c", "apt-get install -y $pkg 2>&1")
                    "remove"  -> arrayOf("bash", "-c", "apt-get remove -y $pkg 2>&1")
                    "update"  -> arrayOf("bash", "-c", "apt-get upgrade -y $pkg 2>&1")
                    else      -> arrayOf("bash", "-c", "apt-get install -y $pkg 2>&1")
                }
                val proc = ProcessBuilder(*cmd)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["DEBIAN_FRONTEND"] = "noninteractive"
                        environment()["PREFIX"] =
                            File(context.filesDir, "termux-prefix").absolutePath
                        environment()["HOME"] =
                            File(context.filesDir, "termux-prefix/home").absolutePath
                    }
                    .start()
                op.process = proc
                scope.launch(Dispatchers.Main) { activeOperation = op.copy() }
                proc.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        op.output.add(line)
                        // refresh state so UI updates
                        scope.launch(Dispatchers.Main) { activeOperation = op.copy() }
                    }
                }
                val exit = proc.waitFor()
                op.success = (exit == 0)
                op.done    = true
                appendHistory(context, action, pkg, op.success)
                if (op.success && action == "install") {
                    installedPkgs = installedPkgs + pkg
                } else if (op.success && action == "remove") {
                    installedPkgs = installedPkgs - pkg
                }
                scope.launch(Dispatchers.Main) {
                    activeOperation  = op.copy()
                    installHistory   = loadHistory(context)
                }
            } catch (e: Exception) {
                op.output.add("Error: ${e.message}")
                op.done    = true
                op.success = false
                scope.launch(Dispatchers.Main) { activeOperation = op.copy() }
            }
        }
    }

    fun upgradeAll() {
        if (activeOperation?.done == false) return
        val op = PkgOperation("(all)", "upgrade-all")
        activeOperation = op
        scope.launch(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder("bash", "-c", "apt-get upgrade -y 2>&1")
                    .redirectErrorStream(true)
                    .apply { environment()["DEBIAN_FRONTEND"] = "noninteractive" }
                    .start()
                op.process = proc
                scope.launch(Dispatchers.Main) { activeOperation = op.copy() }
                proc.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        op.output.add(line)
                        scope.launch(Dispatchers.Main) { activeOperation = op.copy() }
                    }
                }
                val exit = proc.waitFor()
                op.success = (exit == 0)
                op.done    = true
                appendHistory(context, "upgrade-all", "(all)", op.success)
                scope.launch(Dispatchers.Main) {
                    activeOperation = op.copy()
                    loadInstalled()
                    installHistory  = loadHistory(context)
                }
            } catch (e: Exception) {
                op.output.add("Error: ${e.message}")
                op.done    = true
                op.success = false
                scope.launch(Dispatchers.Main) { activeOperation = op.copy() }
            }
        }
    }

    fun loadInstalled() {
        scope.launch(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder("bash", "-c", "dpkg --list 2>/dev/null | awk '/^ii/{print \$2}'")
                    .redirectErrorStream(true).start()
                val pkgs = proc.inputStream.bufferedReader().readLines().toSet()
                proc.waitFor()
                withContext(Dispatchers.Main) { installedPkgs = pkgs }
            } catch (_: Exception) {}
        }
    }

    fun doSearch(q: String) {
        if (q.isBlank()) {
            searchResults = FEATURED_PACKAGES
            return
        }
        isSearching = true
        scope.launch(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder(
                    "bash", "-c",
                    "apt-cache search ${q.trim()} 2>/dev/null | head -40"
                ).redirectErrorStream(true).start()
                val lines = proc.inputStream.bufferedReader().readLines()
                proc.waitFor()
                val results = lines.mapNotNull { line ->
                    val parts = line.split(" - ", limit = 2)
                    if (parts.size == 2) PkgInfo(parts[0].trim(), parts[1].trim(), "search")
                    else null
                }
                withContext(Dispatchers.Main) {
                    searchResults = results.ifEmpty { FEATURED_PACKAGES }
                    isSearching   = false
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

    // Load installed packages + history on first composition
    LaunchedEffect(Unit) {
        loadInstalled()
        installHistory = loadHistory(context)
    }

    // Debounce search
    LaunchedEffect(searchQuery) {
        delay(400)
        doSearch(searchQuery)
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    Column(
        Modifier
            .fillMaxSize()
            .background(PkgBg)
    ) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .background(PkgSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "EXTENSIONS",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = PkgMuted,
                modifier = Modifier.weight(1f)
            )
            // Tab toggle
            TextButton(
                onClick = {
                    showHistory  = false
                    showInstalled = !showInstalled
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Text(
                    if (showInstalled) "Browse" else "Installed",
                    fontSize = 11.sp,
                    color = PkgAccent
                )
            }
            // History toggle
            TextButton(
                onClick = { showHistory = !showHistory; showInstalled = false },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text("History", fontSize = 11.sp, color = if (showHistory) PkgAccent else PkgMuted)
            }
            // Upgrade all
            IconButton(
                onClick = { upgradeAll() },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.SystemUpdate, "Upgrade all packages", tint = PkgMuted, modifier = Modifier.size(16.dp))
            }
            // Refresh package lists
            IconButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        try {
                            ProcessBuilder("bash", "-c", "apt-get update -y 2>&1")
                                .redirectErrorStream(true).start().waitFor()
                        } catch (_: Exception) {}
                    }
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.Refresh, "Update package lists", tint = PkgMuted, modifier = Modifier.size(16.dp))
            }
        }

        if (showHistory) {
            // ── History tab ──────────────────────────────────────────────────
            if (installHistory.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No installation history yet", color = PkgMuted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(installHistory) { entry ->
                        val color = if (entry.startsWith("✓")) PkgGreen else PkgRed
                        Text(
                            entry,
                            fontSize = 11.sp,
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                        HorizontalDivider(color = PkgBorder, thickness = 0.5.dp)
                    }
                }
            }
        } else if (!showInstalled) {
            // Search bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(PkgSurface, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null, tint = PkgMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(color = PkgText, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(PkgAccent),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (searchQuery.isEmpty()) Text("Search packages (apt-cache)…", fontSize = 13.sp, color = PkgMuted)
                        inner()
                    }
                )
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = PkgAccent)
                }
            }

            // Package list
            LazyColumn(Modifier.weight(1f)) {
                items(searchResults, key = { it.name }) { pkg ->
                    val isInstalled = pkg.name in installedPkgs
                    val isActive    = activeOperation?.packageName == pkg.name && activeOperation?.done == false
                    PkgRow(
                        pkg         = pkg,
                        isInstalled = isInstalled,
                        isBusy      = isActive,
                        onInstall   = { runPkg(pkg.name, "install") },
                        onRemove    = { runPkg(pkg.name, "remove") },
                    )
                    HorizontalDivider(color = PkgBorder, thickness = 0.5.dp)
                }
            }
        } else {
            // Installed packages list
            if (installedPkgs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No packages detected", color = PkgMuted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    val sorted = installedPkgs.sorted()
                    items(sorted, key = { it }) { name ->
                        val isActive = activeOperation?.packageName == name && activeOperation?.done == false
                        val pkg = FEATURED_PACKAGES.firstOrNull { it.name == name }
                            ?: PkgInfo(name, "Installed package", "installed")
                        PkgRow(
                            pkg         = pkg,
                            isInstalled = true,
                            isBusy      = isActive,
                            onInstall   = {},
                            onRemove    = { runPkg(name, "remove") },
                        )
                        HorizontalDivider(color = PkgBorder, thickness = 0.5.dp)
                    }
                }
            }
        }

        // Operation output terminal strip
        val op = activeOperation
        if (op != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .background(Color(0xFF0D0D0D))
                    .padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when {
                        !op.done    -> PkgYellow
                        op.success  -> PkgGreen
                        else        -> PkgRed
                    }
                    Box(Modifier.size(8.dp).background(statusColor, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${op.action.uppercase()} ${op.packageName}" +
                        if (op.done) (if (op.success) " — done" else " — failed") else "…",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                        modifier = Modifier.weight(1f)
                    )
                    if (!op.done) {
                        // Cancel — send SIGINT to the running process
                        TextButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    try { activeOperation?.process?.destroy() } catch (_: Exception) {}
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text("Cancel", fontSize = 11.sp, color = PkgRed)
                        }
                    } else {
                        IconButton(onClick = { activeOperation = null }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, null, tint = PkgMuted, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                val scroll = rememberScrollState(Int.MAX_VALUE)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                ) {
                    op.output.takeLast(60).forEach { line ->
                        Text(line, fontSize = 10.sp, color = PkgMuted, fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}

// ─── Single package row ───────────────────────────────────────────────────────
@Composable
private fun PkgRow(
    pkg: PkgInfo,
    isInstalled: Boolean,
    isBusy: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon letter badge
        Box(
            Modifier
                .size(32.dp)
                .background(PkgSurface, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                pkg.name.take(1).uppercase(),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = PkgAccent
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(pkg.name, fontSize = 13.sp, color = PkgText, fontWeight = FontWeight.Medium)
            Text(pkg.description, fontSize = 11.sp, color = PkgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (isBusy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = PkgAccent)
        } else if (isInstalled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓", fontSize = 11.sp, color = PkgGreen)
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = onRemove,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text("Remove", fontSize = 11.sp, color = PkgRed)
                }
            }
        } else {
            TextButton(
                onClick = onInstall,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Text("Install", fontSize = 11.sp, color = PkgAccent)
            }
        }
    }
}

// ─── MCP Panel (below Extensions in the sidebar) ─────────────────────────────
@Composable
internal fun McpPanel() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var serverRunning  by remember { mutableStateOf(false) }
    var toolCount      by remember { mutableStateOf(0) }
    var agentApiUrl    by remember { mutableStateOf("http://localhost:8765") }
    var bashrcInstalled by remember { mutableStateOf(false) }

    // Poll server status every 5s
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                try {
                    val conn = java.net.URL("$agentApiUrl/health").openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 1000
                    conn.readTimeout    = 1000
                    val ok = conn.responseCode == 200
                    conn.disconnect()
                    withContext(Dispatchers.Main) { serverRunning = ok }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) { serverRunning = false }
                }
                // Count tools from agent tools file
                try {
                    val agentJson = File(context.filesDir, "termux-prefix/root/.agent.json")
                    if (agentJson.exists()) {
                        val text = agentJson.readText()
                        val count = text.split("\"name\"").size - 1
                        withContext(Dispatchers.Main) { toolCount = count }
                    }
                } catch (_: Exception) {}
                // Check if bashrc has profile injected
                try {
                    val bashrc = File(context.filesDir, "termux-prefix/root/.bashrc")
                    val has = bashrc.exists() && bashrc.readText().contains("AGENT_API_URL")
                    withContext(Dispatchers.Main) { bashrcInstalled = has }
                } catch (_: Exception) {}
            }
            delay(5000)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(PkgSurface)
            .padding(12.dp)
    ) {
        // Section title
        Text(
            "MCP / AGENT TOOLS",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = PkgMuted,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Server status row
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (serverRunning) PkgGreen else PkgRed,
                        RoundedCornerShape(4.dp)
                    )
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (serverRunning) "Agent API running" else "Agent API offline",
                    fontSize = 12.sp,
                    color = if (serverRunning) PkgGreen else PkgMuted
                )
                Text(agentApiUrl, fontSize = 10.sp, color = PkgMuted, fontFamily = FontFamily.Monospace)
            }
            // Start / restart button
            TextButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        try {
                            McpShellProfile.install(context)
                        } catch (_: Exception) {}
                    }
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Text(if (serverRunning) "Restart" else "Start", fontSize = 11.sp, color = PkgAccent)
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = PkgBorder, thickness = 0.5.dp)
        Spacer(Modifier.height(8.dp))

        // Stats row
        Row(Modifier.fillMaxWidth()) {
            McpStat(label = "Tools", value = if (toolCount > 0) toolCount.toString() else "—")
            McpStat(label = "Shell profile", value = if (bashrcInstalled) "installed" else "missing")
            McpStat(label = "Port", value = "8765")
        }

        Spacer(Modifier.height(8.dp))

        // Quick-install shell profile button
        if (!bashrcInstalled) {
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        try { McpShellProfile.install(context) } catch (_: Exception) {}
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp),
                shape = RoundedCornerShape(4.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = SolidColor(PkgAccent)
                )
            ) {
                Icon(Icons.Default.Terminal, null, tint = PkgAccent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Install shell profile", fontSize = 12.sp, color = PkgAccent)
            }
        }

        // Tool chips (quick reference)
        Spacer(Modifier.height(6.dp))
        val quickTools = listOf("agent_read", "agent_write", "agent_run", "agent_git", "agent_search")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            quickTools.forEach { tool ->
                Text(
                    tool,
                    fontSize = 9.sp,
                    color = PkgAccent,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(Color(0xFF0D2137), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun McpStat(label: String, value: String) {
    Column(
        Modifier.padding(end = 16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PkgText)
        Text(label, fontSize = 10.sp, color = PkgMuted)
    }
}
