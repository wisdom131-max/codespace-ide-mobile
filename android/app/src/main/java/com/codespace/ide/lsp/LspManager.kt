package com.codespace.ide.lsp

import android.content.Context
import android.util.Log
import com.codespace.ide.domain.Language
import com.codespace.ide.terminal.ProotInstaller
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * LspManager - manages LSP server lifecycle and provides LSP operations.
 *
 * P22-F: LSP groundwork - LspManager, stdio JSON-RPC client, server install via npm/pip in proot.
 *
 * Supported language servers:
 * - TypeScript/JavaScript: typescript-language-server --stdio
 * - Python: pylsp
 * - Kotlin: kotlin-language-server
 * - Go: gopls
 *
 * Language servers run inside the Ubuntu proot rootfs and communicate via
 * JSON-RPC 2.0 over stdio (see JsonRpcClient).
 *
 * Usage:
 *   1. LspManager.startServer(context, Language.TYPESCRIPT, workspacePath)
 *   2. LspManager.didOpen(language, uri, languageId, text)
 *   3. LspManager.getCompletion(language, uri, line, char)  // returns JSONArray
 *   4. LspManager.getHover(language, uri, line, char)       // returns JSONObject
 *   5. LspManager.didClose(language, uri)
 *   6. LspManager.stopServer(language)
 *
 * Diagnostics are pushed by the server via textDocument/publishDiagnostics
 * and can be retrieved with getDiagnostics() or via a registered handler.
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
        Language.TYPESCRIPT to ServerConfig(
            Language.TYPESCRIPT,
            "typescript-language-server",
            listOf("--stdio"),
            "which typescript-language-server",
            "npm install -g typescript-language-server typescript"
        ),
        Language.JAVASCRIPT to ServerConfig(
            Language.JAVASCRIPT,
            "typescript-language-server",
            listOf("--stdio"),
            "which typescript-language-server",
            "npm install -g typescript-language-server typescript"
        ),
        Language.PYTHON to ServerConfig(
            Language.PYTHON,
            "pylsp",
            emptyList(),
            "which pylsp",
            "pip install python-lsp-server[all]"
        ),
        Language.KOTLIN to ServerConfig(
            Language.KOTLIN,
            "kotlin-language-server",
            emptyList(),
            "which kotlin-language-server",
            "apt-get update 2>/dev/null; apt-get install -y --no-install-recommends default-jre-headless unzip curl 2>/dev/null; curl -fsSL https://github.com/fwcd/kotlin-language-server/releases/download/1.3.13/server.zip -o /tmp/kls.zip && unzip -o /tmp/kls.zip -d /opt/kotlin-language-server >/dev/null 2>&1 && ln -sf /opt/kotlin-language-server/bin/kotlin-language-server /usr/local/bin/kotlin-language-server && rm -f /tmp/kls.zip && echo Kotlin-LSP-installed",
            300
        ),
        Language.GO to ServerConfig(
            Language.GO,
            "gopls",
            emptyList(),
            "which gopls",
            "go install golang.org/x/tools/gopls@latest"
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
        val output = ProotInstaller.execOnce(context, config.checkCommand, timeoutSeconds = 10)
        return output.isNotBlank() &&
               !output.contains("not found") &&
               !output.contains("Error") &&
               !output.contains("Exit code")
    }

    /**
     * Install the LSP server binary in the proot rootfs via npm/pip.
     * Returns the installation output.
     */
    fun installServer(context: Context, language: Language): String {
        val config = configs[language] ?: return "No LSP server configured for ${language.displayName}"
        if (isServerInstalled(context, language)) {
            return "${language.displayName} LSP server already installed"
        }
        Log.d(TAG, "Installing LSP server for ${language.displayName}...")
        return ProotInstaller.execOnce(context, config.installCommand, timeoutSeconds = config.installTimeout)
    }

    /**
     * Start an LSP server for the given language and workspace.
     * Automatically installs the server if not present.
     * Returns true if the server started and initialized successfully.
     */
    fun startServer(context: Context, language: Language, workspacePath: String): Boolean {
        val config = configs[language] ?: return false

        // Stop existing server if running
        stopServer(language)

        // Check if installed, install if needed
        if (!isServerInstalled(context, language)) {
            val installResult = installServer(context, language)
            Log.d(TAG, "Install result: $installResult")
            if (!isServerInstalled(context, language)) {
                Log.e(TAG, "Failed to install LSP server for ${language.displayName}")
                return false
            }
        }

        // Build proot command with LSP server instead of bash
        val (proot, baseArgs, envVars) = ProotInstaller.launchArgs(context)
        val headArgs = baseArgs.dropLast(2).toTypedArray()  // removes "/bin/bash", "--login"
        val serverArgs = arrayOf(config.command, *config.args.toTypedArray())
        val fullArgs = arrayOf(*headArgs, *serverArgs)

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
            Log.e(TAG, "Failed to start LSP server: ${e.message}")
            return false
        }

        // Convert workspace path to guest path for rootUri
        val guestPath = workspaceGuestPath(context, workspacePath) ?: "/root"
        val rootUri = "file://$guestPath"

        val client = JsonRpcClient(process)
        val server = LspServer(language, process, client, rootUri)
        servers[language] = server

        // Set up diagnostics handler
        client.onNotification("textDocument/publishDiagnostics") { params ->
            val uri = params.optString("uri", "")
            val diags = params.optJSONArray("diagnostics") ?: JSONArray()
            server.diagnostics[uri] = diags
            diagnosticsHandlers[language]?.invoke(uri, diags)
        }

        client.start()

        // Send LSP initialize request
        val initParams = JSONObject()
        initParams.put("processId", android.os.Process.myPid())
        initParams.put("rootUri", rootUri)
        initParams.put("capabilities", JSONObject())

        val response = client.request("initialize", initParams, timeoutSeconds = 30)
        if (response == null) {
            Log.e(TAG, "LSP initialize failed for ${language.displayName}")
            stopServer(language)
            return false
        }

        val caps = response as? JSONObject
        server.capabilities = caps
        server.initialized = true

        // Send initialized notification
        client.notify("initialized")

        Log.d(TAG, "LSP server started for ${language.displayName} at $rootUri")
        return true
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

        val td = JSONObject()
        td.put("uri", uri)
        td.put("languageId", languageId)
        td.put("version", version)
        td.put("text", text)

        val params = JSONObject()
        params.put("textDocument", td)
        server.client.notify("textDocument/didOpen", params)
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

        val td = JSONObject()
        td.put("uri", uri)
        td.put("version", version)

        val change = JSONObject()
        change.put("text", text)
        val changes = JSONArray()
        changes.put(change)

        val params = JSONObject()
        params.put("textDocument", td)
        params.put("contentChanges", changes)
        server.client.notify("textDocument/didChange", params)
        return true
    }

    fun didClose(language: Language, uri: String): Boolean {
        val server = servers[language] ?: return false
        if (!server.initialized) return false

        val td = JSONObject()
        td.put("uri", uri)

        val params = JSONObject()
        params.put("textDocument", td)
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

    fun getSemanticTokens(
        language: Language,
        uri: String,
    ): JSONArray? {
        val server = servers[language] ?: return null
        if (!server.initialized) return null

        val td = JSONObject()
        td.put("uri", uri)
        val params = JSONObject()
        params.put("textDocument", td)
        val response = server.client.request("textDocument/semanticTokens/full", params, timeoutSeconds = 10)
        return when (response) {
            null -> null
            is JSONObject -> response.optJSONArray("data")
            else -> null
        }
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
     * Handles both the filesDir bind mount (/host-files) and rootfs paths.
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
            Language.PLAINTEXT, Language.PLAIN -> "plaintext"
        }
    }

    // ── Private helpers ────────────────────────────────────────────

    private fun positionParams(uri: String, line: Int, character: Int): JSONObject {
        val td = JSONObject()
        td.put("uri", uri)
        val pos = JSONObject()
        pos.put("line", line)
        pos.put("character", character)
        val params = JSONObject()
        params.put("textDocument", td)
        params.put("position", pos)
        return params
    }
}
