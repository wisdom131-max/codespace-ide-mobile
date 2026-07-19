package com.codespace.ide.lsp

import android.content.Context
import android.util.Log
import com.codespace.ide.diagnostics.AppOutputLog
import com.codespace.ide.domain.Language
import com.codespace.ide.terminal.ProotInstaller
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * P24: LSP Code Action — a quick fix or refactoring suggestion returned by the language server.
 */
data class LspCodeAction(
    val title: String,
    val kind: String? = null,
    val edit: String? = null,
    val command: String? = null,
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
                "node -e \"try{require.resolve('typescript/lib/tsserver');process.stdout.write('OK')}catch(e){process.exit(1)}\"  && echo OK",
            "apt-get update -qq; " +
                "apt-get install -y --no-install-recommends nodejs npm; " +
                "npm install -g typescript-language-server typescript@5.6.3 --prefer-offline || " +
                "npm install -g typescript-language-server typescript@5.6.3",
        ),
        Language.JAVASCRIPT to ServerConfig(
            Language.JAVASCRIPT,
            "typescript-language-server",
            listOf("--stdio"),
            "which typescript-language-server && " +
                "node -e \"try{require.resolve('typescript/lib/tsserver');process.stdout.write('OK')}catch(e){process.exit(1)}\"  && echo OK",
            "apt-get update -qq; " +
                "apt-get install -y --no-install-recommends nodejs npm; " +
                "npm install -g typescript-language-server typescript@5.6.3 --prefer-offline || " +
                "npm install -g typescript-language-server typescript@5.6.3",
        ),
        // ── Python ─────────────────────────────────────────────────────────
        Language.PYTHON to ServerConfig(
            Language.PYTHON,
            "pylsp",
            emptyList(),
            "which pylsp",
            "apt-get update -qq; apt-get install -y --no-install-recommends python3-pip; pip3 install 'python-lsp-server[all]' || pip3 install python-lsp-server",
        ),
        // ── Kotlin ─────────────────────────────────────────────────────────
        Language.KOTLIN to ServerConfig(
            Language.KOTLIN,
            "kotlin-language-server",
            emptyList(),
            "which kotlin-language-server",
            "apt-get update -qq; apt-get install -y --no-install-recommends default-jre-headless unzip curl; " +
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
            "which gopls",
            "apt-get update -qq; apt-get install -y --no-install-recommends golang-go; go install golang.org/x/tools/gopls@latest",
        ),
        // ── Java ───────────────────────────────────────────────────────────
        // Uses eclipse.jdt.ls (jdtls). Lighter than IntelliJ, runs on JRE 17+.
        Language.JAVA to ServerConfig(
            Language.JAVA,
            "/opt/jdtls/bin/jdtls",
            listOf("-data", "/tmp/jdtls-workspace"),
            "test -f /opt/jdtls/bin/jdtls && echo found",
            "apt-get update -qq; apt-get install -y --no-install-recommends default-jre-headless curl unzip; " +
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
            "which clangd",
            "apt-get update -qq; apt-get install -y --no-install-recommends clangd",
        ),
        Language.CPP to ServerConfig(
            Language.CPP,
            "clangd",
            listOf("--background-index", "--clang-tidy"),
            "which clangd",
            "apt-get update -qq; apt-get install -y --no-install-recommends clangd",
        ),
        // ── Rust ───────────────────────────────────────────────────────────
        Language.RUST to ServerConfig(
            Language.RUST,
            "rust-analyzer",
            emptyList(),
            "which rust-analyzer",
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
            "which intelephense",
            "apt-get update -qq; apt-get install -y --no-install-recommends nodejs npm; npm install -g intelephense",
        ),
        // ── HTML ───────────────────────────────────────────────────────────
        // ── HTML ───────────────────────────────────────────────────────────
        // FIX: vscode-html-languageserver is deprecated. The maintained replacement is
        // vscode-langservers-extracted which ships html, css, json, and eslint servers.
        Language.HTML to ServerConfig(
            Language.HTML,
            "vscode-html-language-server",
            listOf("--stdio"),
            "which vscode-html-language-server",
            "apt-get update -qq; " +
                "apt-get install -y --no-install-recommends nodejs npm; " +
                "npm install -g vscode-langservers-extracted",
        ),
        // ── CSS ────────────────────────────────────────────────────────────
        // FIX: vscode-css-languageserver is deprecated — covered by vscode-langservers-extracted.
        Language.CSS to ServerConfig(
            Language.CSS,
            "vscode-css-language-server",
            listOf("--stdio"),
            "which vscode-css-language-server",
            "apt-get update -qq; " +
                "apt-get install -y --no-install-recommends nodejs npm; " +
                "npm install -g vscode-langservers-extracted",
        ),
        // ── JSON ───────────────────────────────────────────────────────────
        // vscode-langservers-extracted also ships vscode-json-language-server.
        // Since HTML/CSS already install it, JSON LSP is essentially free.
        Language.JSON to ServerConfig(
            Language.JSON,
            "vscode-json-language-server",
            listOf("--stdio"),
            "which vscode-json-language-server",
            "apt-get update -qq; " +
                "apt-get install -y --no-install-recommends nodejs npm; " +
                "npm install -g vscode-langservers-extracted",
        ),
    )

    // Running servers: language -> LspServer
    private val servers = ConcurrentHashMap<Language, LspServer>()

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

    /**
     * Check if the LSP server binary is installed in the proot rootfs.
     */
    fun isServerInstalled(context: Context, language: Language): Boolean {
        val config = configs[language] ?: return false
        AppOutputLog.log("[LSP] Checking if ${language.displayName} server installed: ${config.checkCommand}", "lsp")
        val output = ProotInstaller.execOnce(context, config.checkCommand, timeoutSeconds = 10)
        val installed = output.isNotBlank() &&
               !output.contains("not found") &&
               !output.contains("Error") &&
               !output.contains("Exit code") &&
               !output.contains("error")
        Log.d(TAG, "isServerInstalled(${language.displayName}): output=${output.take(120)} → $installed")
        AppOutputLog.log("[LSP] Install check result for ${language.displayName}: $installed (raw: ${output.take(80).trim()})", "lsp")
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
        val config = configs[language] ?: return "No LSP server configured for ${language.displayName}"
        if (isServerInstalled(context, language)) {
            AppOutputLog.log("[LSP] ${language.displayName} install check PASSED (binary + runtime files present) — skipping install", "lsp")
            return "${language.displayName} LSP server already installed"
        }
        AppOutputLog.log("[LSP] ${language.displayName} install check FAILED (binary missing or runtime files broken) — running install/repair", "lsp")
        Log.d(TAG, "Installing LSP server for ${language.displayName}...")
        AppOutputLog.log("[LSP] Installing ${language.displayName} server (timeout: ${config.installTimeout}s) — this may take 1-2 minutes…", "lsp")
        val installOutput = ProotInstaller.execOnce(context, config.installCommand, timeoutSeconds = config.installTimeout, logToOutput = true)
        AppOutputLog.log("[LSP] Install output for ${language.displayName}: ${installOutput.take(200).trim()}", "lsp")
        return installOutput
    }

    /**
     * Start an LSP server for the given language and workspace.
     * Automatically installs the server if not present.
     * Returns true if the server started and initialized successfully.
     *
     * BUG-FIX: If a server is already running and healthy for this language,
     * reuse it instead of killing and restarting. Only kill dead processes.
     */
    fun startServer(context: Context, language: Language, workspacePath: String): Boolean {
        val config = configs[language] ?: run {
            AppOutputLog.log("[LSP] No server config for ${language.displayName} — language not supported", "lsp")
            return false
        }
        Log.d(TAG, "startServer: BEGIN for ${language.displayName} workspace=$workspacePath")
        AppOutputLog.log("[LSP] startServer BEGIN: ${language.displayName} workspace=$workspacePath", "lsp")

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
            // Re-check after potential repair
            if (!ProotInstaller.isInstalled(context)) {
                AppOutputLog.log("[LSP] ERROR: Ubuntu rootfs not installed — cannot start LSP server. Open Terminal tab to set up Ubuntu first.", "lsp")
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
                Log.e(TAG, "startServer: FAILED — still not installed after install attempt for ${language.displayName}")
                AppOutputLog.log("[LSP] ERROR: ${language.displayName} server still not installed after install attempt. Output: ${installResult.take(200)}", "lsp")
                return false
            }
            Log.d(TAG, "startServer: install SUCCEEDED for ${language.displayName}")
            AppOutputLog.log("[LSP] ${language.displayName} server install SUCCEEDED", "lsp")
        } else {
            Log.d(TAG, "startServer: already installed for ${language.displayName}")
            AppOutputLog.log("[LSP] ${language.displayName} server already installed — skipping install", "lsp")
        }

        // Build proot command — wrap server in bash -lc to source PATH/profile,
        // matching execOnce() which is proven to work in the terminal.
        val (proot, baseArgs, envVars) = ProotInstaller.launchArgs(context)
        val headArgs = baseArgs.dropLast(2).toTypedArray()  // removes "/bin/bash", "--login"
        val serverCmd = config.command + if (config.args.isEmpty()) "" else " " + config.args.joinToString(" ")
        val fullArgs = arrayOf(*headArgs, "/bin/bash", "-lc", serverCmd)
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
        val rootUri = "file://$guestPath"

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
            AppOutputLog.log("[LSP] ERROR: initialize TIMED OUT (30s) for ${language.displayName} — server process alive=${process.isAlive}", "lsp")
            stopServer(language)
            return false
        }
        Log.d(TAG, "startServer: initialize response for ${language.displayName}: ${response.toString().take(200)}")
        AppOutputLog.log("[LSP] initialize response received from ${language.displayName} server ✓", "lsp")

        val caps = response as? JSONObject
        server.capabilities = caps
        server.initialized = true

        client.notify("initialized")

        Log.d(TAG, "startServer: SUCCESS — LSP server RUNNING for ${language.displayName} at $rootUri")
        AppOutputLog.log("[LSP] ✓ ${language.displayName} server RUNNING at $rootUri", "lsp")
        return true
    }

    /**
     * Build the full LSP client capabilities object declaring everything the app uses.
     * This is sent in the initialize request so servers know what to advertise.
     */
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
                        put(""); put("quickfix"); put("refactor"); put("source")
                    })
                })
            })
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
        }
        // workspace capabilities
        val workspace = JSONObject().apply {
            put("applyEdit", false)
            put("workspaceFolders", true)
            put("symbol", JSONObject().apply { put("dynamicRegistration", false) })
        }
        return JSONObject().apply {
            put("textDocument", textDocument)
            put("workspace", workspace)
        }
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
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null

        val params = positionParams(uri, line, character)
        val response = server.client.request("textDocument/completion", params, timeoutSeconds = 10)
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
        if (!server.initialized) return null

        val params = positionParams(uri, line, character)
        val response = server.client.request("textDocument/hover", params, timeoutSeconds = 10)
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

        val params = positionParams(uri, line, character)
        val response = server.client.request("textDocument/signatureHelp", params, timeoutSeconds = 5)
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
        params.put("context", JSONObject())
        val response = server.client.request("textDocument/references", params, timeoutSeconds = 10)
        return when (response) {
            null -> null
            is JSONArray -> response
            is JSONObject -> JSONArray().put(response)
            else -> null
        }
    }

    /**
     * P22-J: Request code actions (including auto-import fixes) for a range.
     */
    fun getCodeActions(
        language: Language,
        uri: String,
        line: Int,
        character: Int,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null

        val td = JSONObject().put("uri", uri)
        val pos = JSONObject().put("line", line).put("character", character)
        val range = JSONObject().put("start", pos).put("end", pos)
        val context = JSONObject().put("diagnostics", JSONArray())
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

    fun getSemanticTokens(
        language: Language,
        uri: String,
    ): JSONArray? {
        val server = servers[language] ?: return null
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
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        val params = positionParams(uri, line, character)
        val response = server.client.request("textDocument/documentHighlight", params, timeoutSeconds = 5)
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

    /**
     * Request workspace symbols matching a query string.
     * Returns a JSONArray of SymbolInformation { name, kind, location, containerName? }.
     */
    fun getWorkspaceSymbol(
        language: Language,
        query: String,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
        val params = JSONObject().apply {
            put("query", query)
        }
        val response = server.client.request("workspace/symbol", params, timeoutSeconds = 10)
        return response as? JSONArray
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
        return "file://$guestPath"
    }

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
        val params = JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
        }
        val response = server.client.request("textDocument/codeLens", params, timeoutSeconds = 5)
        return response as? JSONArray
    }

    // P26-1: LSP Inlay Hints — inline type/parameter hints
    fun getInlayHints(language: Language, uri: String): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null
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
}
