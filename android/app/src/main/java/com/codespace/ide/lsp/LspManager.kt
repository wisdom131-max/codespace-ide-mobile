package com.codespace.ide.lsp

import android.content.Context
import android.util.Log
import com.codespace.ide.diagnostics.AppOutputLog
import com.codespace.ide.data.NotificationStore
import com.codespace.ide.diagnostics.DiagnosticManager
import com.codespace.ide.diagnostics.DiagnosticConverter
import com.codespace.ide.domain.Language
import com.codespace.ide.editor.DiagnosticsSource
import com.codespace.ide.editor.ProjectSettingsStore
import com.codespace.ide.editor.TypeScriptVersion
import com.codespace.ide.terminal.ProotInstaller
import com.codespace.ide.environment.IdeEnvironment
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * P24: LSP Code Action — a quick fix or refactoring suggestion returned by the language server.
 */
data class LspCodeAction(
    val title: String,
    val kind: String? = null,
    val edit: String? = null,
    val command: String? = null,
    // P39: extended fields for VS Code-parity code action menu
    val isPreferred: Boolean = false,
    val disabled: String? = null,
    val data: String? = null,
    val diagnostics: String? = null,
)


/**
 * Phase V-A: LSP Server Lifecycle State Machine.
 *
 * Authoritative lifecycle state — replaces scattered booleans.
 * Transitions:
 *   STOPPED → STARTING → INITIALIZING → READY
 *   READY → UNHEALTHY → RESTARTING → STARTING → ...
 *   any → STOPPING → STOPPED
 *   READY → IDLE_CLOSE → STOPPING → STOPPED  (idle timeout, not a crash)
 */
enum class LspState {
    STOPPED,
    STARTING,
    INITIALIZING,
    READY,
    UNHEALTHY,
    RESTARTING,
    STOPPING,
    IDLE_CLOSE,
}

/**
 * Phase V-M: Server generation ID — incremented on every server start.
 * Prevents stale callbacks from a dead server instance from affecting the new one.
 */
data class ServerGeneration(val language: Language, val generation: Int)

/**
 * Phase V-D: Tracked open document for workspace recovery after restart.
 * Stores everything needed to re-open a document after a server crash.
 */
data class TrackedDocument(
    val uri: String,
    val languageId: String,
    val content: String,
    val version: Int,
)

/**
 * Phase V-E: Memory snapshot from /proc/<pid>/status.
 */
data class MemorySnapshot(
    val vmRssKb: Long,
    val vmSizeKb: Long,
    val vmPeakKb: Long,
    val state: MemoryState,
)

enum class MemoryState { NORMAL, WARNING, CRITICAL }

/**
 * Phase V-C: Restart backoff state.
 */
data class RestartBackoff(
    val language: Language,
    val consecutiveRestarts: Int = 0,
    val lastRestartTime: Long = 0L,
) {
    companion object {
        const val MAX_RESTARTS = 5
        // Backoff: 1s, 2s, 5s, 15s, 30s → circuit breaker
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 15_000, 30_000)
    }

    fun nextDelayMs(): Long {
        val idx = consecutiveRestarts.coerceAtMost(BACKOFF_MS.size - 1)
        return BACKOFF_MS[idx]
    }

    fun canRestart(): Boolean = consecutiveRestarts < MAX_RESTARTS

    fun increment(): RestartBackoff = copy(
        consecutiveRestarts = consecutiveRestarts + 1,
        lastRestartTime = System.currentTimeMillis()
    )

    fun reset(): RestartBackoff = copy(
        consecutiveRestarts = 0,
        lastRestartTime = 0L
    )
}

/**
 * LspManager - manages LSP server lifecycle and provides LSP operations.
 *
 * P22-F: LSP groundwork - LspManager, stdio JSON-RPC client, server install via npm/pip in proot.
 *
 * Supported language servers (auto-detected by file extension, auto-installed on first use):
 * - TypeScript/JavaScript: typescript-language-server --stdio  (npm)
 * - Python:                pylsp                               (pip3)
 * - Kotlin:                kotlin-language-server              (curl+unzip)
 * - Go:                    gopls                               (go install)
 * - Java:                  jdtls (eclipse.jdt.ls)             (apt + curl)
 * - C / C++:               clangd                             (apt)
 * - Rust:                  rust-analyzer                      (rustup)
 * - PHP:                   intelephense                       (npm)
 * - HTML:                  vscode-html-language-server        (npm)
 * - CSS:                   vscode-css-language-server         (npm)
 * - JSON:                  vscode-json-language-server        (npm)
 *
 * All servers run inside the Ubuntu proot rootfs and communicate via JSON-RPC 2.0 over stdio.
 *
 * BUG-FIX (2026-07-17): startServer no longer kills a healthy server when a 2nd file
 * of the same language is opened. It only restarts if the existing process is dead.
 *
 * BUG-FIX (2026-07-17): initialize now declares full client capabilities so servers
 * know to push diagnostics, completions, hover, and signatureHelp.
 *
 * BUG-FIX (2026-07-17): workspaceFolders included in initialize so Kotlin/Python/Java
 * LSPs can index cross-file symbols.
 *
 * BUG-FIX (2026-07-17): startServer guards against proot rootfs not being set up yet.
 *
 * CRITICAL FIX (2026-07-17): typescript pinned to 5.6.3 — typescript@7.x (latest on
 * npm) ships ONLY tsc.js and no longer includes tsserver.js / tsserverlibrary.js.
 * typescript-language-server requires tsserver.js at runtime and fails with
 * "Could not find a valid TypeScript installation" when typescript@7.x is installed.
 * Fix: npm install -g typescript-language-server typescript@5.6.3
 * Confirmed: typescript@5.6.3 ships tsserver.js + tsserverlibrary.js in lib/.
 *
 * CRITICAL FIX (2026-07-17): checkCommand for TS/JS now validates tsserver.js
 * presence (node -e "require.resolve('typescript/lib/tsserver')") not just the binary.
 * This ensures a broken install (binary present, tsserver.js missing from @7.x) is
 * correctly detected as "not installed" and triggers a repair install.
 *
 * FIX (2026-07-17): HTML/CSS servers updated to vscode-langservers-extracted.
 * vscode-html-languageserver and vscode-css-languageserver are deprecated packages.
 * The maintained replacement is vscode-langservers-extracted which ships
 * vscode-html-language-server, vscode-css-language-server, etc.
 */
object LspManager {

    private const val TAG = "LspManager"

    data class ServerConfig(
        val language: Language,
        val command: String,
        val args: List<String>,
        val checkCommand: String,
        val installCommand: String,
        val installTimeout: Long = 120,
    )


    // TS7 fallback: vtsls — LSP wrapper around the VSCode TypeScript extension.
    // vtsls bundles TypeScript 5.9.3 (hard dependency in @vtsls/language-service 0.3.0).
    // It requires tsserver.js — so it CANNOT use TypeScript 7 (which dropped tsserver.js).
    // Correct npm package: @vtsls/language-server (NOT "vtsls" — that package doesn't exist).
    // Binary name after install: vtsls
    private val vtslsConfig = ServerConfig(
        Language.TYPESCRIPT,
        "vtsls",
        listOf("--stdio"),
        // Check: vtsls binary exists (npm global install)
        "which vtsls && echo OK",
        // Install: NodeSource setup + npm install @vtsls/language-server
        // @vtsls/language-server 0.3.0 bundles TypeScript 5.9.3 as a hard dependency.
        // We do NOT install typescript@7 here — vtsls can't use it (no tsserver.js in TS7).
        "[ -f /usr/lib/libdpkg_android_fix.so ] && export LD_PRELOAD=/usr/lib/libdpkg_android_fix.so; " +
            "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
            "/var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null; " +
            "dpkg --configure -a 2>/dev/null; " +
            "( command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 ) || " +
            "( apt-get install -f -y 2>/dev/null; " +
            "apt-get remove --purge nodejs npm -y 2>/dev/null; " +
            "apt-get autoremove -y 2>/dev/null; " +
            "( command -v curl >/dev/null 2>&1 || apt-get install -y curl 2>/dev/null ) && " +
            "curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && " +
            "apt-get install -y nodejs ); " +
            "npm config set prefix /usr/local 2>/dev/null; " +
            "npm install -g @vtsls/language-server",
        300,
    )

    // TS7 native: TypeScript 7's built-in native LSP server (Go binary).
    // TS7 is a Go rewrite — it ships its own LSP server via `tsc --lsp --stdio`.
    // No tsserver.js, no vtsls, no typescript-language-server needed.
    // Source: github.com/microsoft/typescript-go/blob/main/cmd/tsgo/main.go
    // The --lsp flag is handled in main.go; lsp.go confirms --stdio is the only transport.
    // TS7 native LSP supports: completion, hover, diagnostics, definition, references,
    // rename, code actions, signature help, document/workspace symbols, formatting,
    // code lenses, call hierarchy, selection ranges, auto-imports, quick fixes.
    private val ts7NativeConfig = ServerConfig(
        Language.TYPESCRIPT,
        "tsc",
        listOf("--lsp", "--stdio"),
        // Check: tsc exists AND reports version 7.x (only TS7+ has --lsp support)
        "tsc --version 2>/dev/null | head -1 | grep -q 'Version 7' && echo OK",
        // Install: just install typescript@7 — the tsc binary includes the native LSP server
        "[ -f /usr/lib/libdpkg_android_fix.so ] && export LD_PRELOAD=/usr/lib/libdpkg_android_fix.so; " +
            "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
            "/var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null; " +
            "dpkg --configure -a 2>/dev/null; " +
            "( command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 ) || " +
            "( apt-get install -f -y 2>/dev/null; " +
            "apt-get remove --purge nodejs npm -y 2>/dev/null; " +
            "apt-get autoremove -y 2>/dev/null; " +
            "( command -v curl >/dev/null 2>&1 || apt-get install -y curl 2>/dev/null ) && " +
            "curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && " +
            "apt-get install -y nodejs ); " +
            "npm config set prefix /usr/local 2>/dev/null; " +
            "npm install -g typescript@7",
        300,
    )

    private val configs: Map<Language, ServerConfig> = mapOf(
        // ── JavaScript / TypeScript ──────────────────────────────────────────
        // ── JavaScript / TypeScript ──────────────────────────────────────────
        // CRITICAL FIX: Pin typescript@5.6.3 — typescript@7.x (latest as of 2026-07)
        // ships ONLY tsc.js (compiler CLI) and no longer includes tsserver.js or
        // tsserverlibrary.js. typescript-language-server requires both files at runtime
        // and fails with "Could not find a valid TypeScript installation" without them.
        // typescript@5.6.3 is the last 5.x release confirmed to include tsserver.js.
        //
        // checkCommand also validates tsserver.js presence (not just the binary) so a
        // broken install (binary exists, tsserver.js missing) triggers a repair install.
        Language.TYPESCRIPT to ServerConfig(
            Language.TYPESCRIPT,
            "typescript-language-server",
            listOf("--stdio"),
            // Verify both the binary AND tsserver.js exist — if tsserver.js is missing
            // (e.g. due to a previous unversioned typescript@7.x install), treat as not installed.
            "which typescript-language-server && " +
                // P31-LSP-FIX: Check BOTH /usr/local and /usr prefixes — apt npm uses /usr,
                // npm install -g may go to either depending on npm config.
                "( test -f /usr/local/lib/node_modules/typescript/lib/tsserver.js || " +
                "  test -f /usr/lib/node_modules/typescript/lib/tsserver.js ) && echo OK",
            // LSP-FIX: Replace broken Ubuntu apt nodejs/npm with NodeSource.
            // Root cause: Ubuntu apt's nodejs has libnode115 dependency conflict —
            // a previous failed apt install leaves broken packages that block ALL
            // future npm installs. NodeSource provides clean Node 20.x with npm
            // bundled, bypassing the broken apt package state entirely.
            // Steps: (1) clear dpkg locks, (2) dpkg --configure -a,
            // (3) apt-get install -f (fix broken), (4) purge broken nodejs,
            // (5) autoremove orphans, (6) NodeSource setup_20.x + apt install nodejs,
            // (7) npm install language server.
            // installTimeout 300s: full chain needs ~200-250s on Android proot.
            "[ -f /usr/lib/libdpkg_android_fix.so ] && export LD_PRELOAD=/usr/lib/libdpkg_android_fix.so; " +
                "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
                "/var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null; " +
                "dpkg --configure -a 2>/dev/null; " +
                "( command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 ) || " +
                "( apt-get install -f -y 2>/dev/null; " +
                "apt-get remove --purge nodejs npm -y 2>/dev/null; " +
                "apt-get autoremove -y 2>/dev/null; " +
                "( command -v curl >/dev/null 2>&1 || apt-get install -y curl 2>/dev/null ) && " +
                "curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && " +
                "apt-get install -y nodejs ); " +
                "npm config set prefix /usr/local 2>/dev/null; " +
                "npm install -g typescript-language-server typescript@5.6.3",
            300,
        ),
        Language.JAVASCRIPT to ServerConfig(
            Language.JAVASCRIPT,
            "typescript-language-server",
            listOf("--stdio"),
            // Same check as TypeScript — both use typescript-language-server + tsserver.js.
            "which typescript-language-server && " +
                // P31-LSP-FIX: Check BOTH /usr/local and /usr prefixes — apt npm uses /usr,
                // npm install -g may go to either depending on npm config.
                "( test -f /usr/local/lib/node_modules/typescript/lib/tsserver.js || " +
                "  test -f /usr/lib/node_modules/typescript/lib/tsserver.js ) && echo OK",
            // LSP-FIX: Same NodeSource install + 300s timeout as TS.
            "[ -f /usr/lib/libdpkg_android_fix.so ] && export LD_PRELOAD=/usr/lib/libdpkg_android_fix.so; " +
                "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
                "/var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null; " +
                "dpkg --configure -a 2>/dev/null; " +
                "( command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 ) || " +
                "( apt-get install -f -y 2>/dev/null; " +
                "apt-get remove --purge nodejs npm -y 2>/dev/null; " +
                "apt-get autoremove -y 2>/dev/null; " +
                "( command -v curl >/dev/null 2>&1 || apt-get install -y curl 2>/dev/null ) && " +
                "curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && " +
                "apt-get install -y nodejs ); " +
                "npm config set prefix /usr/local 2>/dev/null; " +
                "npm install -g typescript-language-server typescript@5.6.3",
            300,
        ),
        // ── Python ─────────────────────────────────────────────────────────
        Language.PYTHON to ServerConfig(
            Language.PYTHON,
            "pylsp",
            emptyList(),
            "which pylsp && echo OK",
            // P31-LSP-FIX: Clear stale dpkg locks + skip apt-get if pip3 already present.
            "[ -f /usr/lib/libdpkg_android_fix.so ] && export LD_PRELOAD=/usr/lib/libdpkg_android_fix.so; " +
                "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
                "/var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null; " +
                "dpkg --configure -a 2>/dev/null; " +
                "command -v pip3 >/dev/null 2>&1 || " +
                "( apt-get update -qq && apt-get install -y --no-install-recommends python3-pip ); " +
                "pip3 install --break-system-packages 'python-lsp-server[all]' || " +
                "pip3 install --break-system-packages python-lsp-server; " +
                // Auto-install pylsp-inlay-hints (archived but functional). Non-fatal:
                // if pip/network fails, hasCapability() gate handles it gracefully.
                "pip3 install --break-system-packages pylsp-inlay-hints 2>/dev/null; " +
                // P50-3: pylsp-workspace-symbols plugin — adds workspace/symbol support via Jedi.
                // Auto-advertises workspaceSymbolProvider via pylsp_experimental_capabilities.
                "pip3 install --break-system-packages pylsp-workspace-symbols 2>/dev/null; " +
                "command -v pylsp >/dev/null 2>&1 && python3 -c 'import pylsp_workspace_symbols' 2>/dev/null && echo 'pylsp-workspace-symbols OK' || echo 'pylsp-workspace-symbols not found'",
            240,
        ),
        // ── Kotlin ─────────────────────────────────────────────────────────
        // LSP-FIX (2026-08-12): server.zip's actual layout puts the binary under a
        // top-level `server/` folder — server/bin/kotlin-language-server — NOT directly
        // at bin/kotlin-language-server as the symlink previously assumed (confirmed by
        // extracting the real release archive: `unzip -l` lists server/bin/kotlin-language-server).
        // The old command created a dangling symlink: curl/unzip/ln/rm/echo all reported
        // success (ln -sf doesn't verify its target exists), so the install looked green
        // in the log, but `which kotlin-language-server` correctly failed afterward because
        // the symlink pointed nowhere. Fixed path + added an explicit `test -f` guard so a
        // bad extract fails loudly instead of silently linking to nothing.
        Language.KOTLIN to ServerConfig(
            Language.KOTLIN,
            "kotlin-language-server",
            emptyList(),
            "which kotlin-language-server && echo OK",
            // R3-KLSP-STDLIB: Install command now also downloads kotlin-stdlib-1.9.22.jar
            // from Maven Central to /opt/kotlin-stdlib/ so loose .kt files (no build.gradle)
            // get basic stdlib completions (listOf, println, map, etc.) via the global
            // kls-classpath mechanism. The stdlib download is best-effort — if it fails
            // (no network), the LSP server still installs and works for Gradle projects.
            // ensureKotlinStdlib() also re-downloads this jar if it goes missing later.
            "dpkg --configure -a 2>/dev/null; apt-get update -qq; apt-get install -y --no-install-recommends default-jre-headless unzip curl; " +
                "curl -fsSL https://github.com/fwcd/kotlin-language-server/releases/download/1.3.13/server.zip -o /tmp/kls.zip && " +
                "unzip -o /tmp/kls.zip -d /opt/kotlin-language-server >/dev/null 2>&1 && " +
                "test -f /opt/kotlin-language-server/server/bin/kotlin-language-server && " +
                "chmod +x /opt/kotlin-language-server/server/bin/kotlin-language-server && " +
                "ln -sf /opt/kotlin-language-server/server/bin/kotlin-language-server /usr/local/bin/kotlin-language-server && " +
                "rm -f /tmp/kls.zip && " +
                "(mkdir -p /opt/kotlin-stdlib && " +
                "curl -fsSL https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.22/kotlin-stdlib-1.9.22.jar -o /opt/kotlin-stdlib/kotlin-stdlib.jar && " +
                "echo Kotlin-stdlib-installed || echo Kotlin-stdlib-download-failed-non-fatal) ; " +
                "(curl -fsSL https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/1.7.25/slf4j-simple-1.7.25.jar -o /opt/kotlin-language-server/server/lib/slf4j-simple-1.7.25.jar && " +
                "sed -i 's|slf4j-api-1.7.25.jar|slf4j-api-1.7.25.jar:\$APP_HOME/lib/slf4j-simple-1.7.25.jar|' /opt/kotlin-language-server/server/bin/kotlin-language-server && " +
                "echo SLF4J-simple-installed || echo SLF4J-simple-failed-non-fatal) ; " +
                "echo Kotlin-LSP-installed",
            300,
        ),
        // ── Go ─────────────────────────────────────────────────────────────
        Language.GO to ServerConfig(
            Language.GO,
            "gopls",
            emptyList(),
            "which gopls && echo OK",
            "dpkg --configure -a 2>/dev/null; apt-get update -qq; apt-get install -y --no-install-recommends golang-go; go install golang.org/x/tools/gopls@latest",
        ),
        // ── Java ───────────────────────────────────────────────────────────
        // Uses eclipse.jdt.ls (jdtls). Lighter than IntelliJ, runs on JRE 17+.
        Language.JAVA to ServerConfig(
            Language.JAVA,
            "/opt/jdtls/bin/jdtls",
            listOf("-data", "/tmp/jdtls-workspace"),
            "test -f /opt/jdtls/bin/jdtls && echo found",
            "dpkg --configure -a 2>/dev/null; apt-get update -qq; apt-get install -y --no-install-recommends default-jre-headless curl unzip; " +
                "mkdir -p /opt/jdtls && " +
                "curl -fsSL https://download.eclipse.org/jdtls/milestones/1.9.0/jdt-language-server-1.9.0-202203031534.tar.gz | tar -xz -C /opt/jdtls && " +
                "chmod +x /opt/jdtls/bin/jdtls && echo jdtls-installed",
            300,
        ),
        // ── C / C++ ────────────────────────────────────────────────────────
        // clangd is in the Ubuntu apt repos — simple install, great LSP.
        Language.C to ServerConfig(
            Language.C,
            "clangd",
            listOf("--background-index", "--clang-tidy"),
            "which clangd && echo OK",
            // P31-LSP-FIX: Clear stale dpkg locks before apt-get.
            "[ -f /usr/lib/libdpkg_android_fix.so ] && export LD_PRELOAD=/usr/lib/libdpkg_android_fix.so; " +
                "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
                "/var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null; " +
                "dpkg --configure -a 2>/dev/null; " +
                "apt-get update -qq && apt-get install -y --no-install-recommends clangd",
            180,
        ),
        Language.CPP to ServerConfig(
            Language.CPP,
            "clangd",
            listOf("--background-index", "--clang-tidy"),
            "which clangd && echo OK",
            "[ -f /usr/lib/libdpkg_android_fix.so ] && export LD_PRELOAD=/usr/lib/libdpkg_android_fix.so; " +
                "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
                "/var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null; " +
                "dpkg --configure -a 2>/dev/null; " +
                "apt-get update -qq && apt-get install -y --no-install-recommends clangd",
            180,
        ),
        // ── Rust ───────────────────────────────────────────────────────────
        Language.RUST to ServerConfig(
            Language.RUST,
            "rust-analyzer",
            emptyList(),
            "which rust-analyzer && echo OK",
            "apt-get update -qq; apt-get install -y --no-install-recommends curl; " +
                "curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable; " +
                "source \$HOME/.cargo/env; " +
                "rustup component add rust-analyzer || " +
                "curl -fsSL https://github.com/rust-lang/rust-analyzer/releases/download/2024-04-21/rust-analyzer-aarch64-unknown-linux-gnu.gz | gunzip -c > /usr/local/bin/rust-analyzer && " +
                "chmod +x /usr/local/bin/rust-analyzer && echo rust-analyzer-installed",
            300,
        ),
        // ── PHP ────────────────────────────────────────────────────────────
        Language.PHP to ServerConfig(
            Language.PHP,
            "intelephense",
            listOf("--stdio"),
            "which intelephense && echo OK",
            // LSP-FIX: NodeSource-based install — bypasses broken apt nodejs (libnode115 conflict).
            "[ -f /usr/lib/libdpkg_android_fix.so ] && export LD_PRELOAD=/usr/lib/libdpkg_android_fix.so; " +
                "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
                "/var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null; " +
                "dpkg --configure -a 2>/dev/null; " +
                "( command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 ) || " +
                "( apt-get install -f -y 2>/dev/null; " +
                "apt-get remove --purge nodejs npm -y 2>/dev/null; " +
                "apt-get autoremove -y 2>/dev/null; " +
                "( command -v curl >/dev/null 2>&1 || apt-get install -y curl 2>/dev/null ) && " +
                "curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && " +
                "apt-get install -y nodejs ); " +
                "npm config set prefix /usr/local 2>/dev/null; " +
                "npm install -g intelephense",
            240,
        ),
        // ── HTML ───────────────────────────────────────────────────────────
        // ── HTML ───────────────────────────────────────────────────────────
        // FIX: vscode-html-languageserver is deprecated. The maintained replacement is
        // vscode-langservers-extracted which ships html, css, json, and eslint servers.
        Language.HTML to ServerConfig(
            Language.HTML,
            "vscode-html-language-server",
            listOf("--stdio"),
            "which vscode-html-language-server && echo OK",
            // LSP-FIX: NodeSource-based install — bypasses broken apt nodejs (libnode115 conflict).
            "[ -f /usr/lib/libdpkg_android_fix.so ] && export LD_PRELOAD=/usr/lib/libdpkg_android_fix.so; " +
                "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
                "/var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null; " +
                "dpkg --configure -a 2>/dev/null; " +
                "( command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 ) || " +
                "( apt-get install -f -y 2>/dev/null; " +
                "apt-get remove --purge nodejs npm -y 2>/dev/null; " +
                "apt-get autoremove -y 2>/dev/null; " +
                "( command -v curl >/dev/null 2>&1 || apt-get install -y curl 2>/dev/null ) && " +
                "curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && " +
                "apt-get install -y nodejs ); " +
                "npm config set prefix /usr/local 2>/dev/null; " +
                "npm install -g vscode-langservers-extracted",
            240,
        ),
        // ── CSS ────────────────────────────────────────────────────────────
        // FIX: vscode-css-languageserver is deprecated — covered by vscode-langservers-extracted.
        Language.CSS to ServerConfig(
            Language.CSS,
            "vscode-css-language-server",
            listOf("--stdio"),
            "which vscode-css-language-server && echo OK",
            // LSP-FIX: NodeSource-based install — bypasses broken apt nodejs (libnode115 conflict).
            "[ -f /usr/lib/libdpkg_android_fix.so ] && export LD_PRELOAD=/usr/lib/libdpkg_android_fix.so; " +
                "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
                "/var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null; " +
                "dpkg --configure -a 2>/dev/null; " +
                "( command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 ) || " +
                "( apt-get install -f -y 2>/dev/null; " +
                "apt-get remove --purge nodejs npm -y 2>/dev/null; " +
                "apt-get autoremove -y 2>/dev/null; " +
                "( command -v curl >/dev/null 2>&1 || apt-get install -y curl 2>/dev/null ) && " +
                "curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && " +
                "apt-get install -y nodejs ); " +
                "npm config set prefix /usr/local 2>/dev/null; " +
                "npm install -g vscode-langservers-extracted",
            240,
        ),
        // ── JSON ───────────────────────────────────────────────────────────
        // vscode-langservers-extracted also ships vscode-json-language-server.
        // Since HTML/CSS already install it, JSON LSP is essentially free.
        Language.JSON to ServerConfig(
            Language.JSON,
            "vscode-json-language-server",
            listOf("--stdio"),
            "which vscode-json-language-server && echo OK",
            // LSP-FIX: NodeSource-based install — bypasses broken apt nodejs (libnode115 conflict).
            "[ -f /usr/lib/libdpkg_android_fix.so ] && export LD_PRELOAD=/usr/lib/libdpkg_android_fix.so; " +
                "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
                "/var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null; " +
                "dpkg --configure -a 2>/dev/null; " +
                "( command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 ) || " +
                "( apt-get install -f -y 2>/dev/null; " +
                "apt-get remove --purge nodejs npm -y 2>/dev/null; " +
                "apt-get autoremove -y 2>/dev/null; " +
                "( command -v curl >/dev/null 2>&1 || apt-get install -y curl 2>/dev/null ) && " +
                "curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && " +
                "apt-get install -y nodejs ); " +
                "npm config set prefix /usr/local 2>/dev/null; " +
                "npm install -g vscode-langservers-extracted",
            240,
        ),
        // ── Ruby ──────────────────────────────────────────────────────────
        // solargraph — Ruby language server (completion, diagnostics, formatting).
        Language.RUBY to ServerConfig(
            Language.RUBY,
            "solargraph",
            listOf("stdio"),
            "which solargraph && echo OK",
            "dpkg --configure -a 2>/dev/null; " +
                "( command -v ruby >/dev/null 2>&1 && command -v gem >/dev/null 2>&1 ) || " +
                "( apt-get update -qq && apt-get install -y --no-install-recommends ruby ruby-dev ); " +
                "gem install solargraph",
            240,
        ),
        // ── C# ────────────────────────────────────────────────────────────
        // OmniSharp-Roslyn — C# language server.
        Language.CSHARP to ServerConfig(
            Language.CSHARP,
            "OmniSharp",
            listOf("-stdio", "-loglevel", "warning"),
            "which OmniSharp && echo OK || test -f /opt/omnisharp/OmniSharp && echo OK",
            "dpkg --configure -a 2>/dev/null; apt-get update -qq; " +
                "apt-get install -y --no-install-recommends unzip curl ca-certificates; " +
                "mkdir -p /opt/omnisharp && " +
                "curl -fsSL 'https://github.com/OmniSharp/omnisharp-roslyn/releases/download/v1.39.11/omnisharp-linux-arm64.tar.gz' | tar -xz -C /opt/omnisharp && " +
                "chmod +x /opt/omnisharp/OmniSharp && ln -sf /opt/omnisharp/OmniSharp /usr/local/bin/OmniSharp && " +
                "echo 'OmniSharp-installed'",
            300,
        ),
        // ── Lua ───────────────────────────────────────────────────────────
        // lua-language-server (sumneko) — completion, diagnostics, formatting.
        Language.LUA to ServerConfig(
            Language.LUA,
            "lua-language-server",
            listOf("--stdio"),
            "which lua-language-server && echo OK",
            "dpkg --configure -a 2>/dev/null; apt-get update -qq; " +
                "apt-get install -y --no-install-recommends unzip curl ca-certificates; " +
                "curl -fsSL 'https://github.com/LuaLS/lua-language-server/releases/download/3.13.5/lua-language-server-3.13.5-linux-arm64.tar.gz' | tar -xz -C /opt && " +
                "ln -sf /opt/lua-language-server/bin/lua-language-server /usr/local/bin/lua-language-server && " +
                "echo 'lua-ls-installed'",
            180,
        ),
        // ── Dart ──────────────────────────────────────────────────────────
        // dart language-server — ships with the Dart SDK.
        Language.DART to ServerConfig(
            Language.DART,
            "dart",
            listOf("language-server", "--stdio"),
            "which dart && dart --version 2>/dev/null && echo OK",
            "dpkg --configure -a 2>/dev/null; apt-get update -qq; " +
                "apt-get install -y --no-install-recommends curl unzip; " +
                "curl -fsSL 'https://storage.googleapis.com/dart-archive/channels/stable/release/3.5.0/sdk/dartsdk-linux-arm64-release.zip' -o /tmp/dart-sdk.zip && " +
                "unzip -q -o /tmp/dart-sdk.zip -d /opt && " +
                "ln -sf /opt/dart-sdk/bin/dart /usr/local/bin/dart && " +
                "rm -f /tmp/dart-sdk.zip && echo 'dart-sdk-installed'",
            300,
        ),
        // ── SQL ───────────────────────────────────────────────────────────
        // sql-language-server — npm-based SQL completion + diagnostics.
        Language.SQL to ServerConfig(
            Language.SQL,
            "sql-language-server",
            listOf("up", "--method", "stdio"),
            "which sql-language-server && echo OK",
            "[ -f /usr/lib/libdpkg_android_fix.so ] && export LD_PRELOAD=/usr/lib/libdpkg_android_fix.so; " +
                "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
                "/var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null; " +
                "dpkg --configure -a 2>/dev/null; " +
                "( command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 ) || " +
                "( apt-get install -f -y 2>/dev/null; " +
                "apt-get remove --purge nodejs npm -y 2>/dev/null; " +
                "apt-get autoremove -y 2>/dev/null; " +
                "( command -v curl >/dev/null 2>&1 || apt-get install -y curl 2>/dev/null ) && " +
                "curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && " +
                "apt-get install -y nodejs ); " +
                "npm config set prefix /usr/local 2>/dev/null; " +
                "npm install -g sql-language-server",
            240,
        ),
        // ── PowerShell ─────────────────────────────────────────────────────
        // PowerShell Editor Services — Microsoft's LSP for PowerShell.
        Language.POWERSHELL to ServerConfig(
            Language.POWERSHELL,
            "pwsh",
            listOf("-NoLogo", "-NoProfile", "-Command",
                "/opt/powershell-editor-services/PowerShellEditorServices/Start-EditorServices.ps1 -Stdio"),
            "which pwsh && test -d /opt/powershell-editor-services && echo OK",
            "dpkg --configure -a 2>/dev/null; apt-get update -qq; " +
                "apt-get install -y --no-install-recommends curl unzip libssl-dev; " +
                "curl -fsSL 'https://github.com/PowerShell/PowerShell/releases/download/v7.4.6/powershell-7.4.6-linux-arm64.tar.gz' | tar -xz -C /opt/pwsh && " +
                "ln -sf /opt/pwsh/pwsh /usr/local/bin/pwsh && " +
                "curl -fsSL 'https://github.com/PowerShell/PowerShellEditorServices/releases/download/v4.0.0/PowerShellEditorServices.zip' -o /tmp/pes.zip && " +
                "unzip -q -o /tmp/pes.zip -d /opt/powershell-editor-services && " +
                "rm -f /tmp/pes.zip && echo 'powershell-ls-installed'",
            300,
        ),
        // ── Scala ─────────────────────────────────────────────────────────
        // metals — Scala language server (requires Java).
        Language.SCALA to ServerConfig(
            Language.SCALA,
            "metals",
            listOf("-Dmetals.client=emacs", "-XX:+UseG1GC", "-XX:+UseStringDeduplication"),
            "which metals && echo OK",
            "dpkg --configure -a 2>/dev/null; apt-get update -qq; " +
                "apt-get install -y --no-install-recommends default-jre-headless curl; " +
                "curl -fsSL -o /usr/local/bin/metals 'https://github.com/scalameta/metals/releases/download/v1.4.0/metals-linux-arm64' && " +
                "chmod +x /usr/local/bin/metals && echo 'metals-installed'",
            300,
        ),
        // ── R ──────────────────────────────────────────────────────────────
        // languageserver — R LSP package.
        Language.R to ServerConfig(
            Language.R,
            "R",
            listOf("--slave", "-e", "languageserver::run()"),
            "which R && R -e 'cat(system.file(package=languageserver, mustWork=TRUE))' 2>/dev/null && echo OK",
            "dpkg --configure -a 2>/dev/null; apt-get update -qq; " +
                "apt-get install -y --no-install-recommends r-base r-base-dev; " +
                "R -e 'install.packages(\"languageserver\", repos=\"https://cloud.r-project.org\")'",
            300,
        ),
        // ── Swift ─────────────────────────────────────────────────────────
        // sourcekit-lsp — Swift language server (ships with Swift toolchain).
        Language.SWIFT to ServerConfig(
            Language.SWIFT,
            "sourcekit-lsp",
            listOf("--stdio"),
            "which sourcekit-lsp && echo OK",
            "dpkg --configure -a 2>/dev/null; apt-get update -qq; " +
                "apt-get install -y --no-install-recommends curl tar ca-certificates; " +
                "curl -fsSL 'https://download.swift.org/swift-5.10.1-release/ubuntu2404/swift-5.10.1-RELEASE/swift-5.10.1-RELEASE-ubuntu24.04-aarch64.tar.gz' | tar -xz -C /opt && " +
                "ln -sf /opt/swift-5.10.1-RELEASE-ubuntu24.04-aarch64/usr/bin/sourcekit-lsp /usr/local/bin/sourcekit-lsp && " +
                "echo 'sourcekit-lsp-installed'",
            300,
        ),
        // P50-3: ctags-lsp — universal symbol search + go-to-definition for 100+ languages.
        // Uses universal-ctags as backend. Installed via go install (Go already present for gopls).
        // Needs universal-ctags runtime dependency (apt-get install universal-ctags).
        // We use a dummy Language entry since ctags-lsp isn't tied to a specific language.
    )

    // Running servers: language -> LspServer
    private val servers = ConcurrentHashMap<Language, LspServer>()

    // Auto-close: track last activity per server, shut down after 10s idle
    private val lastActivity = ConcurrentHashMap<Language, AtomicLong>()
    private val autoCloseExecutor = Executors.newSingleThreadScheduledExecutor()
    @Volatile var autoCloseEnabled = true
    private var autoCloseScheduled = false

    // Phase V-A: Server state per language — authoritative lifecycle
    private val serverStates = ConcurrentHashMap<Language, LspState>()

    // R3-LSP: Recovery counter — incremented each time a server transitions TO READY
    // from a non-READY state (restart/recovery). CodeEditor observes this to reset
    // the completion fallback flag so LSP is retried first after recovery.
    @Volatile
    var lspRecoveryCounter: Int = 0
        private set

    // Phase V-M: Generation counter per language — incremented on every server start
    private val generationCounters = ConcurrentHashMap<Language, Int>()

    // Phase V-C: Restart backoff per language
    private val restartBackoffs = ConcurrentHashMap<Language, RestartBackoff>()

    // Phase V-B: Process exit monitor threads
    private val processMonitors = ConcurrentHashMap<Language, Thread>()

    // Phase V-E: Memory monitor executor
    // CRASH-FIX: was `val` — after shutdownNow() on teardown, startServer() would
    // call scheduleAtFixedRate on the dead executor → RejectedExecutionException.
    // Now recreated fresh in ensureMemoryMonitorStarted() if terminated.
    private var memoryMonitorExecutor = Executors.newSingleThreadScheduledExecutor()
    private var memoryMonitorScheduled = false

    // Phase V-I: Configurable idle timeout (seconds). 0 = never auto-close.
    @Volatile var idleTimeoutSeconds: Long = 300_000L // default 300s (5 min)

    // Phase V-G: Health check executor
    private var healthCheckExecutor = Executors.newSingleThreadScheduledExecutor()
    private var healthCheckScheduled = false

    // Phase V-N: Lifecycle log tag
    private const val LSP_LOG_TAG = "[LSP]"

    // Phase V-A: Get the authoritative state of a server
    fun getServerState(language: Language): LspState =
        serverStates[language] ?: LspState.STOPPED

    // Phase V-A: Set server state with structured logging (Section N)
    private fun setServerState(language: Language, newState: LspState, extra: String = "") {
        val oldState = serverStates[language] ?: LspState.STOPPED
        serverStates[language] = newState
        // R3-LSP: Increment recovery counter when server transitions TO READY from a
        // non-READY state (e.g., UNHEALTHY → READY after restart). This lets CodeEditor
        // reset its completion fallback flag so the next request tries LSP first again.
        if (oldState != LspState.READY && newState == LspState.READY) {
            lspRecoveryCounter++
            AppOutputLog.log("$LSP_LOG_TAG ${language.displayName} reconnected, recovery counter: $lspRecoveryCounter", "lsp")
        }
        if (oldState != newState) {
            val server = servers[language]
            val gen = server?.generation ?: 0
            val pid = server?.let { getProcessPid(it.process) }?.toString() ?: "N/A"
            val restartCount = restartBackoffs[language]?.consecutiveRestarts ?: 0
            val memInfo = server?.memorySnapshot?.let { "VmRSS=${it.vmRssKb}kB" } ?: ""
            val extraStr = if (extra.isNotEmpty()) " $extra" else ""
            lifecycleLog("STATE ${oldState}→${newState} lang=${language.displayName} gen=$gen pid=$pid restarts=$restartCount $memInfo$extraStr")
        }
    }

    // Phase V-N: Structured lifecycle log
    private fun lifecycleLog(event: String) {
        val msg = "$LSP_LOG_TAG $event"
        Log.d(TAG, msg)
        AppOutputLog.log(msg, "lsp")
    }

    // Phase N: LSP lifecycle notifications — push meaningful events to NotificationStore
    private fun notifyLspEvent(language: Language, event: String, severity: NotificationStore.Severity, body: String, actions: List<NotificationStore.NotificationAction> = emptyList()) {
        val dedupKey = "lsp:${language.name}:$event"
        NotificationStore.add(
            title = "${language.displayName} language server",
            body = body,
            severity = severity,
            source = NotificationStore.Source.LSP,
            priority = when (severity) {
                NotificationStore.Severity.ERROR -> NotificationStore.Priority.HIGH
                NotificationStore.Severity.WARNING -> NotificationStore.Priority.NORMAL
                NotificationStore.Severity.PROGRESS -> NotificationStore.Priority.NORMAL
                else -> NotificationStore.Priority.LOW
            },
            actions = actions,
            deduplicationKey = dedupKey,
            groupKey = "lsp-${language.name}",
            category = event,
        )
    }

    // Phase V: Get PID of a child Process on Android (Process.pid() is Java 9, not available on Android)
    private fun getProcessPid(process: Process): Long {
        return try {
            var cls: Class<*>? = process.javaClass
            while (cls != null) {
                try {
                    val f = cls.getDeclaredField("pid")
                    f.isAccessible = true
                    return (f.get(process) as? Int)?.toLong() ?: -1L
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                }
            }
            -1L
        } catch (e: Exception) {
            -1L
        }
    }

    // P50-3: ctags-lsp as secondary server for workspace/symbol fallback.
    // Runs alongside primary servers. When a primary server doesn't support
    // workspace/symbol, we route the request to ctags-lsp instead.
    private var ctagsServer: LspServer? = null
    private var ctagsInstallChecked = false

    // Diagnostics handlers: language -> (uri, diagnostics) -> Unit
    private val diagnosticsHandlers = ConcurrentHashMap<Language, (String, JSONArray) -> Unit>()

    class LspServer(
        val language: Language,
        val process: Process,
        val client: JsonRpcClient,
        val rootUri: String,
        val command: String = "",
        val generation: Int = 0,
    ) {
        @Volatile var initialized = false
        @Volatile var capabilities: JSONObject? = null
        val diagnostics = ConcurrentHashMap<String, JSONArray>()

        // Phase V-A: Authoritative lifecycle state
        @Volatile var state: LspState = LspState.STARTING

        // Phase V-D: Tracked open documents for workspace recovery
        val trackedDocuments = ConcurrentHashMap<String, TrackedDocument>()

        // Phase V-E: Last memory snapshot
        @Volatile var memorySnapshot: MemorySnapshot? = null

        // Phase V-F: Exit code from last process death
        @Volatile var lastExitCode: Int = -1

        // Phase V-F: Whether last death was a crash (vs intentional shutdown)
        @Volatile var lastDeathWasCrash: Boolean = false
    }

    // ── Server lifecycle ───────────────────────────────────────────

    fun isSupported(language: Language): Boolean = configs.containsKey(language)

    fun isServerRunning(language: Language): Boolean =
        servers[language]?.let { it.process.isAlive } ?: false

    /**
     * Phase V-M: Get the current server generation for a language.
     * Used for two-level stale response rejection — capture before an
     * async LSP request, compare after the response arrives. If the
     * server restarted (new generation), the response is stale.
     */
    fun getServerGeneration(language: Language): Int = generationCounters[language] ?: 0

    /**
     * Get the current document version for a URI (tracked via didOpen/didChange).
     * Used for two-level stale response rejection — capture before an async LSP
     * request, compare after the response arrives. If the user edited the
     * document (new didChange with new version), the response is stale.
     */
    fun getDocumentVersion(language: Language, uri: String): Int =
        servers[language]?.trackedDocuments?.get(uri)?.version ?: 0

    /** Touch activity timestamp — called on any editor interaction. */
    private fun touchActivity(language: Language) {
        lastActivity[language]?.set(System.currentTimeMillis())
    }

    /**
     * Start the idle auto-close checker (called once on first server start).
     * Phase V-I: Uses configurable idleTimeoutSeconds instead of hardcoded 10s.
     */
    private fun ensureAutoCloseStarted() {
        if (autoCloseScheduled) return
        autoCloseScheduled = true
        autoCloseExecutor.scheduleAtFixedRate({
            if (!autoCloseEnabled) return@scheduleAtFixedRate
            // Phase V-I: 0 means never auto-close
            if (idleTimeoutSeconds == 0L) return@scheduleAtFixedRate
            val now = System.currentTimeMillis()
            lastActivity.entries.forEach { (lang, ts) ->
                if (now - ts.get() > idleTimeoutSeconds) {
                    val server = servers[lang]
                    if (server != null && server.process.isAlive) {
                        // Phase V-I: Idle close is NOT a crash — no restart, no backoff
                        setServerState(lang, LspState.IDLE_CLOSE, "idle ${idleTimeoutSeconds / 1000}s")
                        lifecycleLog("IDLE_CLOSE lang=${lang.displayName} gen=${server.generation} — idle ${idleTimeoutSeconds / 1000}s")
            notifyLspEvent(lang, "idle_close", NotificationStore.Severity.INFO, "Server idle for ${idleTimeoutSeconds / 1000}s — shutting down to save memory.")
                        stopServer(lang)
                        lastActivity.remove(lang)
                    }
                }
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    /**
     * Check if the LSP server has completed the initialize handshake.
     * isServerRunning=true but isServerInitialized=false means the server
     * is starting up but not ready for requests yet.
     */
    fun isServerInitialized(language: Language): Boolean =
        servers[language]?.let { it.process.isAlive && it.initialized } ?: false

    /** P41-W: Get the server's capabilities object (for semantic token legend, etc.) */
    fun getServerCapabilities(language: Language): JSONObject? =
        servers[language]?.capabilities

    /**
     * P38-FIX: Check if the server advertises a specific capability.
     * Used to gate optional LSP requests (workspace/symbol, inlayHint, etc.)
     * so we don't send unsupported methods that crash servers like pylsp.
     */
    fun hasCapability(language: Language, capabilityPath: String): Boolean {
        val server = servers[language] ?: return false
        val caps = server.capabilities ?: return false
        var current: Any? = caps
        for (part in capabilityPath.split(".")) {
            current = when (current) {
                is org.json.JSONObject -> current.opt(part)
                else -> null
            }
            if (current == null) return false
        }
        return when (current) {
            is Boolean -> current
            is org.json.JSONObject -> true
            else -> false
        }
    }

    fun supportsWorkspaceSymbols(language: Language): Boolean =
        hasCapability(language, "workspaceSymbolProvider")

    /**
     * Check if the server advertises completionItem/resolve support.
     * Servers with resolveProvider=false (or no resolveProvider field) will
     * throw NotImplementedError if we send completionItem/resolve — which
     * can crash the server and eat the completion timeout budget.
     */
    fun supportsCompletionResolve(language: Language): Boolean {
        val server = servers[language] ?: return false
        val compCaps = server.capabilities?.optJSONObject("completionProvider") ?: return false
        return compCaps.optBoolean("resolveProvider", false)
    }

    /**
     * Check if the LSP server binary is installed in the proot rootfs.
     */
    fun isServerInstalled(context: Context, language: Language, resolvedConfig: ServerConfig? = null): Boolean {
        // LSP-FIX (2026-08-12): [resolvedConfig] lets a caller that already
        // determined the EFFECTIVE config (e.g. startServer's TS7-native-vs-vtsls
        // runtime check) force this function to check that exact config, instead
        // of re-deriving it here from typescriptVersion alone. Without this, this
        // function always assumed ts7NativeConfig whenever the TS7 setting was on
        // even when startServer had already decided to fall back to vtsls — so it
        // checked/installed the WRONG server (typescript@7 native) while startServer
        // went on to spawn a DIFFERENT one (vtsls) that was never installed, causing
        // "exec: vtsls: not found".
        if (resolvedConfig != null) {
            AppOutputLog.log("[LSP] Checking if ${language.displayName} server installed: ${resolvedConfig.checkCommand}", "lsp")
            val output = ProotInstaller.execOnce(context, resolvedConfig.checkCommand, timeoutSeconds = 20)
            val lastLine = output.trimEnd().lines().lastOrNull().orEmpty().trim()
            val installed = lastLine == "OK" || lastLine == "found" ||
                (lastLine.startsWith("/") && !lastLine.contains("not found") && !lastLine.contains("no "))
            AppOutputLog.log("[LSP] Install check result for ${language.displayName}: $installed (lastLine='$lastLine')", "lsp")
            return installed
        }
        var config = configs[language] ?: return false
        // P-PYRIGHT: Use Pyright config if Python + diagnostics source is PYRIGHT
        if (language == Language.PYTHON && ProjectSettingsStore.diagnosticsSource.value == DiagnosticsSource.PYRIGHT) {
            config = pyrightConfig
        }
        // TS7: Check the effective config (native TS7 or vtsls fallback)
        if ((language == Language.TYPESCRIPT || language == Language.JAVASCRIPT) &&
            ProjectSettingsStore.typescriptVersion.value == TypeScriptVersion.TS7) {
            config = ts7NativeConfig
        }
        AppOutputLog.log("[LSP] Checking if ${language.displayName} server installed: ${config.checkCommand}", "lsp")
        // FIX: increased timeout from 10→20s — proot bash login can take >10s on cold start.
        val output = ProotInstaller.execOnce(context, config.checkCommand, timeoutSeconds = 20)
        // FIX: check LAST LINE of output only, not substring of the whole string.
        //
        // Previous substring check (output.contains("OK")) was a false positive bug:
        // when execOnce times out it returns "Timed out after Ns running: <command>", and
        // since all our check commands end with "echo OK", the timed-out string itself
        // contains "OK" as a substring, so isServerInstalled() returned true even though
        // the check never completed, which skipped install and caused binary-not-found crash.
        //
        // Correct logic: the check command exits 0 and prints "OK" (or "found" for
        // test-based checks like jdtls) as its FINAL LINE. On failure, execOnce returns
        // "Exit code N" or "Timed out ...". Neither ends with "OK" or "found".
        val lastLine = output.trimEnd().lines().lastOrNull().orEmpty().trim()
        val installed = lastLine == "OK" || lastLine == "found" ||
            // Defensive: a bare `which X` that succeeds outputs a path like /usr/local/bin/pylsp.
            // Accept any absolute path as proof the binary is installed.
            (lastLine.startsWith("/") && !lastLine.contains("not found") && !lastLine.contains("no "))
        Log.d(TAG, "isServerInstalled(${language.displayName}): lastLine='$lastLine' installed=$installed (raw: ${output.take(80)})")
        AppOutputLog.log("[LSP] Install check result for ${language.displayName}: $installed (lastLine='$lastLine')", "lsp")
        return installed
    }

    /**
     * Install the LSP server binary in the proot rootfs via npm/pip/apt.
     * Returns the installation output.
     */
    /**
     * Install (or repair) the LSP server.
     *
     * FIX: Previously skipped install if the binary existed via `which` alone.
     * For TypeScript, the binary existing is NOT sufficient — tsserver.js must also
     * be resolvable. The checkCommand now validates both, so a broken existing install
     * (typescript@7.x: binary present, tsserver.js absent) correctly falls through
     * to the install command here and runs `npm install -g typescript@5.6.3` to repair.
     */
    fun installServer(context: Context, language: Language, resolvedConfig: ServerConfig? = null): String {
        var config = resolvedConfig ?: (configs[language] ?: return "No LSP server configured for ${language.displayName}")
        if (resolvedConfig == null) {
            // P-PYRIGHT: Use Pyright config if Python + diagnostics source is PYRIGHT
            if (language == Language.PYTHON && ProjectSettingsStore.diagnosticsSource.value == DiagnosticsSource.PYRIGHT) {
                config = pyrightConfig
            }
            // TS7: Install the effective config (native TS7 or vtsls fallback)
            if ((language == Language.TYPESCRIPT || language == Language.JAVASCRIPT) &&
                ProjectSettingsStore.typescriptVersion.value == TypeScriptVersion.TS7) {
                config = ts7NativeConfig
            }
        }
        if (isServerInstalled(context, language, resolvedConfig = config)) {
            AppOutputLog.log("[LSP] ${language.displayName} install check PASSED (binary + runtime files present) — skipping install", "lsp")
            return "${language.displayName} LSP server already installed"
        }
        AppOutputLog.log("[LSP] ${language.displayName} install check FAILED (binary missing or runtime files broken) — running install/repair", "lsp")
        Log.d(TAG, "Installing LSP server for ${language.displayName}...")
        AppOutputLog.log("[LSP] Installing ${language.displayName} server (timeout: ${config.installTimeout}s) — this may take 1-2 minutes…", "lsp")
        // P-NOTIFY: Verbose download notification — show detailed progress in output log if enabled
        if (ProjectSettingsStore.verboseDownloadNotify.value) {
            AppOutputLog.log("[LSP] [Verbose] Install command: ${config.installCommand.take(300)}", "lsp")
        }
        val installOutput = ProotInstaller.execOnce(context, config.installCommand, timeoutSeconds = config.installTimeout, logToOutput = true)
        AppOutputLog.log("[LSP] Install output for ${language.displayName}: ${installOutput.take(200).trim()}", "lsp")
        // P-NOTIFY: Task completion notification — fire system notification if threshold allows
        notifyTaskComplete(context, "${language.displayName} LSP server installed")
        return installOutput
    }

    /**
     * P-NOTIFY: Fire a system notification when a task completes, respecting the
     * task notification threshold from In-Project Settings.
     * - threshold -1: never notify
     * - threshold 0: always notify
     * - threshold N: notify only if task took > N ms (caller passes elapsed time or 0 for instant)
     */
    private fun notifyTaskComplete(context: Context, message: String, elapsedMs: Long = 0) {
        try {
            val threshold = ProjectSettingsStore.taskNotifyThresholdMs.value
            if (threshold == -1) return  // never
            if (threshold > 0 && elapsedMs < threshold) return  // under threshold
            val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "task_complete"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(channelId, "Task Complete", android.app.NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(channel)
            }
            val notif = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setContentTitle("VN Code")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
            nm.notify(System.currentTimeMillis().toInt(), notif)
        } catch (_: Exception) {
            // Notification failed — don't crash the app
        }
    }

    /**
     * Start an LSP server for the given language and workspace.
     * Automatically installs the server if not present.
     * Returns true if the server started and initialized successfully.
     *
     * BUG-FIX: If a server is already running and healthy for this language,
     * reuse it instead of killing and restarting. Only kill dead processes.
     */

    /** P-PYRIGHT: Pyright language server config — Microsoft's Node.js-based Python LSP. */
    /**
     * Returns the effective ServerConfig for a language, considering In-Project Settings.
     * TS7 selection: tries native TS7 LSP first, falls back to vtsls if unavailable.
     * This is used by startServer, isServerInstalled, and installServer for consistency.
     */
    private fun effectiveConfig(language: Language): ServerConfig? {
        var config = configs[language] ?: return null
        if (language == Language.PYTHON && ProjectSettingsStore.diagnosticsSource.value == DiagnosticsSource.PYRIGHT) {
            config = pyrightConfig
        }
        if ((language == Language.TYPESCRIPT || language == Language.JAVASCRIPT) &&
            ProjectSettingsStore.typescriptVersion.value == TypeScriptVersion.TS7) {
            // TS7 native is preferred — but we can't check availability here (no Context).
            // startServer handles the check and fallback. isServerInstalled/installServer
            // use this function and will naturally check both configs.
            config = ts7NativeConfig
        }
        return config
    }

    private val pyrightConfig = ServerConfig(
        Language.PYTHON,
        "pyright-langserver",
        listOf("--stdio"),
        // Check: node + pyright-langserver must be present
        "which pyright-langserver && echo OK",
        // Install: pyright is an npm package — requires Node.js (already installed for tsserver)
        "[ -f /usr/lib/libdpkg_android_fix.so ] && export LD_PRELOAD=/usr/lib/libdpkg_android_fix.so; " +
            "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
            "/var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null; " +
            "dpkg --configure -a 2>/dev/null; " +
            "( command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 ) || " +
            "( apt-get install -f -y 2>/dev/null; " +
            "apt-get remove --purge nodejs npm -y 2>/dev/null; " +
            "apt-get autoremove -y 2>/dev/null; " +
            "( command -v curl >/dev/null 2>&1 || apt-get install -y curl 2>/dev/null ) && " +
            "curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && " +
            "apt-get install -y nodejs ); " +
            "npm config set prefix /usr/local 2>/dev/null; " +
            "npm install -g pyright",
        300,
    )

    /**
     * R3-KLSP-STDLIB: Ensures the Kotlin stdlib JAR and global classpath script exist
     * before starting the Kotlin LSP server.
     *
     * This is called automatically from startServer() for Kotlin, right before the
     * server process is spawned — not just during initial install. This handles:
     * - Fresh install (stdlib downloaded during installServer)
     * - Re-download if the JAR was deleted or storage was cleared
     * - Creating the conditional classpath.sh script if missing or stale
     *
     * The classpath script outputs the stdlib JAR path ONLY when no build.gradle
     * exists in the workspace — otherwise it outputs nothing, letting the LSP server's
     * built-in Gradle/Maven resolver handle per-project classpath normally.
     *
     * All operations are non-fatal: if the download fails (no network), the LSP server
     * still starts — it just won't have stdlib completions for loose files until the
     * next successful download.
     */
    private fun ensureKotlinStdlib(context: Context, workspaceGuestPath: String = "/root"): Boolean {
        val stdlibJarPath = "/opt/kotlin-stdlib/kotlin-stdlib.jar"
        val classpathScriptPath = "~/.config/kotlin-language-server/classpath.sh"

        // Check if stdlib JAR exists in the proot rootfs
        val stdlibCheck = ProotInstaller.execOnce(context,
            "test -f $stdlibJarPath && echo EXISTS || echo MISSING",
            timeoutSeconds = 10)
        val stdlibPresent = stdlibCheck.trimEnd().lines().lastOrNull().orEmpty().trim() == "EXISTS"

        if (!stdlibPresent) {
            AppOutputLog.log("[LSP-DIAG] Kotlin stdlib JAR missing at $stdlibJarPath — attempting re-download", "lsp")
            // Re-download from Maven Central (best-effort, non-fatal)
            val downloadResult = ProotInstaller.execOnce(context,
                "mkdir -p /opt/kotlin-stdlib && " +
                "curl -fsSL --connect-timeout 10 --max-time 30 " +
                "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.22/kotlin-stdlib-1.9.22.jar " +
                "-o /opt/kotlin-stdlib/kotlin-stdlib.jar && echo DOWNLOADED || echo DOWNLOAD_FAILED",
                timeoutSeconds = 45)
            val downloadOk = downloadResult.trimEnd().lines().lastOrNull().orEmpty().trim() == "DOWNLOADED"
            if (downloadOk) {
                AppOutputLog.log("[LSP-DIAG] Kotlin stdlib JAR re-downloaded successfully", "lsp")
            } else {
                AppOutputLog.log("[LSP-DIAG] Kotlin stdlib JAR re-download FAILED (non-fatal) — loose .kt files won't get stdlib completions until next successful download", "lsp")
            }
        } else {
            AppOutputLog.log("[LSP-DIAG] Kotlin stdlib JAR present at $stdlibJarPath", "lsp")
        }

        // R3-KLSP-SLF4J: Ensure slf4j-simple is installed and patched into the
        // launch script. Without this, the server SLF4J defaults to NOP and ALL
        // internal LOG.info() calls are silently dropped — we cannot see
        // "Adding N files to class path", symbol index counts, or exceptions.
        val slf4jResult = ProotInstaller.execOnce(context,
            "[ -f /opt/kotlin-language-server/server/lib/slf4j-simple-1.7.25.jar ] && " +
            "grep -q slf4j-simple /opt/kotlin-language-server/server/bin/kotlin-language-server && " +
            "echo SLF4J_OK || " +
            "(curl -fsSL --connect-timeout 10 --max-time 30 " +
            "https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/1.7.25/slf4j-simple-1.7.25.jar " +
            "-o /opt/kotlin-language-server/server/lib/slf4j-simple-1.7.25.jar && " +
            "sed -i 's|slf4j-api-1.7.25.jar|slf4j-api-1.7.25.jar:\$APP_HOME/lib/slf4j-simple-1.7.25.jar|' " +
            "/opt/kotlin-language-server/server/bin/kotlin-language-server && " +
            "echo SLF4J_PATCHED || echo SLF4J_PATCH_FAILED)",
            timeoutSeconds = 45)
        val slf4jStatus = slf4jResult.trimEnd().lines().lastOrNull().orEmpty().trim()
        AppOutputLog.log("[LSP-DIAG] SLF4J binding: $slf4jStatus", "lsp")

        // Ensure the global classpath.sh script exists and is up-to-date.
        // The script is idempotent — we overwrite it every time to ensure it's current.
        // Using printf (not heredoc) to avoid raw newline issues in the patch pipeline.
        // Write the script via proot — using printf to avoid heredoc/newline issues
        val writeScriptCmd = "mkdir -p ~/.config/kotlin-language-server && " +
            "printf '%s\\n' '#!/bin/bash' " +
            "'# R3-KLSP-STDLIB: Global classpath for kotlin-language-server' " +
            "'# Outputs stdlib JAR ONLY for projects without build files.' " +
            "'if [ -f build.gradle ] || [ -f build.gradle.kts ] || [ -f settings.gradle ] || [ -f settings.gradle.kts ]; then' " +
            "'  exit 0' " +
            "'fi' " +
            "'if [ -f /opt/kotlin-stdlib/kotlin-stdlib.jar ]; then' " +
            "'  echo /opt/kotlin-stdlib/kotlin-stdlib.jar' " +
            "'fi' > ~/.config/kotlin-language-server/classpath.sh && " +
            "chmod +x ~/.config/kotlin-language-server/classpath.sh && " +
            "echo CLASSPATH_SCRIPT_OK || echo CLASSPATH_SCRIPT_FAILED"

        val scriptResult = ProotInstaller.execOnce(context, writeScriptCmd, timeoutSeconds = 10)
        val scriptOk = scriptResult.trimEnd().lines().lastOrNull().orEmpty().trim() == "CLASSPATH_SCRIPT_OK"
        if (scriptOk) {
            AppOutputLog.log("[LSP-DIAG] Kotlin classpath.sh created/updated at $classpathScriptPath", "lsp")
        } else {
            AppOutputLog.log("[LSP-DIAG] Failed to create classpath.sh (non-fatal): $scriptResult", "lsp")
        }

        // R3-KLSP-DIAG: Comprehensive environment diagnostics — log exactly what the
        // Kotlin LSP server process will see when it starts. This runs in the same
        // proot environment with the same profile sourcing as the server.
        val diagCmd = "source /etc/profile >/dev/null 2>&1; source ~/.bashrc >/dev/null 2>&1; " +
            "echo HOME=\${HOME}; " +
            "echo XDG_CONFIG_HOME=\${XDG_CONFIG_HOME}; " +
            "echo USER_HOME_JAVA=\$(java -XshowSettings:properties -version 2>&1 | grep user.home || echo java-not-found); " +
            "echo CLASSPATH_SCRIPT_EXISTS=\$([ -f ~/.config/kotlin-language-server/classpath.sh ] && echo YES || echo NO); " +
            "echo CLASSPATH_SCRIPT_PATH=\$(realpath ~/.config/kotlin-language-server/classpath.sh 2>/dev/null || echo N/A); " +
            "echo STDLIB_JAR_EXISTS=\$([ -f /opt/kotlin-stdlib/kotlin-stdlib.jar ] && echo YES || echo NO); " +
            "echo STDLIB_JAR_SIZE=\$(stat -c%s /opt/kotlin-stdlib/kotlin-stdlib.jar 2>/dev/null || echo N/A); " +
            "echo WORKDIR_BUILD_GRADLE_ROOT=\$([ -f build.gradle ] && echo YES || echo NO); " +
            "echo WORKDIR_BUILD_GRADLE_KTS_ROOT=\$([ -f build.gradle.kts ] && echo YES || echo NO); " +
            "echo WORKDIR_GUEST_PATH=$workspaceGuestPath; " +
            "echo WORKDIR_BUILD_GRADLE_GUEST=\$([ -f \"$workspaceGuestPath/build.gradle\" ] && echo YES || echo NO); " +
            "echo WORKDIR_BUILD_GRADLE_KTS_GUEST=\$([ -f \"$workspaceGuestPath/build.gradle.kts\" ] && echo YES || echo NO); " +
            "echo CLASSPATH_SCRIPT_OUTPUT_ROOT=\$(cd /root && ~/.config/kotlin-language-server/classpath.sh 2>&1 || echo SCRIPT_FAILED_EXIT=\$?); " +
            "echo CLASSPATH_SCRIPT_OUTPUT_GUEST=\$(cd \"$workspaceGuestPath\" 2>&1 && ~/.config/kotlin-language-server/classpath.sh 2>&1 || echo SCRIPT_FAILED_EXIT=\$?); " +
            "echo CLASSPATH_GUEST_CD_OK=\$(cd \"$workspaceGuestPath\" 2>/dev/null && echo YES || echo NO); " +
            "echo CLASSPATH_GUEST_DIR_EXISTS=\$([ -d \"$workspaceGuestPath\" ] && echo YES || echo NO); " +
            "echo STDLIB_JAR_VALID=\$(unzip -t /opt/kotlin-stdlib/kotlin-stdlib.jar 2>&1 | tail -1); " +
            "echo STDLIB_JAR_ENTRIES=\$(unzip -l /opt/kotlin-stdlib/kotlin-stdlib.jar 2>/dev/null | tail -1); " +
            "echo SLF4J_SIMPLE_EXISTS=\$([ -f /opt/kotlin-language-server/server/lib/slf4j-simple-1.7.25.jar ] && echo YES || echo NO); " +
            "echo SLF4J_IN_CLASSPATH=\$(grep -c slf4j-simple /opt/kotlin-language-server/server/bin/kotlin-language-server 2>/dev/null || echo 0)"
        val diagResult = ProotInstaller.execOnce(context, diagCmd, timeoutSeconds = 15)
        for (line in diagResult.trimEnd().lines()) {
            if (line.isNotBlank()) {
                AppOutputLog.log("[LSP-DIAG] $line", "lsp")
            }
        }

        // Final verification
        val finalCheck = ProotInstaller.execOnce(context,
            "test -f $stdlibJarPath && echo STDLIB_OK || echo STDLIB_MISSING",
            timeoutSeconds = 10)
        val stdlibReady = finalCheck.trimEnd().lines().lastOrNull().orEmpty().trim() == "STDLIB_OK"
        AppOutputLog.log("[LSP-DIAG] ensureKotlinStdlib result: stdlibReady=$stdlibReady, scriptReady=$scriptOk", "lsp")
        return stdlibReady && scriptOk
    }

    fun startServer(context: Context, language: Language, workspacePath: String): Boolean {
        // Master LSP toggle — when disabled, skip all LSP servers, use fallback completions only
        if (!ProjectSettingsStore.lspEnabled.value) {
            AppOutputLog.log("[LSP] LSP servers disabled in In-Project Settings — skipping startServer for ${language.displayName}", "lsp")
            return false
        }
        // P-PYRIGHT: If Python and diagnostics source is set to Pyright, use that config
        var config = configs[language] ?: run {
            AppOutputLog.log("[LSP] No server config for ${language.displayName} — language not supported", "lsp")
            return false
        }
        if (language == Language.PYTHON && ProjectSettingsStore.diagnosticsSource.value == DiagnosticsSource.PYRIGHT) {
            config = pyrightConfig
            AppOutputLog.log("[LSP] Using Pyright (Microsoft) instead of pylsp for Python — per In-Project Settings", "lsp")
        }
        // TS7: Try native TS7 LSP server first, fall back to vtsls if unavailable.
        // TypeScript 7 ships a native Go LSP server via `tsc --lsp --stdio`.
        // vtsls bundles TS 5.9.3 and is the fallback (it cannot use TS7).
        if ((language == Language.TYPESCRIPT || language == Language.JAVASCRIPT) &&
            ProjectSettingsStore.typescriptVersion.value == TypeScriptVersion.TS7) {
            // Check if TS7 native LSP is available (tsc exists and reports version 7.x)
            val ts7Check = ProotInstaller.execOnce(context,
                "tsc --version 2>/dev/null | head -1 | grep -q 'Version 7' && echo OK",
                timeoutSeconds = 15)
            val ts7Available = ts7Check.trimEnd().lines().lastOrNull().orEmpty().trim() == "OK"
            if (ts7Available) {
                config = ts7NativeConfig
                AppOutputLog.log("[LSP] Using TypeScript 7 native LSP (tsc --lsp --stdio) — per In-Project Settings", "lsp")
            } else {
                config = vtslsConfig
                AppOutputLog.log("[LSP] TS7 native LSP unavailable — falling back to vtsls + TypeScript 5.9.3", "lsp")
            }
        }
        Log.d(TAG, "startServer: BEGIN for ${language.displayName} workspace=$workspacePath")
        AppOutputLog.log("[LSP] startServer BEGIN: ${language.displayName} workspace=$workspacePath", "lsp")

        // Self-heal: ensure libdpkg_android_fix.so is present in the guest rootfs before
        // any apt-get/npm/pip install runs. Without this shim, dpkg's link() calls for
        // status-file backups fail with EACCES on Android, breaking every apt-get install.
        // This is a no-op if the .so is already present and matches the source size.
        ProotInstaller.ensureShimInstalled(context)

        // BUG-FIX: Don't kill a healthy server just because a 2nd file of the same
        // language was opened. Only stop if the process has already died.
        val existing = servers[language]
        if (existing != null && existing.process.isAlive && existing.initialized) {
            setServerState(language, LspState.READY, "reuse existing")
            AppOutputLog.log("[LSP] ${language.displayName} server already running and healthy — reusing", "lsp")
            return true
        }
        if (existing != null) {
            AppOutputLog.log("[LSP] ${language.displayName} server found but dead (isAlive=${existing.process.isAlive}) — restarting", "lsp")
            stopServer(language)
        }

        // Phase V-A: Transition to STARTING
        setServerState(language, LspState.STARTING)

        // BUG-FIX: Guard against proot rootfs not being installed yet.
        // If bash exists but the version marker is missing/stale (happens when rootfs was
        // carried forward from an older build), we repair the marker silently rather than
        // blocking LSP entirely — the rootfs itself is functional.
        if (!ProotInstaller.isInstalled(context)) {
            val bashExists = java.io.File(ProotInstaller.rootfsDir(context), "usr/bin/bash").exists()
            if (bashExists) {
                // Rootfs is functional but marker is missing/stale — repair it
                val versionFile = java.io.File(context.filesDir, ".ubuntu_version")
                try {
                    versionFile.writeText(ProotInstaller.VERSION)
                    AppOutputLog.log("[LSP] Repaired missing .ubuntu_version marker — rootfs was functional, marker was stale", "lsp")
                } catch (e: Exception) {
                    AppOutputLog.log("[LSP] WARNING: Could not write .ubuntu_version marker: ${e.message}", "lsp")
                }
            }
            // P40-AUTO-INSTALL: The rootfs is genuinely missing. Previously this returned
            // false and told the user to "open Terminal tab first" — a manual step nobody
            // asked for. ProotInstaller.install() is concurrency-safe (installLock/installJob)
            // so calling it here directly is safe even if a Terminal tab is installing it too;
            // it will just wait on the existing install and return once done. We're already on
            // Dispatchers.IO (called via withContext(Dispatchers.IO) from EditorPane), so this
            // blocking download+extract is safe to run right here — no user action required.
            if (!ProotInstaller.isInstalled(context)) {
                AppOutputLog.log("[LSP] Ubuntu rootfs not installed — auto-installing now (no manual Terminal step needed)…", "lsp")
                try {
                    ProotInstaller.install(context) { msg ->
                        AppOutputLog.log("[LSP] [setup] $msg", "lsp")
                    }
                } catch (e: Exception) {
                    AppOutputLog.log("[LSP] ERROR: Auto-install of Ubuntu rootfs failed: ${e.message}", "lsp")
                }
            }
            // Re-check after potential repair/auto-install
            if (!ProotInstaller.isInstalled(context)) {
                AppOutputLog.log("[LSP] ERROR: Ubuntu rootfs auto-install did not complete — LSP unavailable for now.", "lsp")
                return false
            }
        }

        // Check if installed, install if needed
        // LSP-FIX: pass the ALREADY-RESOLVED `config` (which may be vtslsConfig,
        // the TS7-native-vs-vtsls decision made above) through explicitly — otherwise
        // isServerInstalled/installServer independently re-derive config from
        // typescriptVersion alone and always assume TS7 native, checking/installing
        // a DIFFERENT server than the one about to be spawned below.
        Log.d(TAG, "startServer: checking isServerInstalled for ${language.displayName} via: ${config.checkCommand}")
        if (!isServerInstalled(context, language, resolvedConfig = config)) {
            Log.d(TAG, "startServer: NOT installed — running installServer for ${language.displayName}")
            AppOutputLog.log("[LSP] ${language.displayName} server not installed — starting install…", "lsp")
            val installResult = installServer(context, language, resolvedConfig = config)
            Log.d(TAG, "Install result: $installResult")
            if (!isServerInstalled(context, language, resolvedConfig = config)) {
                // P38-CHECK-FIX: The install may have succeeded but the check command
                // itself may be broken (false negative). As a last resort, check if the
                // binary exists at common locations before giving up.
                val binaryName = config.command
                val fallbackCheck = ProotInstaller.execOnce(context,
                    "command -v $binaryName && echo OK || echo NOT_FOUND", timeoutSeconds = 10)
                val fallbackLast = fallbackCheck.trimEnd().lines().lastOrNull().orEmpty().trim()
                if (fallbackLast == "OK") {
                    AppOutputLog.log("[LSP] ${language.displayName} install SUCCEEDED (fallback check confirmed binary exists)", "lsp")
                    Log.d(TAG, "startServer: install confirmed via fallback check for ${language.displayName}")
                } else {
                    Log.e(TAG, "startServer: FAILED — still not installed after install attempt for ${language.displayName}")
                    AppOutputLog.log("[LSP] ERROR: ${language.displayName} server still not installed after install attempt. Output: ${installResult.take(200)}", "lsp")
                    AppOutputLog.log("[LSP] ${language.displayName} fallback check also failed: $fallbackCheck", "lsp")
                    return false
                }
            }
            Log.d(TAG, "startServer: install SUCCEEDED for ${language.displayName}")
            AppOutputLog.log("[LSP] ${language.displayName} server install SUCCEEDED", "lsp")
        } else {
            Log.d(TAG, "startServer: already installed for ${language.displayName}")
            AppOutputLog.log("[LSP] ${language.displayName} server already installed — skipping install", "lsp")
        }

        // R3-KLSP-STDLIB: Ensure Kotlin stdlib JAR + classpath script exist before
        // starting the server. This handles re-download if the JAR was deleted and
        // creates the conditional classpath.sh that provides stdlib for loose .kt files.
        if (language == Language.KOTLIN) {
            val guestPath = workspaceGuestPath(context, workspacePath) ?: "/root"
            ensureKotlinStdlib(context, guestPath)
        }

        // Build proot command — wrap server in bash -c (NON-login shell).
        //
        // CRITICAL FIX (P32): Previously used bash -lc (login shell), which sources
        // /etc/profile → /etc/profile.d/*.sh → ~/.bashrc → ~/.agent-profile.sh.
        // The agent profile (McpShellProfile.kt) prints banner text to stdout:
        //   echo '[Agent] 32 tools ready. Type agent_tools to list...'
        //   echo "[Agent] Project files: $WORKSPACE_PATH"
        //   echo '[Agent] Shorthands: agent_read, agent_write...'
        // This banner text corrupts the JSON-RPC pipe BEFORE the LSP server starts.
        // The JsonRpcClient reader sees "[Agent] 32 tools ready..." instead of
        // "Content-Length: N\r\n\r\n", fails to parse Content-Length
        // (defaults to 0), and the initialize handshake fails with
        // "contentLength=0 (invalid)".
        //
        // FIX: Use bash -c (non-login) but source /etc/profile and ~/.bashrc with
        // stdout/stderr redirected to /dev/null. This preserves PATH, LD_PRELOAD,
        // and other environment variables set by profile scripts, while preventing
        // ANY banner text from reaching the JSON-RPC pipe. Then exec replaces
        // bash with the LSP server, giving it a clean stdout.
        // Gap 1: Use IdeEnvironment.forSubprocess — central env config with stdio binds stripped.
        val prootEnv = IdeEnvironment.forSubprocess(context)
        val proot = prootEnv.proot
        val envVars = prootEnv.envVars
        val headArgs = prootEnv.args.dropLast(2).toTypedArray()  // removes "/bin/bash", "--login"
        // P-PYRIGHT: Inject Node.js arguments from In-Project Settings when using Pyright
        val effectiveCmd = if (language == Language.PYTHON && ProjectSettingsStore.diagnosticsSource.value == DiagnosticsSource.PYRIGHT) {
            val nodeArgs = ProjectSettingsStore.pyrightNodeArgs.value.trim()
            val pyrightVer = ProjectSettingsStore.pyrightVersion.value.trim()
            val baseCmd = if (pyrightVer.isNotEmpty() && pyrightVer.startsWith("/")) {
                // User specified a path to local pyright-langserver.js
                "node $nodeArgs $pyrightVer --stdio"
            } else if (pyrightVer.isNotEmpty()) {
                // User specified a version — npm installs to /usr/local/lib/node_modules/pyright
                "node $nodeArgs /usr/local/lib/node_modules/pyright/langserver.index.js --stdio"
            } else {
                // Default: use the installed pyright-langserver binary
                "node $nodeArgs \$(which pyright-langserver) --stdio"
            }
            baseCmd
        } else {
            config.command + if (config.args.isEmpty()) "" else " " + config.args.joinToString(" ")
        }
        val serverCmd = effectiveCmd
        // Source profiles with output redirected to /dev/null, then exec the LSP server.
        // >/dev/null 2>&1 redirects ALL stdout/stderr from the sourcing to /dev/null,
        // so no echo/banner/cat output can reach the JSON-RPC pipe. Environment
        // variables set by the sourced scripts (PATH, LD_PRELOAD, LANG, etc.) persist
        // in the shell and are inherited by the exec'd LSP server.
        val shellCommand = "source /etc/profile >/dev/null 2>&1; source ~/.bashrc >/dev/null 2>&1; exec $serverCmd"
        val fullArgs = arrayOf(*headArgs, "/bin/bash", "-c", shellCommand)
        val cmdLine = listOf(proot) + fullArgs.drop(1).toList()
        Log.d(TAG, "startServer: spawning command: ${cmdLine.joinToString(" ")}")
        AppOutputLog.log("[LSP] Spawning ${language.displayName} server: $serverCmd", "lsp")

        val pb = ProcessBuilder(proot, *fullArgs.drop(1).toTypedArray())
        pb.redirectErrorStream(false)
        // Gap 1: Apply env via IdeEnvironment helper — no manual parsing here.
        IdeEnvironment.applyToProcessBuilder(pb, envVars)
        // JVM-based LSP servers (kotlin-language-server, jdtls) need a heap limit
        // on this 2.8GB device — without -Xmx, the JVM grabs too much memory and gets
        // OOM-killed by the Android low-memory killer before initialization completes.
        // 384m is enough for indexing small projects while leaving room for the IDE.
        if (config.command == "kotlin-language-server" || config.command == "/opt/jdtls/bin/jdtls") {
            pb.environment()["JAVA_TOOL_OPTIONS"] = "-Xmx384m -Dorg.slf4j.simpleLogger.defaultLogLevel=info -Dorg.slf4j.simpleLogger.showDateTime=true"
            AppOutputLog.log("[LSP] Setting JAVA_TOOL_OPTIONS=-Xmx384m + slf4j logging for ${language.displayName}", "lsp")
        }

        val process = try {
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "startServer: ProcessBuilder.start() THREW: ${e.message}")
            AppOutputLog.log("[LSP] ERROR: Failed to spawn ${language.displayName} process: ${e.message}", "lsp")
            return false
        }
        Log.d(TAG, "startServer: process spawned, isAlive=${process.isAlive}")
        notifyLspEvent(language, "starting", NotificationStore.Severity.PROGRESS, "Initializing language server…")
        AppOutputLog.log("[LSP] Process spawned for ${language.displayName} — isAlive=${process.isAlive}", "lsp")

        // Drain stderr in background thread so it doesn't block stdout (JSON-RPC) reads
        Thread {
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    Log.w(TAG, "LSP-STDERR [${language.displayName}]: $line")
                    if (line.isNotBlank()) {
                        AppOutputLog.log("[LSP][${language.displayName}][stderr] $line", "lsp")
                    }
                }
            } catch (_: Exception) {}
        }.also { it.isDaemon = true }.start()

        // Convert workspace path to guest path for rootUri
        val guestPath = workspaceGuestPath(context, workspacePath) ?: "/root"
        val guestPathEncoded = guestPath.split("/").joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8")
                .replace("+", "%20").replace("%2F", "/")
        }
        val rootUri = "file://$guestPathEncoded"

        val client = JsonRpcClient(process)
        // Phase V-M: Increment generation for this new server instance
        val generation = (generationCounters[language] ?: 0) + 1
        generationCounters[language] = generation
        val server = LspServer(language, process, client, rootUri, config.command, generation)
        servers[language] = server
        // Phase V-M: Set generation on the client for stale response protection
        client.generation = generation

        // Phase V-B: Start process exit monitor (crash detection)
        startProcessMonitor(language, server)

        // Phase V-E: Start memory monitoring
        ensureMemoryMonitorStarted()

        // Set up diagnostics push handler
        client.onNotification("textDocument/publishDiagnostics") { params ->
            val uri = params.optString("uri", "")
            val diags = params.optJSONArray("diagnostics") ?: JSONArray()
            server.diagnostics[uri] = diags
            diagnosticsHandlers[language]?.invoke(uri, diags)
            // Phase P: Feed into central DiagnosticManager
            val filePath = uri.removePrefix("file://")
            val converted = DiagnosticConverter.fromLsp(diags, uri, filePath, language.name.lowercase())
            if (converted.isEmpty()) {
                DiagnosticManager.clearDiagnostics(DiagnosticManager.DiagnosticSource.LSP, language.name.lowercase(), uri)
            } else {
                DiagnosticManager.publishDiagnostics(DiagnosticManager.DiagnosticSource.LSP, language.name.lowercase(), uri, filePath, converted)
            }
            AppOutputLog.log("[LSP] publishDiagnostics for ${language.displayName}: ${diags.length()} diagnostic(s) in ${uri.substringAfterLast('/')}", "lsp")
        }

        // R3-KLSP-LOG: Capture window/logMessage notifications from the
        // kotlin-language-server. KLS uses a CUSTOM Logger (org.javacs.kt.LOG)
        // that routes ALL application logs (classpath resolution, symbol
        // indexing, workspace management) through window/logMessage JSON-RPC
        // notifications — NOT stderr. SLF4J only captures Exposed (database)
        // and LSP4J framework logs. Without this handler, the critical
        // "Adding N files to class path" and "Updated symbol index" messages
        // are silently dropped.
        client.onNotification("window/logMessage") { params ->
            val type = params.optString("type", "")
            val msg = params.optString("message", "")
            val typeLabel = when (type) {
                "1" -> "ERROR"
                "2" -> "WARN"
                "3" -> "INFO"
                "4" -> "LOG"
                else -> type
            }
            AppOutputLog.log("[LSP][${language.displayName}][logMsg] [$typeLabel] $msg", "lsp")
        }

        client.start()

        // P38-FIX: When the reader thread exits (server crashed, EOF, etc.),
        // mark the server as not initialized so the next startServer call
        // can restart it.
        // Phase V-B/V-K: On disconnect, mark server dead and trigger auto-restart
        client.onDisconnect = disconnectHandler@ {
            val gen = server.generation
            val currentGen = generationCounters[language] ?: 0
            if (gen != currentGen) {
                lifecycleLog("DISCONNECT lang=${language.displayName} gen=$gen STALE (current=$currentGen) — ignoring")
                return@disconnectHandler
            }
            val wasIntentional = getServerState(language) == LspState.STOPPING
            if (!wasIntentional) {
                server.lastDeathWasCrash = true
                lifecycleLog("CRASH lang=${language.displayName} gen=$gen — unexpected disconnect")
                notifyLspEvent(language, "crash", NotificationStore.Severity.ERROR, "Server crashed unexpectedly. Restarting with backoff.", listOf(
                    NotificationStore.NotificationAction("restart", "Restart"),
                    NotificationStore.NotificationAction("view_logs", "View Logs"),
                ))
                setServerState(language, LspState.UNHEALTHY, "reader disconnected")
                server.initialized = false
                // Phase V-C: Trigger auto-restart with backoff
                handleAutoRestart(context, language, workspacePath)
            } else {
                lifecycleLog("DISCONNECT lang=${language.displayName} gen=$gen — intentional shutdown")
                server.initialized = false
            }
        }

        // ── LSP initialize request ────────────────────────────────────────
        // BUG-FIX: Send full client capabilities so servers know what we support.
        // Empty {} causes many servers to skip diagnostics, completions, and hover.
        // BUG-FIX: Include workspaceFolders so Kotlin/Python/Java LSPs can index project.
        val workspaceFolder = JSONObject().apply {
            put("uri", rootUri)
            put("name", workspacePath.substringAfterLast('/'))
        }
        val workspaceFoldersArray = JSONArray().apply { put(workspaceFolder) }

        // Kotlin LSP (fwcd/kotlin-language-server 1.3.13) uses an older LSP4J that
        // cannot deserialize many newer capability fields (callHierarchy, typeHierarchy,
        // linkedEditingRange, moniker, inlayHint, semanticTokens with empty arrays,
        // codeAction.resolveProvider). Sending full caps causes "Message could not be parsed."
        // Fix: send minimal capabilities for Kotlin — only well-established LSP 3.14 fields.
        val capabilities = if (config.command == "kotlin-language-server") {
            buildMinimalClientCapabilities()
        } else {
            buildClientCapabilities()
        }

        val initParams = JSONObject().apply {
            put("processId", android.os.Process.myPid())
            put("rootUri", rootUri)
            put("workspaceFolders", workspaceFoldersArray)
            put("capabilities", capabilities)
            put("clientInfo", JSONObject().apply {
                put("name", "VN Code")
                put("version", "1.0")
            })
            // Kotlin LSP (fwcd/kotlin-language-server) has a custom InitializationOptions
            // class — sending an empty {} causes LSP4J's Gson to throw "Message could
            // not be parsed" because it can't deserialize {} into the specific class.
            // Fix: omit initializationOptions entirely for Kotlin (server uses defaults).
            // vtsls needs specific options. Other servers accept {} fine.
            if (config.command == "kotlin-language-server") {
                // Omit initializationOptions — Kotlin LSP uses its own defaults
            } else if (config.command == "vtsls") {
                put("initializationOptions", JSONObject().apply {
                    put("vtsls", JSONObject().apply {
                        put("autoUseConfigFile", true)
                    })
                })
            } else {
                put("initializationOptions", JSONObject())
            }
        }

        // Phase V-A: Transition to INITIALIZING
        setServerState(language, LspState.INITIALIZING)
        Log.d(TAG, "startServer: sending initialize to ${language.displayName} (30s timeout)...")
        AppOutputLog.log("[LSP] Sending initialize to ${language.displayName} server (rootUri=$rootUri, 30s timeout)…", "lsp")
        AppOutputLog.log("[LSP] Initialize params (${initParams.toString().length} chars): ${initParams.toString().take(500)}", "lsp")
        val response = client.request("initialize", initParams, timeoutSeconds = 30)
        if (response == null) {
            Log.e(TAG, "startServer: LSP initialize TIMED OUT for ${language.displayName}")
            AppOutputLog.log("[LSP] ERROR: initialize failed for ${language.displayName} — server process alive=${process.isAlive}. Check [LSP][rpc] messages above for cause (TIMEOUT vs CONNECTION ERROR).", "lsp")
            stopServer(language)
            return false
        }
        Log.d(TAG, "startServer: initialize response for ${language.displayName}: ${response.toString().take(200)}")
        AppOutputLog.log("[LSP] initialize response received from ${language.displayName} server ✓", "lsp")

        val result = response as? JSONObject
        // P38-FIX: The initialize result is { "capabilities": {...}, "serverInfo": {...} }.
        // Extract the inner capabilities object so hasCapability("hoverProvider") etc. work.
        val caps = result?.optJSONObject("capabilities") ?: result
        server.capabilities = caps
        server.initialized = true
        // Phase V-A: Transition to READY
        setServerState(language, LspState.READY, "initialized")
        // Phase V-C: Reset restart backoff on successful init
        restartBackoffs[language]?.reset()?.let { restartBackoffs[language] = it }
        lifecycleLog("READY lang=${language.displayName} gen=${server.generation} pid=${getProcessPid(process)}")
        notifyLspEvent(language, "ready", NotificationStore.Severity.SUCCESS, "Server started successfully.")
        // DIAG: Log completionProvider capability specifically
        val compProvider = caps?.optJSONObject("completionProvider") ?: caps?.opt("completionProvider")
        AppOutputLog.log("[LSP-DIAG] Server capabilities - completionProvider: ${compProvider?.toString()?.take(300) ?: "NOT ADVERTISED"}", "lsp")
        val tdSync = caps?.opt("textDocumentSync")
        AppOutputLog.log("[LSP-DIAG] Server capabilities - textDocumentSync: ${tdSync?.toString() ?: "null"}", "lsp")
        AppOutputLog.log("[LSP] Server capabilities: ${caps.toString().take(300)}", "lsp")

        client.notify("initialized")

        // C-5 FIX: Send workspace/didChangeConfiguration so pylsp (and other servers)
        // configure their plugins properly. Without this, pylsp uses defaults that:
        // - don't enable rope for cross-file analysis
        // - don't configure file size limits / completion cache
        // - don't enable all diagnostic sources
        sendDidChangeConfiguration(language)

        Log.d(TAG, "startServer: SUCCESS — LSP server RUNNING for ${language.displayName} at $rootUri")
        AppOutputLog.log("[LSP] ✓ ${language.displayName} server RUNNING at $rootUri", "lsp")
        // Log server identity from the initialize response for diagnostics.
        // serverInfo contains { name, version } — confirms which backend is active.
        val serverInfo = result?.optJSONObject("serverInfo")
        if (serverInfo != null) {
            val serverName = serverInfo.optString("name", "unknown")
            val serverVersion = serverInfo.optString("version", "unknown")
            if (config.command == "tsc") {
                AppOutputLog.log("[LSP] ✓ TypeScript 7 native LSP confirmed — server: $serverName v$serverVersion", "lsp")
            } else if (config.command == "vtsls") {
                AppOutputLog.log("[LSP] ✓ vtsls + TypeScript 5.9.3 confirmed — server: $serverName v$serverVersion", "lsp")
            } else {
                AppOutputLog.log("[LSP] ✓ ${language.displayName} server confirmed — $serverName v$serverVersion", "lsp")
            }
        }
        // P50-3: If this server doesn't support workspace/symbol, auto-start ctags-lsp
        // as a secondary server so workspace symbol search still works for this language.
        if (!supportsWorkspaceSymbols(language)) {
            AppOutputLog.log("[LSP] ${language.displayName} lacks workspaceSymbolProvider — starting ctags-lsp fallback", "lsp")
            startCtagsLsp(context, workspacePath)
        }
        return true
    }

    /**
     * Build the full LSP client capabilities object declaring everything the app uses.
     * This is sent in the initialize request so servers know what to advertise.
     */
    /**
     * C-5 FIX: Send workspace/didChangeConfiguration to configure LSP server plugins.
     * This is critical for pylsp which needs explicit configuration to:
     * - Enable rope for cross-file refactoring and completion
     * - Enable all completion sources (jedi, rope, pycodestyle, pyflakes)
     * - Handle large files without timeouts
     * - Enable autopep8 for formatting
     * For other servers (tsserver, kotlin-ls, gopls), this is a no-op or sets sensible defaults.
     */
    private fun sendDidChangeConfiguration(language: Language) {
        val server = servers[language] ?: return
        if (!server.initialized) return

        val settings = when (language) {
            Language.PYTHON -> JSONObject().apply {
                put("pylsp", JSONObject().apply {
                    put("configurationSources", JSONArray().apply { put("pycodestyle"); put("pyflakes") })
                    put("plugins", JSONObject().apply {
                        // Jedi completion — handles all completion including stdlib
                        put("jedi_completion", JSONObject().apply {
                            put("enabled", true)
                            put("include_params", true)
                            put("include_class_objects", true)
                            put("include_imports", true)
                            put("fuzzy", true)
                        })
                        put("jedi", JSONObject().apply {
                            put("enabled", true)
                            // C-5: No line limit — handle any file size
                            put("environment", JSONObject().apply {
                                put("auto_download_modules", true)
                            })
                        })
                        // Rope — cross-file analysis and refactoring
                        put("rope", JSONObject().apply {
                            put("enabled", true)
                        })
                        // Pycodestyle — linting (minimal, don't flag style in large files)
                        put("pycodestyle", JSONObject().apply {
                            put("enabled", true)
                            put("maxLineLength", 120)
                        })
                        put("pyflakes", JSONObject().apply {
                            put("enabled", true)
                        })
                        // Autopep8 — formatting
                        put("autopep8", JSONObject().apply {
                            put("enabled", true)
                        })
                        // mccabe — complexity
                        put("mccabe", JSONObject().apply {
                            put("enabled", true)
                            put("threshold", 15)
                        })
                        // preload — preload modules for faster completion on large projects
                        put("preload", JSONObject().apply {
                            put("enabled", true)
                            put("modules", JSONArray().apply {
                                put("os"); put("sys"); put("json"); put("math")
                                put("re"); put("collections"); put("typing")
                                put("pathlib"); put("subprocess"); put("io")
                                put("datetime"); put("itertools"); put("functools")
                            })
                        })
                    })
                })
            }
            Language.KOTLIN -> JSONObject().apply {
                put("kotlin", JSONObject().apply {
                    put("languageServer", JSONObject().apply {
                        put("completion", JSONObject().apply { put("enabled", true) })
                    })
                })
            }
            Language.TYPESCRIPT, Language.JAVASCRIPT -> {
                val server = servers[language]
                val isNativeTs7 = server != null && server.command == "tsc"
                val isVtsls = server != null && server.command == "vtsls"
                if (isNativeTs7) {
                    // Native TS7 LSP: does NOT accept tsserver-specific settings.
                    // It uses standard LSP workspace/configuration and discovers
                    // tsconfig.json automatically. Send empty settings to avoid
                    // confusing the Go binary with JS-only tsserver options.
                    JSONObject()
                } else if (isVtsls) {
                    // vtsls: send VSCode-style typescript/javascript settings.
                    // vtsls wraps the VSCode TS extension and understands these.
                    JSONObject().apply {
                        put("typescript", JSONObject().apply {
                            put("suggest", JSONObject().apply {
                                put("enabled", true)
                                put("names", true)
                                put("paths", true)
                                put("autoImports", true)
                                put("completeFunctionCalls", true)
                                put("includeCompletionsForModuleExports", true)
                                put("includeCompletionsForImportStatements", true)
                            })
                            put("diagnostics", JSONObject().apply {
                                put("enabled", true)
                            })
                            put("format", JSONObject().apply {
                                put("enabled", true)
                                put("semicolons", "ignore")
                                put("indentSize", 2)
                                put("tabSize", 2)
                            })
                            put("updateImportsOnFileMove", JSONObject().apply {
                                put("enabled", "always")
                            })
                        })
                        put("javascript", JSONObject().apply {
                            put("suggest", JSONObject().apply {
                                put("enabled", true)
                                put("names", true)
                                put("paths", true)
                                put("autoImports", true)
                                put("completeFunctionCalls", true)
                            })
                            put("diagnostics", JSONObject().apply {
                                put("enabled", true)
                            })
                            put("format", JSONObject().apply {
                                put("enabled", true)
                                put("semicolons", "ignore")
                                put("indentSize", 2)
                                put("tabSize", 2)
                            })
                            put("updateImportsOnFileMove", JSONObject().apply {
                                put("enabled", "always")
                            })
                        })
                        put("vtsls", JSONObject().apply {
                            put("autoUseConfigFile", true)
                        })
                    }
                } else {
                    // typescript-language-server (TS 5.6.3) or unknown: send basic settings
                    JSONObject()
                }
            }
            else -> JSONObject()
        }

        val params = JSONObject().apply { put("settings", settings) }
        try {
            server.client.notify("workspace/didChangeConfiguration", params)
            AppOutputLog.log("[LSP] Sent workspace/didChangeConfiguration for ${language.displayName}", "lsp")
        } catch (e: Exception) {
            AppOutputLog.log("[LSP] Warning: didChangeConfiguration failed for ${language.displayName}: ${e.message}", "lsp")
        }
    }

    /**
     * Minimal client capabilities for LSP4J-based servers (kotlin-language-server 1.3.13)
     * that use older LSP4J versions which can't deserialize newer LSP capability fields.
     * Only includes LSP 3.14 (base spec) fields to avoid Gson "Message could not be parsed."
     */
    private fun buildMinimalClientCapabilities(): JSONObject =
        LspServerLifecycle.buildMinimalClientCapabilities()

    private fun buildClientCapabilities(): JSONObject =
        LspServerLifecycle.buildClientCapabilities()

    /**
     * P39-FULL: Send workspace/willRenameFiles notification before a file rename.
     * This lets the LSP server prepare WorkspaceEdits for updating imports/references
     * that point to the file being renamed. Returns the WorkspaceEdit if the server
     * provides one, or null.
     */
    fun willRenameFiles(
        language: Language,
        oldUri: String,
        newUri: String,
    ): JSONObject? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        return try {
            val renameFile = JSONObject()
                .put("oldUri", oldUri)
                .put("newUri", newUri)
            val params = JSONObject()
                .put("files", JSONArray().put(renameFile))
            val response = server.client.request("workspace/willRenameFiles", params, timeoutSeconds = 5)
            response as? JSONObject
        } catch (_: Exception) { null }
    }

    /**
     * P39-FULL: Send workspace/didRenameFiles notification after a file rename.
     * This tells the LSP server the rename is done so it can update its internal state.
     */
    fun didRenameFiles(
        language: Language,
        oldUri: String,
        newUri: String,
    ) {
        val server = servers[language] ?: return
        if (!server.initialized) return
        try {
            val renameFile = JSONObject()
                .put("oldUri", oldUri)
                .put("newUri", newUri)
            val params = JSONObject()
                .put("files", JSONArray().put(renameFile))
            server.client.notify("workspace/didRenameFiles", params)
        } catch (_: Exception) {}
    }

    // ── Phase V-B: Process Exit Monitor (crash detection) ──────────────────

    /**
     * Start a dedicated thread that waits for the LSP process to exit.
     * Captures exit status and distinguishes crash vs intentional shutdown.
     */
    private fun startProcessMonitor(language: Language, server: LspServer) {
        val gen = server.generation
        val monitorThread = Thread {
            try {
                val exitCode = server.process.waitFor()
                val currentGen = generationCounters[language] ?: 0
                if (gen != currentGen) {
                    // This monitor is for an old generation — ignore
                    lifecycleLog("PROCESS_EXIT lang=${language.displayName} gen=$gen STALE (current=$currentGen) — ignoring")
                    return@Thread
                }
                server.lastExitCode = exitCode
                val state = getServerState(language)
                if (state == LspState.STOPPING || state == LspState.STOPPED || state == LspState.IDLE_CLOSE) {
                    // Intentional shutdown — not a crash
                    server.lastDeathWasCrash = false
                    lifecycleLog("PROCESS_EXIT lang=${language.displayName} gen=$gen exit=$exitCode — intentional")
                } else {
                    // Unexpected death — this is a crash
                    server.lastDeathWasCrash = true
                    server.initialized = false
                    // Phase V-F: Detect OOM (exit code 137 = SIGKILL, commonly OOM killer)
                    // Phase V-FIX: Exit code 9 removed — in proot it's useradd/groupadd
                    // "already exists", not SIGKILL. Only 137 is a reliable OOM indicator.
                    val oomIndicator = if (exitCode == 137) {
                        val memSnap = server.memorySnapshot
                        if (memSnap != null && memSnap.state == MemoryState.CRITICAL) {
                            " POSSIBLE_OOM"
                        } else {
                            " POSSIBLE_SIGKILL"
                        }
                    } else ""
                    lifecycleLog("CRASH lang=${language.displayName} gen=$gen exit=$exitCode$oomIndicator")
                    if (oomIndicator.contains("OOM")) {
                        notifyLspEvent(language, "oom", NotificationStore.Severity.WARNING, "Server may have been killed by OOM (exit code $exitCode). High memory usage detected.")
                    } else {
                        notifyLspEvent(language, "crash", NotificationStore.Severity.ERROR, "Server crashed (exit code $exitCode). Auto-restarting with backoff.", listOf(
                            NotificationStore.NotificationAction("restart", "Restart"),
                            NotificationStore.NotificationAction("view_logs", "View Logs"),
                        ))
                    }
                    setServerState(language, LspState.UNHEALTHY, "process exit code=$exitCode")
                }
            } catch (_: InterruptedException) {
                // Normal — monitor was interrupted during stopServer
            }
        }.apply {
            isDaemon = true
            name = "LSP-Monitor-${language.displayName}-gen${gen}"
            start()
        }
        processMonitors[language] = monitorThread
    }

    // ── Phase V-C: Auto-restart with backoff ──────────────────────────────

    /**
     * Attempt to auto-restart a crashed LSP server with exponential backoff.
     * Circuit breaker after MAX_RESTARTS consecutive failures.
     */
    private fun handleAutoRestart(context: Context, language: Language, workspacePath: String) {
        val backoff = restartBackoffs.getOrPut(language) { RestartBackoff(language) }

        if (!backoff.canRestart()) {
            lifecycleLog("RESTART lang=${language.displayName} — CIRCUIT BREAKER (max ${RestartBackoff.MAX_RESTARTS} restarts exceeded)")
            setServerState(language, LspState.STOPPED, "circuit breaker")
            notifyLspEvent(language, "circuit_breaker", NotificationStore.Severity.ERROR, "Server repeatedly failed to start (${RestartBackoff.MAX_RESTARTS} attempts). Auto-restart disabled.", listOf(
                NotificationStore.NotificationAction("restart", "Restart Manually"),
            ))
            return
        }

        val delayMs = backoff.nextDelayMs()
        val nextRestartCount = backoff.consecutiveRestarts + 1
        lifecycleLog("RESTART lang=${language.displayName} attempt=$nextRestartCount/${RestartBackoff.MAX_RESTARTS} delay=${delayMs}ms")

        setServerState(language, LspState.RESTARTING, "attempt $nextRestartCount")

        Thread {
            try {
                Thread.sleep(delayMs)
                // Check if we were interrupted (e.g., user closed the tab during backoff)
                if (getServerState(language) == LspState.STOPPED) {
                    lifecycleLog("RESTART lang=${language.displayName} — cancelled during backoff (server stopped)")
                    return@Thread
                }
                // Phase V-C: Update backoff before restart attempt
                restartBackoffs[language] = backoff.increment()
                // Phase V-D: Save tracked documents before restart
                val savedDocs = servers[language]?.trackedDocuments?.let { HashMap(it) } ?: emptyMap()
                // Clean up old server
                servers.remove(language)?.let { old ->
                    old.client.stop()
                    old.process.destroyForcibly()
                }
                lifecycleLog("REINITIALIZE lang=${language.displayName}")
                // Restart the server
                val restarted = startServer(context, language, workspacePath)
                if (restarted) {
                    // Phase V-D: Restore workspace — re-open all tracked documents
                    if (savedDocs.isNotEmpty()) {
                        lifecycleLog("RESTORE_DOCUMENT lang=${language.displayName} — re-opening ${savedDocs.size} document(s)")
                        for ((_, doc) in savedDocs) {
                            didOpen(language, doc.uri, doc.languageId, doc.content, doc.version)
                        }
                    }
                } else {
                    lifecycleLog("RESTART lang=${language.displayName} — FAILED")
                    setServerState(language, LspState.UNHEALTHY, "restart failed")
                }
            } catch (_: InterruptedException) {
                lifecycleLog("RESTART lang=${language.displayName} — interrupted during backoff")
            }
        }.apply {
            isDaemon = true
            name = "LSP-Restart-${language.displayName}"
            start()
        }
    }

    // ── Phase V-D: Workspace / Document Recovery ───────────────────────────

    /**
     * Track an open document for workspace recovery after server restart.
     * Called by didOpen to record document state.
     */
    private fun trackDocument(language: Language, uri: String, languageId: String, content: String, version: Int) {
        val server = servers[language] ?: return
        server.trackedDocuments[uri] = TrackedDocument(uri, languageId, content, version)
    }

    /**
     * Update tracked document content on didChange.
     */
    private fun updateTrackedDocument(language: Language, uri: String, content: String, version: Int) {
        val server = servers[language] ?: return
        val existing = server.trackedDocuments[uri] ?: return
        server.trackedDocuments[uri] = existing.copy(content = content, version = version)
    }

    /**
     * Remove a tracked document on didClose.
     */
    private fun untrackDocument(language: Language, uri: String) {
        servers[language]?.trackedDocuments?.remove(uri)
    }

    /**
     * Clear all tracked documents for a language (on server death).
     * Phase V-D: Fix stale lspOpenedFiles problem — EditorPane should also clear its lspOpenedFiles.
     */
    fun clearTrackedDocuments(language: Language) {
        servers[language]?.trackedDocuments?.clear()
    }

    // ── Phase V-E: Memory Usage Monitoring ─────────────────────────────────

    /**
     * Start periodic memory monitoring for all running servers.
     * Reads /proc/<pid>/status off the UI thread.
     */
    private fun ensureMemoryMonitorStarted() {
        if (memoryMonitorScheduled) return
        // CRASH-FIX: recreate executor if it was shut down during teardown
        if (memoryMonitorExecutor.isShutdown || memoryMonitorExecutor.isTerminated) {
            memoryMonitorExecutor = Executors.newSingleThreadScheduledExecutor()
        }
        memoryMonitorScheduled = true
        memoryMonitorExecutor.scheduleAtFixedRate({
            for ((language, server) in servers) {
                if (!server.process.isAlive) continue
                try {
                    val pid = getProcessPid(server.process)
                    val snap = readMemorySnapshot(pid)
                    if (snap != null) {
                        server.memorySnapshot = snap
                        if (snap.state != MemoryState.NORMAL) {
                            lifecycleLog("MEMORY lang=${language.displayName} gen=${server.generation} ${snap.state} VmRSS=${snap.vmRssKb}kB VmPeak=${snap.vmPeakKb}kB")
                        }
                    }
                } catch (_: Exception) {}
            }
        }, 5, 10, TimeUnit.SECONDS) // first check after 5s, then every 10s
    }

    /**
     * Read VmRSS, VmSize, VmPeak from /proc/<pid>/status.
     * Returns null if the file is unavailable (e.g., proot hides it).
     */
    private fun readMemorySnapshot(pid: Long): MemorySnapshot? {
        return try {
            val statusFile = java.io.File("/proc/${pid}/status")
            if (!statusFile.exists()) return null
            var vmRss = 0L
            var vmSize = 0L
            var vmPeak = 0L
            statusFile.bufferedReader().forEachLine { line ->
                when {
                    line.startsWith("VmRSS:") -> vmRss = line.split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L
                    line.startsWith("VmSize:") -> vmSize = line.split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L
                    line.startsWith("VmPeak:") -> vmPeak = line.split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L
                }
            }
            // Phase V-E: Classify memory state
            // WARNING: >500MB RSS, CRITICAL: >1GB RSS (configurable in future)
            val state = when {
                vmRss > 1_000_000 -> MemoryState.CRITICAL  // >1GB
                vmRss > 500_000 -> MemoryState.WARNING      // >500MB
                else -> MemoryState.NORMAL
            }
            MemorySnapshot(vmRss, vmSize, vmPeak, state)
        } catch (_: Exception) { null }
    }

    // ── Phase V-G: Health Check / Responsiveness ──────────────────────────

    /**
     * Start periodic health checks for all running servers.
     * Uses a lightweight ping — the server's response to a simple request.
     */
    private fun ensureHealthCheckStarted() {
        if (healthCheckScheduled) return
        // CRASH-FIX: recreate executor if it was shut down during teardown
        if (healthCheckExecutor.isShutdown || healthCheckExecutor.isTerminated) {
            healthCheckExecutor = Executors.newSingleThreadScheduledExecutor()
        }
        healthCheckScheduled = true
        healthCheckExecutor.scheduleAtFixedRate({
            for ((language, server) in servers) {
                if (!server.process.isAlive) continue
                if (!server.initialized) continue
                val state = getServerState(language)
                if (state != LspState.READY) continue
                try {
                    // Phase V-G: Non-disruptive probe — check if process is alive AND responsive
                    // Use a short-timeout ping (not a real LSP method, just check alive)
                    val alive = server.process.isAlive
                    if (!alive) {
                        lifecycleLog("HEALTH_CHECK lang=${language.displayName} gen=${server.generation} — DEAD")
                        setServerState(language, LspState.UNHEALTHY, "health check: process dead")
                    }
                    // Note: We don't send a real LSP ping — no invented method.
                    // If the process is alive, we consider it responsive. The reader
                    // thread will detect unresponsive servers via timeout on real requests.
                } catch (_: Exception) {}
            }
        }, 30, 60, TimeUnit.SECONDS) // first check after 30s, then every 60s
    }

    // ── Phase V-I: Configurable Idle Auto-close ────────────────────────────

    /**
     * Set the idle timeout for LSP servers.
     * @param seconds 0 = never auto-close, otherwise auto-close after N seconds idle
     */
    fun setIdleTimeout(seconds: Long) {
        idleTimeoutSeconds = if (seconds > 0) seconds * 1000 else 0L
        lifecycleLog("IDLE_TIMEOUT set to " + if (seconds == 0L) "Never" else "${seconds}s")
    }

    /**
     * Get the effective idle timeout in milliseconds.
     */
    fun getIdleTimeoutMs(): Long = idleTimeoutSeconds

    fun stopServer(language: Language) {
        val server = servers.remove(language) ?: return
        // Phase V-A: Transition to STOPPING
        setServerState(language, LspState.STOPPING)
        // Phase P: Mark diagnostics from this LSP server as stale
        DiagnosticManager.markSourceStale(DiagnosticManager.DiagnosticSource.LSP, language.name.lowercase())
        // Phase V-J: Graceful shutdown — shutdown → exit → wait → destroy → destroyForcibly
        try {
            if (server.initialized) {
                server.client.request("shutdown", timeoutSeconds = 5)
                server.client.notify("exit")
            }
        } catch (_: Exception) {}
        // Phase V-J: Give the server a brief grace period to exit cleanly
        try {
            if (!server.process.waitFor(2, TimeUnit.SECONDS)) {
                server.process.destroy()
                if (!server.process.waitFor(3, TimeUnit.SECONDS)) {
                    // Last resort: SIGKILL
                    lifecycleLog("FORCE_KILL lang=${language.displayName} gen=${server.generation} — graceful shutdown exceeded 5s")
                    server.process.destroyForcibly()
                    server.process.waitFor(1, TimeUnit.SECONDS)
                }
            }
        } catch (_: Exception) {
            server.process.destroyForcibly()
        }
        server.client.stop()
        // Phase V-B: Stop the process exit monitor
        processMonitors.remove(language)?.let { it.interrupt() }
        lifecycleLog("SHUTDOWN lang=${language.displayName} gen=${server.generation} — complete")
        setServerState(language, LspState.STOPPED)
        Log.d(TAG, "LSP server stopped for ${language.displayName}")
        AppOutputLog.log("[LSP] Server stopped for ${language.displayName}", "lsp")
    }

    fun stopAll() {
        servers.keys.toList().forEach { stopServer(it) }
        // P50-3: Also stop ctags-lsp secondary server
        ctagsServer?.let { server ->
            try {
                if (server.initialized) {
                    server.client.request("shutdown", timeoutSeconds = 5)
                    server.client.notify("exit")
                }
            } catch (_: Exception) {}
            server.client.stop()
            server.process.destroyForcibly()
            ctagsServer = null
            AppOutputLog.log("[LSP] ctags-lsp secondary server stopped", "lsp")
        }
        // Phase V-E: Stop memory monitor
        memoryMonitorExecutor.shutdownNow()
        memoryMonitorScheduled = false
        // Phase V-G: Stop health check
        healthCheckExecutor.shutdownNow()
        healthCheckScheduled = false
        // Phase V: Clear all state
        serverStates.clear()
        restartBackoffs.clear()
        processMonitors.values.forEach { it.interrupt() }
        processMonitors.clear()
    }

    // ── Text document synchronization ──────────────────────────────

    fun didOpen(
        language: Language,
        uri: String,
        languageId: String,
        text: String,
        version: Int = 1,
    ): Boolean {
        val server = servers[language] ?: return false
        if (!server.initialized) return false
        val params = LspDocumentSync.buildDidOpenParams(uri, languageId, text, version)
        server.client.notify("textDocument/didOpen", params)
        trackDocument(language, uri, languageId, text, version)
        touchActivity(language)
        val contentPreview = if (text.length > 80) text.take(40) + "..." + text.takeLast(40) else text
        AppOutputLog.log("[LSP] didOpen sent: $uri (lang=$languageId, version=$version, textLen=${text.length})", "lsp")
        AppOutputLog.log("[LSP-DIAG] didOpen content preview: " + contentPreview.replace("\n", "\\n"), "lsp")
        return true
    }

    fun didChange(
        language: Language,
        uri: String,
        text: String,
        version: Int,
    ): Boolean {
        val server = servers[language] ?: return false
        if (!server.initialized) return false
        val params = LspDocumentSync.buildDidChangeParams(uri, text, version)
        server.client.notify("textDocument/didChange", params)
        updateTrackedDocument(language, uri, text, version)
        touchActivity(language)
        val contentPreview = if (text.length > 80) text.take(40) + "..." + text.takeLast(40) else text
        AppOutputLog.log("[LSP-DIAG] didChange: uri=" + uri + " version=" + version + " textLen=" + text.length + " content=" + contentPreview.replace("\n", "\\n"), "lsp")
        return true
    }

    fun didClose(language: Language, uri: String): Boolean {
        val server = servers[language] ?: return false
        if (!server.initialized) return false
        val params = LspDocumentSync.buildDidCloseParams(uri)
        server.client.notify("textDocument/didClose", params)
        untrackDocument(language, uri)
        return true
    }

    // ── LSP requests ───────────────────────────────────────────────

    fun getCompletion(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
        triggerCharacter: String? = null,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null

        // DIAG: Log server capability for completion
        val hasComp = hasCapability(language, "completionProvider")
        val compCaps = server.capabilities?.optJSONObject("completionProvider")
        AppOutputLog.log("[LSP-DIAG] getCompletion: uri=$uri line=$line char=$character triggerChar=$triggerCharacter hasCompletionProvider=$hasComp", "lsp")
        AppOutputLog.log("[LSP-DIAG] completionProvider caps: ${compCaps?.toString()?.take(300) ?: "null"}", "lsp")

        // R3-A: Delegate param building to LspCompletionHandler
        val triggerKind = if (triggerCharacter != null) 2 else 1
        val params = LspCompletionHandler.buildCompletionParams(uri, line, character, triggerCharacter, triggerKind)
        AppOutputLog.log("[LSP-DIAG] completion params: ${params.toString().take(500)}", "lsp")
        val compTimeout = if (line > 5000) 20L else if (line > 1000) 15L else 10L
        val response = server.client.request("textDocument/completion", params, timeoutSeconds = compTimeout)
        // DIAG: Log raw response
        val rawType = response?.javaClass?.simpleName ?: "null"
        val rawStr = response?.toString()?.take(500) ?: "null"
        AppOutputLog.log("[LSP-DIAG] completion RAW response: type=$rawType len=${response?.toString()?.length ?: 0} body=$rawStr", "lsp")
        return when (response) {
            null -> null
            is JSONArray -> response
            is JSONObject -> response.optJSONArray("items")
            else -> null
        }
    }

    /**
     * Phase U-1: Returns completion items + isIncomplete flag.
     * When isIncomplete=true, the server signals more items may be available on re-request.
     * Callers should re-request completions on the next keystroke instead of caching.
     */
    fun getCompletionWithMeta(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
        triggerCharacter: String? = null,
    ): Pair<JSONArray?, Boolean> {
        val server = servers[language] ?: return Pair(null, false)
        if (!server.initialized) return Pair(null, false)

        // DIAG: Log server capability for completion
        val hasComp = hasCapability(language, "completionProvider")
        val compCaps = server.capabilities?.optJSONObject("completionProvider")
        AppOutputLog.log("[LSP-DIAG] getCompletionWithMeta: uri=$uri line=$line char=$character triggerChar=$triggerCharacter hasCompletionProvider=$hasComp", "lsp")
        AppOutputLog.log("[LSP-DIAG] completionProvider caps: ${compCaps?.toString()?.take(300) ?: "null"}", "lsp")

        // R3-A: Delegate param building to LspCompletionHandler
        val triggerKind = if (triggerCharacter != null) 2 else 1
        val params = LspCompletionHandler.buildCompletionParams(uri, line, character, triggerCharacter, triggerKind)
        AppOutputLog.log("[LSP-DIAG] completion params: ${params.toString().take(500)}", "lsp")
        val compTimeout = if (line > 5000) 20L else if (line > 1000) 15L else 10L
        val response = server.client.request("textDocument/completion", params, timeoutSeconds = compTimeout)
        // DIAG: Log raw response
        val rawType = response?.javaClass?.simpleName ?: "null"
        val rawStr = response?.toString()?.take(500) ?: "null"
        AppOutputLog.log("[LSP-DIAG] completion RAW response: type=$rawType len=${response?.toString()?.length ?: 0} body=$rawStr", "lsp")
        return when (response) {
            null -> Pair(null, false)
            is JSONArray -> {
                AppOutputLog.log("[LSP-DIAG] response is JSONArray (array form) isIncomplete=false", "lsp")
                Pair(response, false)
            }
            is JSONObject -> {
                val isIncomplete = response.optBoolean("isIncomplete", false)
                AppOutputLog.log("[LSP-DIAG] response is JSONObject isIncomplete=$isIncomplete items=${response.optJSONArray("items")?.length() ?: 0}", "lsp")
                Pair(response.optJSONArray("items"), isIncomplete)
            }
            else -> Pair(null, false)
        }
    }

    fun getHover(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
    ): JSONObject? {
        val server = servers[language] ?: return null
        if (!hasCapability(language, "hoverProvider")) return null
        if (!server.initialized) return null

        // R3-A: Delegate param building to LspHoverHandler
        val params = LspHoverHandler.buildHoverParams(uri, line, character)
        // C-5 FIX: Scale timeout for large files
        val hoverTimeout = if (line > 5000) 15L else if (line > 1000) 12L else 10L
        val response = server.client.request("textDocument/hover", params, timeoutSeconds = hoverTimeout)
        return response as? JSONObject
    }

    /**
     * Request LSP signatureHelp at the given cursor position.
     * Returns a JSONObject with 'signatures' array, 'activeSignature', 'activeParameter',
     * or null if the server doesn't support it or no call is active.
     */
    fun getSignatureHelp(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
    ): JSONObject? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        if (!hasCapability(language, "signatureHelpProvider")) return null

        // R3-A: Delegate param building to LspSignatureHandler
        val params = LspSignatureHandler.buildSignatureHelpParams(uri, line, character)
        // C-5 FIX: Scale timeout for large files — pylsp needs more time to analyze big files
        val timeout = if (line > 5000) 15L else if (line > 1000) 10L else 5L
        val response = server.client.request("textDocument/signatureHelp", params, timeoutSeconds = timeout)
        return response as? JSONObject
    }

    fun getDefinition(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null

        val params = positionParams(uri, line, character)
        val response = server.client.request("textDocument/definition", params, timeoutSeconds = 10)
        return when (response) {
            null -> null
            is JSONArray -> response
            is JSONObject -> JSONArray().put(response)
            else -> null
        }
    }

    fun getReferences(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null

        val params = positionParams(uri, line, character)
        // BUG-5 FIX (restored): pylsp crashes with KeyError: 'includeDeclaration' if this field
        // is missing — it's required per the LSP spec, not optional as some servers treat it.
        params.put("context", JSONObject().put("includeDeclaration", true))
        val response = server.client.request("textDocument/references", params, timeoutSeconds = 10)
        return when (response) {
            null -> null
            is JSONArray -> response
            is JSONObject -> JSONArray().put(response)
            else -> null
        }
    }

    /**
     * BUG-4 FIX (restored): Reverse of fileUriFromHostPath — converts an LSP location's file://
     * URI (a GUEST/proot path, e.g. file:///host-files/main.py) back to the real host filesystem
     * path so the app can open/read the file. Without this, call sites decode the URI and use the
     * guest path directly as a host File(...) path, which only works by coincidence for the
     * currently-open file and silently fails to resolve (and thus fails to navigate) for any
     * other file — this is what broke cross-file Go to Definition.
     */
    fun hostPathFromFileUri(context: Context, uri: String): String? {
        if (!uri.startsWith("file://")) return null
        val rawPath = uri.removePrefix("file://")
        val guestPath = try { java.net.URLDecoder.decode(rawPath, "UTF-8") } catch (_: Exception) { rawPath }
        val filesDir = context.filesDir.absolutePath
        return when {
            guestPath == "/host-files" -> filesDir
            guestPath.startsWith("/host-files/") -> "$filesDir/" + guestPath.removePrefix("/host-files/")
            else -> try { ProotInstaller.guestToHostPath(context, guestPath).absolutePath } catch (_: Exception) { null }
        }
    }

    /**
     * P22-J: Request code actions (including auto-import fixes) for a range.
     * P39: accepts optional pre-built diagnostics context (e.g. from lint errors) so the
     * server can offer targeted quick fixes for known problems at this range.
     */
    fun getCodeActions(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
        diagnostics: JSONArray? = null,
        only: List<String>? = null,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        if (!hasCapability(language, "codeActionProvider")) return null

        val td = JSONObject().put("uri", uri)
        val pos = JSONObject().put("line", line).put("character", character)
        // R4-6: Delegate param building to LspCodeActionHandler
        val params = LspCodeActionHandler.buildCodeActionParams(uri, line, character, line, character, only)
        val response = server.client.request("textDocument/codeAction", params, timeoutSeconds = 10)
        return when (response) {
            null -> null
            is JSONArray -> response
            is JSONObject -> JSONArray().put(response)
            else -> null
        }
    }

    /**
     * P39-FULL: Execute a workspace command (for code actions that return a command
     * instead of a WorkspaceEdit). The command is executed on the server side.
     */
    fun executeCommand(
        language: Language,
        command: String,
        arguments: JSONArray? = null,
    ): Any? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        return try {
            val params = JSONObject()
                .put("command", command)
            if (arguments != null) params.put("arguments", arguments)
            server.client.request("workspace/executeCommand", params, timeoutSeconds = 10)
        } catch (_: Exception) { null }
    }

    /**
     * P39-FULL: Resolve a code action that returned `data` instead of `edit`.
     * Some servers return a code action with a `data` field and no `edit` —
     * the client must call `codeAction/resolve` to get the actual WorkspaceEdit.
     */
    fun resolveCodeAction(
        language: Language,
        action: org.json.JSONObject,
    ): org.json.JSONObject? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        return try {
            val response = server.client.request("codeAction/resolve", action, timeoutSeconds = 10)
            response as? org.json.JSONObject
        } catch (_: Exception) { null }
    }

    fun getSemanticTokens(
        language: Language,
        uri: String,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!hasCapability(language, "semanticTokensProvider")) return null
        if (!server.initialized) return null

        val td = JSONObject().apply { put("uri", uri) }
        val params = JSONObject().apply { put("textDocument", td) }
        val response = server.client.request("textDocument/semanticTokens/full", params, timeoutSeconds = 10)
        return when (response) {
            null -> null
            is JSONObject -> response.optJSONArray("data")
            else -> null
        }
    }

    /**
     * P24-3: Rename symbol at position across the workspace.
     */
    fun rename(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
        newName: String,
    ): JSONObject? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        val params = positionParams(uri, line, character)
        params.put("newName", newName)
        val response = server.client.request("textDocument/rename", params, timeoutSeconds = 15)
        return response as? JSONObject
    }


    // ── Document Symbol (Outline) ──────────────────────────────

    /**
     * Request document symbols for the outline panel / breadcrumbs.
     * Returns a JSONArray of SymbolInformation or DocumentSymbol entries.
     */
    fun getDocumentSymbol(
        language: Language,
        uri: String,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        val params = JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
        }
        val response = server.client.request("textDocument/documentSymbol", params, timeoutSeconds = 10)
        return response as? JSONArray
    }

    // ── Document Highlight ─────────────────────────────────────

    /**
     * Request document highlights (e.g. all occurrences of the symbol under cursor).
     * Returns a JSONArray of DocumentHighlight { range, kind }.
     */
    fun getDocumentHighlight(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
    ): JSONArray? {
        if (!hasCapability(language, "documentHighlightProvider")) return null
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        val params = positionParams(uri, line, character)
        val response = server.client.request("textDocument/documentHighlight", params, timeoutSeconds = 5)
        return response as? JSONArray
    }

    // ── Document Colors ────────────────────────────────────────

    /**
     * P41-K: Request document colors from the LSP server.
     * Returns a JSONArray of ColorInformation: { range: { start, end }, color: { red, green, blue, alpha } }
     */
    fun getDocumentColors(
        language: Language,
        uri: String,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        if (!hasCapability(language, "colorProvider")) return null
        val td = JSONObject().put("uri", uri)
        val params = JSONObject().put("textDocument", td)
        val response = server.client.request("textDocument/documentColor", params, timeoutSeconds = 10)
        return response as? JSONArray
    }

    /**
     * P41-S: Request color presentations for a color found via documentColor.
     * Returns a JSONArray of ColorPresentation entries (label + TextEdit).
     */
    fun getColorPresentations(
        language: Language,
        uri: String,
        colorInfo: JSONObject,
        range: JSONObject,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        if (!hasCapability(language, "colorProvider")) return null
        val params = JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("color", colorInfo)
            put("range", range)
        }
        val response = server.client.request("textDocument/colorPresentation", params, timeoutSeconds = 10)
        return response as? JSONArray
    }

    /**
     * P41-S: Request linked editing ranges for a position (matching HTML/XML tags).
     * Returns a JSONArray of Location ranges that should be edited together.
     */
    fun getLinkedEditingRanges(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        if (!hasCapability(language, "linkedEditingRangeProvider")) return null
        val params = JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("position", JSONObject().apply { put("line", line); put("character", character) })
        }
        val response = server.client.request("textDocument/linkedEditingRange", params, timeoutSeconds = 10)
        return response as? JSONArray
    }

    /**
     * P41-S: Request monikers (stable symbol identifiers) for a position.
     * Returns a JSONArray of Moniker entries.
     */
    fun getMonikers(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        if (!hasCapability(language, "monikerProvider")) return null
        val params = JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("position", JSONObject().apply { put("line", line); put("character", character) })
        }
        val response = server.client.request("textDocument/moniker", params, timeoutSeconds = 10)
        return response as? JSONArray
    }

    // ── Formatting ──────────────────────────────────────────────

    /**
     * Request full document formatting.
     * Returns a JSONArray of TextEdit entries to apply.
     */
    fun getFormatting(
        language: Language,
        uri: String,
        tabSize: Int = 4,
        insertSpaces: Boolean = true,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        if (!hasCapability(language, "documentFormattingProvider")) return null
        // R4-7: Delegate param building to LspFormattingHandler
        val params = LspFormattingHandler.buildFormattingParams(uri, LspFormattingHandler.buildDefaultFormattingOptions().apply {
            put("tabSize", tabSize)
            put("insertSpaces", insertSpaces)
        })
        val response = server.client.request("textDocument/formatting", params, timeoutSeconds = 10)
        return response as? JSONArray
    }

    /**
     * Request range formatting (format a selected range).
     * Returns a JSONArray of TextEdit entries.
     */
    fun getRangeFormatting(
        language: Language,
        uri: String,
        startLine: Int, startChar: Int,
        endLine: Int, endChar: Int,
        tabSize: Int = 4,
        insertSpaces: Boolean = true,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        val params = JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("range", JSONObject().apply {
                put("start", JSONObject().apply { put("line", startLine); put("character", startChar) })
                put("end", JSONObject().apply { put("line", endLine); put("character", endChar) })
            })
            put("options", JSONObject().apply {
                put("tabSize", tabSize)
                put("insertSpaces", insertSpaces)
            })
        }
        val response = server.client.request("textDocument/rangeFormatting", params, timeoutSeconds = 10)
        return response as? JSONArray
    }

    /**
     * Request on-type formatting (e.g. auto-format after typing `}`, `;`, etc.).
     */
    fun getOnTypeFormatting(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
        ch: String,
        tabSize: Int = 4,
        insertSpaces: Boolean = true,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        // R4-7: Delegate to LspFormattingHandler
        val params = LspFormattingHandler.buildOnTypeFormattingParams(
            uri, line, character, ch,
            LspFormattingHandler.buildDefaultFormattingOptions().apply {
                put("tabSize", tabSize)
                put("insertSpaces", insertSpaces)
            })
        val response = server.client.request("textDocument/onTypeFormatting", params, timeoutSeconds = 5)
        return response as? JSONArray
    }

    // ── Type Definition & Implementation ────────────────────────

    /**
     * Request the type definition of the symbol at position.
     * Returns a JSONArray of Location entries (like getDefinition).
     */
    fun getTypeDefinition(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        val params = positionParams(uri, line, character)
        val response = server.client.request("textDocument/typeDefinition", params, timeoutSeconds = 10)
        return when (response) {
            null -> null
            is JSONArray -> response
            is JSONObject -> JSONArray().put(response)
            else -> null
        }
    }

    /**
     * Request the implementations of an interface/abstract symbol at position.
     * Returns a JSONArray of Location entries.
     */
    fun getImplementation(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        val params = positionParams(uri, line, character)
        val response = server.client.request("textDocument/implementation", params, timeoutSeconds = 10)
        return when (response) {
            null -> null
            is JSONArray -> response
            is JSONObject -> JSONArray().put(response)
            else -> null
        }
    }

    /**
     * Request the declaration of the symbol at position.
     * Returns a JSONArray of Location entries (like getDefinition).
     * Some servers return declaration separately from definition
     * (e.g. header file declaration vs .cpp definition in C/C++).
     */
    fun getDeclaration(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        if (!hasCapability(language, "declarationProvider")) return null
        val params = positionParams(uri, line, character)
        val response = server.client.request("textDocument/declaration", params, timeoutSeconds = 10)
        return when (response) {
            null -> null
            is JSONArray -> response
            is JSONObject -> JSONArray().put(response)
            else -> null
        }
    }

    // ── Folding Range ──────────────────────────────────────────

    /**
     * Request folding ranges for code folding.
     * Returns a JSONArray of FoldingRange { startLine, endLine, kind? }.
     */
    fun getFoldingRange(
        language: Language,
        uri: String,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        if (!hasCapability(language, "foldingRangeProvider")) return null
        val params = JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
        }
        val response = server.client.request("textDocument/foldingRange", params, timeoutSeconds = 10)
        return response as? JSONArray
    }

    // ── Selection Range ────────────────────────────────────────

    /**
     * Request selection ranges for expand/shrink selection.
     * Returns a JSONArray of SelectionRange { range, parent? }.
     */
    fun getSelectionRange(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        if (!hasCapability(language, "selectionRangeProvider")) return null
        val params = JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("positions", JSONArray().apply {
                put(JSONObject().apply { put("line", line); put("character", character) })
            })
        }
        val response = server.client.request("textDocument/selectionRange", params, timeoutSeconds = 5)
        return response as? JSONArray
    }

    // ── Completion Resolve ─────────────────────────────────────

    /**
     * Resolve a completion item to get additional documentation/detail.
     * The server fills in `documentation`, `detail`, etc. on the returned item.
     */
    fun resolveCompletion(
        language: Language,
        item: JSONObject,
    ): JSONObject? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        val response = server.client.request("completionItem/resolve", item, timeoutSeconds = 5)
        return response as? JSONObject
    }


    /**
     * P41-K: Cancel a pending completion request for the given language.
     * Sends $/cancelRequest to the LSP server so it stops computing stale completions.
     */
    fun cancelPendingRequest(language: Language, requestId: Long) {
        val server = servers[language] ?: return
        if (!server.initialized) return
        server.client.cancelRequest(requestId)
    }

    /**
     * P41-K: Get the current pending request ID for a language server (for cancellation tracking).
     */
    fun getPendingRequestId(language: Language): Long {
        val server = servers[language] ?: return -1L
        if (!server.initialized) return -1L
        return server.client.getPendingRequestId()
    }

    /**
     * Phase X-7: Per-method pending request ID — prevents cross-method cancellation.
     */
    fun getPendingRequestId(language: Language, method: String): Long {
        val server = servers[language] ?: return -1L
        if (!server.initialized) return -1L
        return server.client.getPendingRequestId(method)
    }

    /**
     * Phase X-7: Cancel a pending request for a specific LSP method only.
     */
    fun cancelPendingRequest(language: Language, method: String, requestId: Long) {
        val server = servers[language] ?: return
        if (!server.initialized) return
        server.client.cancelRequest(method, requestId)
    }

    // ── Prepare Rename ──────────────────────────────────────────

    /**
     * Request the range of the symbol at position that can be renamed.
     * Returns a Range JSONObject { start, end } or null if not renameable.
     */
    fun prepareRename(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
    ): JSONObject? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        val params = positionParams(uri, line, character)
        val response = server.client.request("textDocument/prepareRename", params, timeoutSeconds = 5)
        return response as? JSONObject
    }

    // ── Workspace Symbol ────────────────────────────────────────

    // ── P50-3: ctags-lsp secondary server ─────────────────────────────────

    /**
     * P50-3: Install universal-ctags + ctags-lsp in the proot rootfs.
     * Called lazily when workspace/symbol fallback is first needed.
     * universal-ctags via apt, ctags-lsp via go install (Go already present for gopls).
     */
    fun ensureCtagsLspInstalled(context: Context): Boolean {
        if (ctagsInstallChecked) return ctagsServer != null
        ctagsInstallChecked = true
        try {
            // Install universal-ctags if not present
            val ctagsCheck = ProotInstaller.execOnce(context, "which ctags && echo OK", timeoutSeconds = 15)
            if (!ctagsCheck.trim().endsWith("OK")) {
                AppOutputLog.log("[LSP] Installing universal-ctags...", "lsp")
                ProotInstaller.execOnce(context,
                    "apt-get update -qq && apt-get install -y --no-install-recommends universal-ctags",
                    timeoutSeconds = 120, logToOutput = true)
            }
            // Install ctags-lsp if not present
            val ctagsLspCheck = ProotInstaller.execOnce(context, "which ctags-lsp && echo OK", timeoutSeconds = 15)
            if (!ctagsLspCheck.trim().endsWith("OK")) {
                AppOutputLog.log("[LSP] Installing ctags-lsp via go install...", "lsp")
                ProotInstaller.execOnce(context,
                    "go install github.com/netmute/ctags-lsp@latest",
                    timeoutSeconds = 180, logToOutput = true)
            }
            return true
        } catch (e: Exception) {
            AppOutputLog.log("[LSP] ctags-lsp install failed: ${e.message}", "lsp")
            return false
        }
    }

    /**
     * P50-3: Start ctags-lsp as a secondary server for workspace/symbol fallback.
     * Uses the same ProcessBuilder + proot pattern as startServer().
     */
    fun startCtagsLsp(context: Context, workspacePath: String): Boolean {
        if (ctagsServer?.process?.isAlive == true && ctagsServer?.initialized == true) return true
        if (!ensureCtagsLspInstalled(context)) return false

        try {
            AppOutputLog.log("[LSP] Starting ctags-lsp secondary server...", "lsp")

            // Gap 1: Use IdeEnvironment.forSubprocess — central env config with stdio binds stripped.
            val prootEnv = IdeEnvironment.forSubprocess(context)
            val proot = prootEnv.proot
            val envVars = prootEnv.envVars
            val headArgs = prootEnv.args.dropLast(2).toTypedArray()
            // Source profiles (same pattern as startServer), then exec ctags-lsp
            val shellCommand = "source /etc/profile >/dev/null 2>&1; source ~/.bashrc >/dev/null 2>&1; exec ctags-lsp"
            val fullArgs = arrayOf(*headArgs, "/bin/bash", "-c", shellCommand)

            val pb = ProcessBuilder(proot, *fullArgs.drop(1).toTypedArray())
            pb.redirectErrorStream(false)
            IdeEnvironment.applyToProcessBuilder(pb, envVars)

            val process = pb.start()

            // Drain stderr in background
            Thread {
                try {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        if (line.isNotBlank()) {
                            AppOutputLog.log("[LSP][ctags-lsp][stderr] $line", "lsp")
                        }
                    }
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()

            val client = JsonRpcClient(process)
            val guestPath = workspaceGuestPath(context, workspacePath) ?: "/root"
            val rootUri = "file://" + guestPath

            val server = LspServer(Language.PLAINTEXT, process, client, rootUri, "ctags-lsp")

            // Initialize handshake
            val initParams = JSONObject().apply {
                put("processId", 0)
                put("rootUri", rootUri)
                put("capabilities", JSONObject())
            }
            val response = client.request("initialize", initParams, timeoutSeconds = 30)
            if (response is JSONObject) {
                server.capabilities = response.optJSONObject("result")
                server.initialized = true
            lastActivity[Language.PLAINTEXT] = AtomicLong(System.currentTimeMillis())
            ensureAutoCloseStarted()
                client.notify("initialized", JSONObject())
                ctagsServer = server
                AppOutputLog.log("[LSP] ctags-lsp started successfully — workspace/symbol fallback ready", "lsp")
                return true
            }
        } catch (e: Exception) {
            AppOutputLog.log("[LSP] ctags-lsp start failed: ${e.message}", "lsp")
        }
        return false
    }

    /**
     * P50-3: Query ctags-lsp for workspace symbols.
     * Returns a JSONArray of SymbolInformation, or null if ctags-lsp isn't running.
     */
    fun getCtagsWorkspaceSymbol(query: String): JSONArray? {
        val server = ctagsServer ?: return null
        if (!server.initialized || !server.process.isAlive) return null
        val params = JSONObject().apply { put("query", query) }
        val response = server.client.request("workspace/symbol", params, timeoutSeconds = 10)
        return response as? JSONArray
    }

    /**
     * Request workspace symbols matching a query string.
     * Returns a JSONArray of SymbolInformation { name, kind, location, containerName? }.
     *
     * P50-3: Now with fallback chain:
     *   1. Primary LSP server (if it supports workspace/symbol)
     *   2. ctags-lsp secondary server (universal symbol search)
     *   3. FileIndexer regex (tertiary fallback, handled by SymbolSearchPanel)
     */
    fun getWorkspaceSymbol(
        language: Language,
        query: String,
    ): JSONArray? {
        val server = servers[language]
        if (server != null && server.initialized && supportsWorkspaceSymbols(language)) {
            // Primary server supports workspace/symbol — use it directly
            val params = JSONObject().apply {
                put("query", query)
            }
            val response = server.client.request("workspace/symbol", params, timeoutSeconds = 10)
            return response as? JSONArray
        }

        // P50-3: Primary server doesn't support workspace/symbol — try ctags-lsp
        if (server != null && server.initialized && !supportsWorkspaceSymbols(language)) {
            AppOutputLog.log("[LSP] ${language.displayName} does not support workspace/symbol — trying ctags-lsp fallback", "lsp")
            // Auto-start ctags-lsp if not running
            if (ctagsServer == null || ctagsServer?.process?.isAlive != true) {
                // Need context + workspacePath to start — we get these from the active server
                // We can't start ctags-lsp here (no Context param). It must be started
                // proactively when a server that lacks workspace/symbol starts.
                // For now, just query if it's already running.
                AppOutputLog.log("[LSP] ctags-lsp not running — using FileIndexer regex fallback", "lsp")
                return null
            }
            val ctagsResult = getCtagsWorkspaceSymbol(query)
            if (ctagsResult != null && ctagsResult.length() > 0) {
                return ctagsResult
            }
            AppOutputLog.log("[LSP] ctags-lsp fallback returned no results — using FileIndexer regex", "lsp")
            return null
        }

        // No server running at all — return null (FileIndexer regex will handle it)
        return null
    }

    // ── Diagnostics ────────────────────────────────────────────────

    fun getDiagnostics(language: Language, uri: String): JSONArray? {
        return servers[language]?.diagnostics?.get(uri)
    }

    fun setDiagnosticsHandler(language: Language, handler: (String, JSONArray) -> Unit) =
        LspDiagnosticsHandler.setHandler(language, handler)

    fun clearDiagnosticsHandler(language: Language) =
        LspDiagnosticsHandler.clearHandler(language)

    // ── Utility ────────────────────────────────────────────────────

    /**
     * Convert a host filesystem path to a file:// URI for LSP.
     */
    fun fileUriFromHostPath(context: Context, hostPath: String): String? {
        val guestPath = workspaceGuestPath(context, hostPath) ?: return null
        // P33-INTELLISENSE: Percent-encode path segments so spaces and special chars
        // round-trip correctly. The LSP server canonicalizes URIs with %20 for spaces;
        // if we send raw spaces, the server's publishDiagnostics response comes back
        // with %20 and diagUri == uri fails → squiggles never render.
        val encoded = guestPath.split("/").joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8")
                .replace("+", "%20")  // URLEncoder uses + for spaces; URI needs %20
                .replace("%2F", "/")  // don't encode slashes (already split)
        }
        return "file://$encoded"
    }

    /** Normalize a file:// URI for comparison — decode %XX so both sides match. */
    fun normalizeFileUri(uri: String): String =
        try { java.net.URLDecoder.decode(uri, "UTF-8") } catch (_: Exception) { uri }

    /**
     * Map a host path to the corresponding guest path inside proot.
     * Handles the context.filesDir -> /host-files bind mount.
     */
    fun workspaceGuestPath(context: Context, hostPath: String): String? {
        val filesDir = context.filesDir.absolutePath
        return when {
            hostPath == filesDir -> "/host-files"
            hostPath.startsWith("$filesDir/") ->
                "/host-files/" + hostPath.removePrefix("$filesDir/")
            else -> ProotInstaller.hostToGuestPath(context, hostPath)
        }
    }

    /**
     * Get the LSP languageId string for a Language enum value.
     */
    fun languageId(language: Language): String {
        return when (language) {
            Language.TYPESCRIPT -> "typescript"
            Language.JAVASCRIPT -> "javascript"
            Language.PYTHON -> "python"
            Language.KOTLIN -> "kotlin"
            Language.GO -> "go"
            Language.JAVA -> "java"
            Language.C -> "c"
            Language.CPP -> "cpp"
            Language.HTML -> "html"
            Language.CSS -> "css"
            Language.JSON -> "json"
            Language.MARKDOWN -> "markdown"
            Language.RUST -> "rust"
            Language.PHP -> "php"
            Language.SHELL -> "shellscript"
            Language.XML -> "xml"
            Language.YAML -> "yaml"
            Language.TOML -> "toml"
            Language.VUE -> "vue"
            Language.SVELTE -> "svelte"
            Language.CSHARP -> "csharp"
            Language.RUBY -> "ruby"
            Language.SWIFT -> "swift"
            Language.DART -> "dart"
            Language.LUA -> "lua"
            Language.SQL -> "sql"
            Language.POWERSHELL -> "powershell"
            Language.SCALA -> "scala"
            Language.R -> "r"
            Language.PLAINTEXT, Language.PLAIN -> "plaintext"
        }
    }

    // ── Private helpers ────────────────────────────────────────────

    private fun positionParams(uri: String, line: Int, character: Int): JSONObject =
        LspServerLifecycle.buildPositionParams(uri, line, character)

    // P26-1: LSP Code Lens — inline annotations (references count, test/run, etc.)
    fun getCodeLens(language: Language, uri: String): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        if (!hasCapability(language, "codeLensProvider")) return null
        val params = JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
        }
        val response = server.client.request("textDocument/codeLens", params, timeoutSeconds = 5)
        return response as? JSONArray
    }
    // P26-1: LSP Inlay Hints — inline type/parameter hints
    /**
     * P41-N: Resolve a CodeLens that returned only `data` (no command).
     * Some servers return a lens with just a range + data, and need a second
     * round-trip to get the actual command/title.
     */
    fun resolveCodeLens(language: Language, lens: JSONObject): JSONObject? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        if (!hasCapability(language, "codeLensProvider")) return null
        val caps = server.capabilities
        val codeLensCap = caps?.opt("codeLensProvider")
        val hasResolve = codeLensCap is JSONObject && codeLensCap.optBoolean("resolveProvider", false)
        if (!hasResolve) return null
        val response = server.client.request("codeLens/resolve", lens, timeoutSeconds = 5)
        return response as? JSONObject
    }

    fun getInlayHints(language: Language, uri: String): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        // pylsp-inlay-hints plugin advertises under "experimental.inlayHintProvider",
        // not the standard "inlayHintProvider" path. Check both.
        if (!hasCapability(language, "inlayHintProvider") &&
            !hasCapability(language, "experimental.inlayHintProvider")) return null
        val params = JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("range", JSONObject().apply {
                put("start", JSONObject().apply { put("line", 0); put("character", 0) })
                put("end", JSONObject().apply { put("line", Int.MAX_VALUE); put("character", 0) })
            })
        }
        val response = server.client.request("textDocument/inlayHint", params, timeoutSeconds = 5)
        return response as? JSONArray
    }

    // P26-1: LSP Document Link — clickable links in comments/strings
    fun getDocumentLinks(language: Language, uri: String): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        if (!hasCapability(language, "documentLinkProvider")) return null
        val params = JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
        }
        val response = server.client.request("textDocument/documentLink", params, timeoutSeconds = 5)
        return response as? JSONArray
    }

    // P26-1: LSP didSave — notify server on file save
    fun didSave(language: Language, uri: String, content: String): Boolean {
        val server = servers[language] ?: return false
        if (!server.initialized) return false
        val params = JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("text", content)
        }
        server.client.notify("textDocument/didSave", params)
        return true
    }

    // ── P41-M: Call Hierarchy ──────────────────────────────────────────────

    /**
     * P41-M: Prepare a call hierarchy item at the given position.
     * Returns a CallHierarchyItem JSON array (usually 1 item) or null if unsupported.
     * LSP method: textDocument/prepareCallHierarchy
     */
    fun prepareCallHierarchy(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        if (!hasCapability(language, "callHierarchyProvider")) return null

        val params = positionParams(uri, line, character)
        val response = server.client.request("textDocument/prepareCallHierarchy", params, timeoutSeconds = 10)
        return response as? JSONArray
    }

    /**
     * P41-M: Get incoming calls (who calls this function/method).
     * LSP method: callHierarchy/incomingCalls
     * Each result item has { from: CallHierarchyItem, fromRanges: Range[] }
     */
    fun callHierarchyIncoming(
        language: Language,
        item: JSONObject,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null

        val params = JSONObject().apply { put("item", item) }
        val response = server.client.request("callHierarchy/incomingCalls", params, timeoutSeconds = 10)
        return response as? JSONArray
    }

    /**
     * P41-M: Get outgoing calls (what does this function/method call).
     * LSP method: callHierarchy/outgoingCalls
     * Each result item has { to: CallHierarchyItem, fromRanges: Range[] }
     */
    fun callHierarchyOutgoing(
        language: Language,
        item: JSONObject,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null

        val params = JSONObject().apply { put("item", item) }
        val response = server.client.request("callHierarchy/outgoingCalls", params, timeoutSeconds = 10)
        return response as? JSONArray
    }

    // ── P41-M: Type Hierarchy ──────────────────────────────────────────────

    /**
     * P41-M: Prepare a type hierarchy item at the given position.
     * Returns a TypeHierarchyItem JSON array (usually 1 item) or null if unsupported.
     * LSP method: textDocument/prepareTypeHierarchy
     */
    fun prepareTypeHierarchy(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        if (!hasCapability(language, "typeHierarchyProvider")) return null

        val params = positionParams(uri, line, character)
        val response = server.client.request("textDocument/prepareTypeHierarchy", params, timeoutSeconds = 10)
        return response as? JSONArray
    }

    /**
     * P41-M: Get supertypes (parent classes/interfaces).
     * LSP method: typeHierarchy/supertypes
     */
    fun typeHierarchySupertypes(
        language: Language,
        item: JSONObject,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null

        val params = JSONObject().apply { put("item", item) }
        val response = server.client.request("typeHierarchy/supertypes", params, timeoutSeconds = 10)
        return response as? JSONArray
    }

    /**
     * P41-M: Get subtypes (child classes/implementations).
     * LSP method: typeHierarchy/subtypes
     */
    fun typeHierarchySubtypes(
        language: Language,
        item: JSONObject,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null

        val params = JSONObject().apply { put("item", item) }
        val response = server.client.request("typeHierarchy/subtypes", params, timeoutSeconds = 10)
        return response as? JSONArray
    }
}
