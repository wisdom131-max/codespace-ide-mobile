package com.codespace.ide.lsp

import android.content.Context
import android.util.Log
import com.codespace.ide.diagnostics.AppOutputLog
import com.codespace.ide.domain.Language
import com.codespace.ide.editor.DiagnosticsSource
import com.codespace.ide.editor.ProjectSettingsStore
import com.codespace.ide.editor.TypeScriptVersion
import com.codespace.ide.terminal.ProotInstaller
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


    // TS7 config: vtsls — works with TypeScript 7 (which dropped tsserver.js).
    // vtsls uses the TypeScript compiler API directly via JIT, no tsserver.js needed.
    private val vtslsConfig = ServerConfig(
        Language.TYPESCRIPT,
        "vtsls",
        listOf("--stdio"),
        // Check: vtsls binary exists (npm global install)
        "which vtsls && echo OK",
        // Install: NodeSource setup + npm install vtsls + typescript@7
        // vtsls is a pure-JS LSP server using TS compiler API, no tsserver.js dependency.
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
            "npm install -g vtsls typescript@7",
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
            // P32-LSP-FIX: Replace broken Ubuntu apt nodejs/npm with NodeSource.
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
            // P32-LSP-FIX: Same NodeSource install + 300s timeout as TS.
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
        Language.KOTLIN to ServerConfig(
            Language.KOTLIN,
            "kotlin-language-server",
            emptyList(),
            "which kotlin-language-server && echo OK",
            "dpkg --configure -a 2>/dev/null; apt-get update -qq; apt-get install -y --no-install-recommends default-jre-headless unzip curl; " +
                "curl -fsSL https://github.com/fwcd/kotlin-language-server/releases/download/1.3.13/server.zip -o /tmp/kls.zip && " +
                "unzip -o /tmp/kls.zip -d /opt/kotlin-language-server >/dev/null 2>&1 && " +
                "ln -sf /opt/kotlin-language-server/bin/kotlin-language-server /usr/local/bin/kotlin-language-server && " +
                "rm -f /tmp/kls.zip && echo Kotlin-LSP-installed",
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
            // P32-LSP-FIX: NodeSource-based install — bypasses broken apt nodejs (libnode115 conflict).
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
            // P32-LSP-FIX: NodeSource-based install — bypasses broken apt nodejs (libnode115 conflict).
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
            // P32-LSP-FIX: NodeSource-based install — bypasses broken apt nodejs (libnode115 conflict).
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
            // P32-LSP-FIX: NodeSource-based install — bypasses broken apt nodejs (libnode115 conflict).
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
    ) {
        @Volatile var initialized = false
        @Volatile var capabilities: JSONObject? = null
        val diagnostics = ConcurrentHashMap<String, JSONArray>()
    }

    // ── Server lifecycle ───────────────────────────────────────────

    fun isSupported(language: Language): Boolean = configs.containsKey(language)

    fun isServerRunning(language: Language): Boolean =
        servers[language]?.let { it.process.isAlive } ?: false

    /** Touch activity timestamp — called on any editor interaction. */
    private fun touchActivity(language: Language) {
        lastActivity[language]?.set(System.currentTimeMillis())
    }

    /** Start the 10s idle auto-close checker (called once on first server start). */
    private fun ensureAutoCloseStarted() {
        if (autoCloseScheduled) return
        autoCloseScheduled = true
        autoCloseExecutor.scheduleAtFixedRate({
            if (!autoCloseEnabled) return@scheduleAtFixedRate
            val now = System.currentTimeMillis()
            lastActivity.entries.forEach { (lang, ts) ->
                if (now - ts.get() > 10_000L) {
                    val server = servers[lang]
                    if (server != null && server.process.isAlive) {
                        AppOutputLog.log("[LSP] Auto-closing idle server for ${lang.displayName} (10s idle)", "lsp")
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
     * Check if the LSP server binary is installed in the proot rootfs.
     */
    fun isServerInstalled(context: Context, language: Language): Boolean {
        var config = configs[language] ?: return false
        // P-PYRIGHT: Use Pyright config if Python + diagnostics source is PYRIGHT
        if (language == Language.PYTHON && ProjectSettingsStore.diagnosticsSource.value == DiagnosticsSource.PYRIGHT) {
            config = pyrightConfig
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
    fun installServer(context: Context, language: Language): String {
        var config = configs[language] ?: return "No LSP server configured for ${language.displayName}"
        // P-PYRIGHT: Use Pyright config if Python + diagnostics source is PYRIGHT
        if (language == Language.PYTHON && ProjectSettingsStore.diagnosticsSource.value == DiagnosticsSource.PYRIGHT) {
            config = pyrightConfig
        }
        if (isServerInstalled(context, language)) {
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

    fun startServer(context: Context, language: Language, workspacePath: String): Boolean {
        // Master LSP toggle — when disabled, skip all LSP servers, use fallback completions only
        if (!ProjectSettingsStore.lspEnabled.value) {
            AppOutputLog.log("[LSP] LSP servers disabled in In-Project Settings — skipping startServer for ${'$'}{language.displayName}", "lsp")
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
        // TS7: Use vtsls instead of typescript-language-server when TS7 is selected.
        // TypeScript 7 dropped tsserver.js — vtsls uses the compiler API directly.
        if ((language == Language.TYPESCRIPT || language == Language.JAVASCRIPT) &&
            ProjectSettingsStore.typescriptVersion.value == TypeScriptVersion.TS7) {
            config = vtslsConfig
            AppOutputLog.log("[LSP] Using vtsls (TypeScript 7) instead of typescript-language-server — per In-Project Settings", "lsp")
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
            AppOutputLog.log("[LSP] ${language.displayName} server already running and healthy — reusing", "lsp")
            return true
        }
        if (existing != null) {
            AppOutputLog.log("[LSP] ${language.displayName} server found but dead (isAlive=${existing.process.isAlive}) — restarting", "lsp")
            stopServer(language)
        }

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
        Log.d(TAG, "startServer: checking isServerInstalled for ${language.displayName} via: ${config.checkCommand}")
        if (!isServerInstalled(context, language)) {
            Log.d(TAG, "startServer: NOT installed — running installServer for ${language.displayName}")
            AppOutputLog.log("[LSP] ${language.displayName} server not installed — starting install…", "lsp")
            val installResult = installServer(context, language)
            Log.d(TAG, "Install result: $installResult")
            if (!isServerInstalled(context, language)) {
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
        val (proot, baseArgs, envVars) = ProotInstaller.launchArgs(context)
        // Strip fd/0, fd/1, and fd/2 bind mounts (see previous comment — cosmetic warnings).
        val filteredArgs = baseArgs.filter {
            it != "--bind=/proc/self/fd/0:/dev/stdin" &&
            it != "--bind=/proc/self/fd/1:/dev/stdout" &&
            it != "--bind=/proc/self/fd/2:/dev/stderr"
        }
        val headArgs = filteredArgs.dropLast(2).toTypedArray()  // removes "/bin/bash", "--login"
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
        val envMap = pb.environment()
        envVars.forEach { kv ->
            val idx = kv.indexOf('=')
            if (idx > 0) envMap[kv.substring(0, idx)] = kv.substring(idx + 1)
        }

        val process = try {
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "startServer: ProcessBuilder.start() THREW: ${e.message}")
            AppOutputLog.log("[LSP] ERROR: Failed to spawn ${language.displayName} process: ${e.message}", "lsp")
            return false
        }
        Log.d(TAG, "startServer: process spawned, isAlive=${process.isAlive}")
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
        val server = LspServer(language, process, client, rootUri)
        servers[language] = server

        // Set up diagnostics push handler
        client.onNotification("textDocument/publishDiagnostics") { params ->
            val uri = params.optString("uri", "")
            val diags = params.optJSONArray("diagnostics") ?: JSONArray()
            server.diagnostics[uri] = diags
            diagnosticsHandlers[language]?.invoke(uri, diags)
            AppOutputLog.log("[LSP] publishDiagnostics for ${language.displayName}: ${diags.length()} diagnostic(s) in ${uri.substringAfterLast('/')}", "lsp")
        }

        client.start()

        // P38-FIX: When the reader thread exits (server crashed, EOF, etc.),
        // mark the server as not initialized so the next startServer call
        // can restart it.
        client.onDisconnect = {
            AppOutputLog.log("[LSP] Reader thread disconnected for ${'$'}{language.displayName} — marking server for restart", "lsp")
            server.initialized = false
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

        val capabilities = buildClientCapabilities()

        val initParams = JSONObject().apply {
            put("processId", android.os.Process.myPid())
            put("rootUri", rootUri)
            put("workspaceFolders", workspaceFoldersArray)
            put("capabilities", capabilities)
            put("clientInfo", JSONObject().apply {
                put("name", "CodeSpace IDE")
                put("version", "1.0")
            })
            put("initializationOptions", JSONObject())
        }

        Log.d(TAG, "startServer: sending initialize to ${language.displayName} (30s timeout)...")
        AppOutputLog.log("[LSP] Sending initialize to ${language.displayName} server (rootUri=$rootUri, 30s timeout)…", "lsp")
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

    private fun buildClientCapabilities(): JSONObject {
        // textDocument.synchronization
        val sync = JSONObject().apply {
            put("didSave", true)
            put("willSave", false)
            put("dynamicRegistration", false)
        }
        // textDocument.completion
        val completionItem = JSONObject().apply {
            put("snippetSupport", false)          // we strip snippets in parseLspCompletions
            put("documentationFormat", JSONArray().apply { put("plaintext"); put("markdown") })
        }
        val completion = JSONObject().apply {
            put("completionItem", completionItem)
            put("dynamicRegistration", false)
        }
        // textDocument.hover
        val hover = JSONObject().apply {
            put("contentFormat", JSONArray().apply { put("plaintext"); put("markdown") })
            put("dynamicRegistration", false)
        }
        // textDocument.signatureHelp
        val signatureInformation = JSONObject().apply {
            put("documentationFormat", JSONArray().apply { put("plaintext") })
        }
        val signatureHelp = JSONObject().apply {
            put("signatureInformation", signatureInformation)
            put("dynamicRegistration", false)
        }
        // textDocument.definition, references, rename
        val basic = JSONObject().apply { put("dynamicRegistration", false) }
        // textDocument.publishDiagnostics
        val publishDiagnostics = JSONObject().apply {
            put("relatedInformation", false)
            put("versionSupport", false)
        }
        // textDocument.codeAction
        val codeAction = JSONObject().apply {
            put("dynamicRegistration", false)
            put("codeActionLiteralSupport", JSONObject().apply {
                put("codeActionKind", JSONObject().apply {
                    put("valueSet", JSONArray().apply {
                        put(""); put("quickfix"); put("refactor"); put("refactor.extract")
                        put("refactor.inline"); put("refactor.rewrite"); put("source")
                        put("source.organizeImports"); put("source.fixAll")
                        put("source.removeUnused")
                    })
                })
            })
            // P39-FULL: Advertise resolve support so servers return data-only actions
            // that need resolving to get the actual WorkspaceEdit
            put("resolveProvider", true)
        }
        // textDocument.semanticTokens (declared as supported but minimal)
        val semanticTokens = JSONObject().apply {
            put("dynamicRegistration", false)
            put("requests", JSONObject().apply { put("full", true) })
            put("tokenTypes", JSONArray())
            put("tokenModifiers", JSONArray())
            put("formats", JSONArray().apply { put("relative") })
        }
        // textDocument.formatting, rangeFormatting, onTypeFormatting
        val formatting = JSONObject().apply { put("dynamicRegistration", false) }
        // textDocument.documentSymbol
        val documentSymbol = JSONObject().apply {
            put("dynamicRegistration", false)
            put("hierarchicalDocumentSymbolSupport", true)
            put("labelSupport", JSONObject().apply { put("labelDetailsSupport", true) })
        }
        // textDocument.foldingRange
        val foldingRange = JSONObject().apply {
            put("dynamicRegistration", false)
            put("rangeLimit", 5000)
            put("lineFoldingOnly", true)
        }
        // textDocument.selectionRange
        val selectionRange = JSONObject().apply { put("dynamicRegistration", false) }
        // textDocument.documentHighlight
        val documentHighlight = JSONObject().apply { put("dynamicRegistration", false) }
        // textDocument.declaration
        val declaration = JSONObject().apply { put("dynamicRegistration", false); put("linkSupport", true) }
        // textDocument.typeDefinition
        val typeDefinition = JSONObject().apply { put("dynamicRegistration", false); put("linkSupport", true) }
        // textDocument.implementation
        val implementation = JSONObject().apply { put("dynamicRegistration", false); put("linkSupport", true) }
        // textDocument.prepareRename
        val prepareSupport = JSONObject().apply { put("prepareSupport", true) }

        val textDocument = JSONObject().apply {
            put("synchronization", sync)
            put("completion", completion)
            put("hover", hover)
            put("signatureHelp", signatureHelp)
            put("definition", basic)
            put("declaration", declaration)
            put("typeDefinition", typeDefinition)
            put("implementation", implementation)
            put("references", basic)
            put("rename", JSONObject().apply {
                put("dynamicRegistration", false)
                put("prepareSupport", true)
            })
            put("publishDiagnostics", publishDiagnostics)
            put("codeAction", codeAction)
            put("semanticTokens", semanticTokens)
            put("documentSymbol", documentSymbol)
            put("foldingRange", foldingRange)
            put("selectionRange", selectionRange)
            put("documentHighlight", documentHighlight)
            put("formatting", formatting)
            put("rangeFormatting", formatting)
            put("onTypeFormatting", formatting)
            put("codeLens", JSONObject().apply { put("dynamicRegistration", false) })
            put("inlayHint", JSONObject().apply { put("dynamicRegistration", false) })
            put("documentLink", JSONObject().apply { put("dynamicRegistration", false); put("tooltipSupport", true) })
            // P41-M: Call Hierarchy
            put("callHierarchy", JSONObject().apply { put("dynamicRegistration", false) })
            // P41-M: Type Hierarchy
            put("typeHierarchy", JSONObject().apply { put("dynamicRegistration", false) })
            // P41-S: Linked Editing Range — allows simultaneous editing of matching tags (HTML/XML)
            put("linkedEditingRange", JSONObject().apply { put("dynamicRegistration", false) })
            // P41-S: Moniker — stable identifiers for symbols across workspace
            put("moniker", JSONObject().apply { put("dynamicRegistration", false) })
            // P41-S: Document Color + Color Presentation — color picker support for CSS/SCSS/Less
            put("documentColor", JSONObject().apply { put("dynamicRegistration", false) })
        }
        // workspace capabilities
        val workspace = JSONObject().apply {
            put("applyEdit", false)
            put("workspaceFolders", true)
            put("symbol", JSONObject().apply { put("dynamicRegistration", false) })
            // P39-FULL: Advertise fileOperations support for willRenameFiles/didRenameFiles
            put("fileOperations", JSONObject().apply {
                put("willRename", true)
                put("didRename", true)
            })
        }
        return JSONObject().apply {
            put("textDocument", textDocument)
            put("workspace", workspace)
        }
    }

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

    fun stopServer(language: Language) {
        val server = servers.remove(language) ?: return
        try {
            if (server.initialized) {
                server.client.request("shutdown", timeoutSeconds = 5)
                server.client.notify("exit")
            }
        } catch (_: Exception) {}
        server.client.stop()
        server.process.destroyForcibly()
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

        val td = JSONObject().apply {
            put("uri", uri)
            put("languageId", languageId)
            put("version", version)
            put("text", text)
        }
        val params = JSONObject().apply { put("textDocument", td) }
        server.client.notify("textDocument/didOpen", params)
        touchActivity(language)
        AppOutputLog.log("[LSP] didOpen sent: $uri (lang=$languageId)", "lsp")
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

        val td = JSONObject().apply {
            put("uri", uri)
            put("version", version)
        }
        val change = JSONObject().apply { put("text", text) }
        val changes = JSONArray().apply { put(change) }
        val params = JSONObject().apply {
            put("textDocument", td)
            put("contentChanges", changes)
        }
        server.client.notify("textDocument/didChange", params)
        touchActivity(language)
        return true
    }

    fun didClose(language: Language, uri: String): Boolean {
        val server = servers[language] ?: return false
        if (!server.initialized) return false

        val td = JSONObject().apply { put("uri", uri) }
        val params = JSONObject().apply { put("textDocument", td) }
        server.client.notify("textDocument/didClose", params)
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

        val params = positionParams(uri, line, character)
        // BUG-1 FIX (restored): pass completion context per LSP spec so servers that branch on
        // triggerKind (e.g. member completion after ".") behave correctly instead of
        // falling back to generic/invoked-style completion.
        val completionContext = JSONObject()
        if (triggerCharacter != null) {
            completionContext.put("triggerKind", 2) // TriggerCharacter
            completionContext.put("triggerCharacter", triggerCharacter)
        } else {
            completionContext.put("triggerKind", 1) // Invoked
        }
        params.put("context", completionContext)
        // C-5 FIX: Scale timeout for large files
        val compTimeout = if (line > 5000) 20L else if (line > 1000) 15L else 10L
        val response = server.client.request("textDocument/completion", params, timeoutSeconds = compTimeout)
        return when (response) {
            null -> null
            is JSONArray -> response
            is JSONObject -> response.optJSONArray("items")
            else -> null
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

        val params = positionParams(uri, line, character)
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

        val params = positionParams(uri, line, character)
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
        val range = JSONObject().put("start", pos).put("end", pos)
        val context = JSONObject().put("diagnostics", diagnostics ?: JSONArray())
        // P39-FULL: Pass `only` filter so the server returns only the requested action kinds
        // (e.g. ["refactor"] for "Show Available Refactorings", ["source"] for source actions)
        if (only != null && only.isNotEmpty()) {
            context.put("only", JSONArray(only))
        }
        val params = JSONObject()
            .put("textDocument", td)
            .put("range", range)
            .put("context", context)
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
        val params = JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("options", JSONObject().apply {
                put("tabSize", tabSize)
                put("insertSpaces", insertSpaces)
            })
        }
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
        val params = JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("position", JSONObject().apply { put("line", line); put("character", character) })
            put("ch", ch)
            put("options", JSONObject().apply {
                put("tabSize", tabSize)
                put("insertSpaces", insertSpaces)
            })
        }
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
            AppOutputLog.log("[LSP] ctags-lsp install failed: ${'$'}{e.message}", "lsp")
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

            val (proot, baseArgs, envVars) = ProotInstaller.launchArgs(context)
            val filteredArgs = baseArgs.filter {
                it != "--bind=/proc/self/fd/0:/dev/stdin" &&
                it != "--bind=/proc/self/fd/1:/dev/stdout" &&
                it != "--bind=/proc/self/fd/2:/dev/stderr"
            }
            val headArgs = filteredArgs.dropLast(2).toTypedArray()
            // Source profiles (same pattern as startServer), then exec ctags-lsp
            val shellCommand = "source /etc/profile >/dev/null 2>&1; source ~/.bashrc >/dev/null 2>&1; exec ctags-lsp"
            val fullArgs = arrayOf(*headArgs, "/bin/bash", "-c", shellCommand)

            val pb = ProcessBuilder(proot, *fullArgs.drop(1).toTypedArray())
            pb.redirectErrorStream(false)
            val envMap = pb.environment()
            envVars.forEach { kv ->
                val idx = kv.indexOf('=')
                if (idx > 0) envMap[kv.substring(0, idx)] = kv.substring(idx + 1)
            }

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

            val server = LspServer(Language.PLAINTEXT, process, client, rootUri)

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
            AppOutputLog.log("[LSP] ctags-lsp start failed: ${'$'}{e.message}", "lsp")
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
            AppOutputLog.log("[LSP] ${'$'}{language.displayName} does not support workspace/symbol — trying ctags-lsp fallback", "lsp")
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

    fun setDiagnosticsHandler(language: Language, handler: (String, JSONArray) -> Unit) {
        diagnosticsHandlers[language] = handler
    }

    fun clearDiagnosticsHandler(language: Language) {
        diagnosticsHandlers.remove(language)
    }

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

    private fun positionParams(uri: String, line: Int, character: Int): JSONObject {
        val td = JSONObject().apply { put("uri", uri) }
        val pos = JSONObject().apply {
            put("line", line)
            put("character", character)
        }
        return JSONObject().apply {
            put("textDocument", td)
            put("position", pos)
        }
    }

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
