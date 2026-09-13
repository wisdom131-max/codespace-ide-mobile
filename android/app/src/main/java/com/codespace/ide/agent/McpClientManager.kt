package com.codespace.ide.agent

import android.content.Context
import com.codespace.ide.data.SecureTokenStore
import com.codespace.ide.diagnostics.AppOutputLog
import com.codespace.ide.terminal.ProotInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.io.File

/**
 * McpClientManager — real external MCP server support (stdio transport).
 *
 * DESIGN (confirmed with Wisdom 2026-09-07):
 * - Servers run INSIDE the existing Ubuntu proot session (npx/uvx-style
 *   commands work there). Spawn reuses ProotInstaller.launchArgs() — same
 *   machinery as AgentTools/PackageManagerPane commands, but keeps the
 *   process ALIVE with clean stdin/stdout pipes (no fd-1/2 binds, no merged
 *   streams) so JSON-RPC messages flow uncorrupted.
 * - LAZY lifecycle: nothing spawns at app launch. First chat message (docs
 *   discovery) or first mcp_ tool call starts a server. Stop via UI button.
 * - Env vars (may contain API keys) go through SecureTokenStore — same
 *   encrypted Keystore-backed storage as the AI provider keys. The config
 *   file only stores the VAR NAME -> secret KEY mapping, never the value.
 * - Tool names: mcp_<server>_<tool>. Bridged into AgentTools.executeTool's
 *   existing dispatch, so the SAME AgentFlowGate AUTO/MANUAL approval flow
 *   gates external tools — zero new approval mechanism.
 * - 60s per tools/call timeout. Missing Node/Python runtimes are DETECTED
 *   and reported (never silent-failed); install goes through the existing
 *   package-manager flow (ProotInstaller.execOnce with Output-tab logging).
 *
 * Protocol: MCP stdio = newline-delimited JSON-RPC 2.0 (spec 2024-11-05).
 * initialize -> notifications/initialized -> tools/list -> tools/call.
 */
object McpClientManager {

    data class McpServerConfig(
        val name: String,
        var command: String,
        var enabled: Boolean = true,
        val disabledTools: MutableList<String> = mutableListOf(),
        val envKeys: MutableMap<String, String> = mutableMapOf(),
    )

    data class ExternalTool(
        val server: String,
        val name: String,
        val description: String,
        val inputSchema: JSONObject,
    )

    // R9-C — MCP prompts capability (surfaced as read-only chat skills).
    // Prompts are OPTIONAL in the MCP spec: absence of the capability is
    // normal, never an error (caught + cached-empty below).
    data class ExternalPrompt(
        val server: String,
        val name: String,
        val description: String,
        val firstArgName: String?,  // single optional text arg (mobile-simple)
    )

    private val promptsCache = ConcurrentHashMap<String, List<ExternalPrompt>>()

    private const val PROTOCOL_VERSION = "2024-11-05"
    const val CALL_TIMEOUT_MS = 60_000L
    private const val INIT_TIMEOUT_MS = 30_000L
    private const val CHANNEL = "mcp"

    private val sessions = ConcurrentHashMap<String, McpServerSession>()
    private val toolsCache = ConcurrentHashMap<String, ExternalTool>() // "mcp_<server>_<tool>" -> tool
    @Volatile private var discoveryStarted = false

    // ── Config (mcp_servers.json in filesDir) ───────────────────────────────

    fun configFile(context: Context): File = File(context.filesDir, "mcp_servers.json")

    fun loadConfig(context: Context): MutableList<McpServerConfig> {
        val f = configFile(context)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val list = mutableListOf<McpServerConfig>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cfg = McpServerConfig(
                    o.getString("name"),
                    o.getString("command"),
                    o.optBoolean("enabled", true),
                )
                val dt = o.optJSONArray("disabledTools")
                if (dt != null) for (j in 0 until dt.length()) cfg.disabledTools.add(dt.getString(j))
                val ek = o.optJSONObject("envKeys")
                if (ek != null) for (k in ek.keys()) cfg.envKeys[k] = ek.getString(k)
                list.add(cfg)
            }
            list
        } catch (_: Exception) { mutableListOf() }
    }

    fun saveConfig(context: Context, list: List<McpServerConfig>) {
        try {
            val arr = JSONArray()
            for (c in list) {
                val o = JSONObject()
                    .put("name", c.name)
                    .put("command", c.command)
                    .put("enabled", c.enabled)
                o.put("disabledTools", JSONArray(c.disabledTools))
                val ek = JSONObject()
                c.envKeys.forEach { (k, v) -> ek.put(k, v) }
                o.put("envKeys", ek)
                arr.put(o)
            }
            configFile(context).writeText(arr.toString(2))
        } catch (e: Exception) {
            AppOutputLog.log("mcp config save failed: ${e.message}", CHANNEL)
        }
        // Config changed — next chat re-discovers docs.
        discoveryStarted = false
    }

    fun serverNameRaw(name: String): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")

    fun addServer(context: Context, name: String, command: String): Boolean {
        val clean = serverNameRaw(name)
        if (clean.isBlank() || command.isBlank()) return false
        val list = loadConfig(context)
        if (list.any { it.name == clean }) return false
        list.add(McpServerConfig(clean, command.trim()))
        saveConfig(context, list)
        AppOutputLog.log("mcp server added: $clean -> ${command.trim()}", CHANNEL)
        return true
    }

    fun removeServer(context: Context, name: String) {
        val list = loadConfig(context)
        val cfg = list.find { it.name == name } ?: return
        // Wipe env secrets (SecureTokenStore) — config only held the mapping.
        for (secretKey in cfg.envKeys.values) {
            try { SecureTokenStore(context).setAiKey(secretKey, null) } catch (_: Exception) {}
        }
        stopServer(name)
        saveConfig(context, list.filter { it.name != name })
        AppOutputLog.log("mcp server removed: $name", CHANNEL)
    }

    fun setEnabled(context: Context, name: String, enabled: Boolean) {
        val list = loadConfig(context)
        val cfg = list.find { it.name == name } ?: return
        cfg.enabled = enabled
        if (!enabled) stopServer(name)
        saveConfig(context, list)
    }

    fun setToolDisabled(context: Context, server: String, tool: String, disabled: Boolean) {
        val list = loadConfig(context)
        val cfg = list.find { it.name == server } ?: return
        if (disabled) {
            if (!cfg.disabledTools.contains(tool)) cfg.disabledTools.add(tool)
        } else {
            cfg.disabledTools.remove(tool)
        }
        saveConfig(context, list)
    }

    fun setCommand(context: Context, name: String, command: String) {
        val list = loadConfig(context)
        val cfg = list.find { it.name == name } ?: return
        cfg.command = command.trim()
        stopServer(name) // restart required for a changed command
        saveConfig(context, list)
    }

    // ── Env vars via SecureTokenStore (encrypted, Keystore-backed) ─────────

    fun envSecretKey(server: String, varName: String): String =
        "mcp_env_${serverNameRaw(server)}_${varName.trim().uppercase()}"

    fun setEnvVar(context: Context, server: String, varName: String, value: String) {
        val varClean = varName.trim()
        if (varClean.isEmpty()) return
        val secretKey = envSecretKey(server, varClean)
        SecureTokenStore(context).setAiKey(secretKey, value)
        val list = loadConfig(context)
        val cfg = list.find { it.name == serverNameRaw(server) } ?: return
        cfg.envKeys[varClean] = secretKey
        saveConfig(context, list)
        stopServer(serverNameRaw(server)) // restart required to pick up env
        AppOutputLog.log("mcp env var set for '$server': $varClean (stored encrypted)", CHANNEL)
    }

    fun getEnvVar(context: Context, server: String, varName: String): String? =
        SecureTokenStore(context).aiKey(envSecretKey(server, varName))

    // ── Runtime prerequisite detection (never fail silently) ────────────────

    /** "node" | "uv" | "python3" | null when the command has no known requirement. */
    fun requiredRuntime(command: String): String? {
        val head = command.trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return null
        return when (head) {
            "npx", "npm", "node" -> "node"
            "uvx", "uv" -> "uv"
            "python", "python3" -> "python3"
            else -> null
        }
    }

    /** True when the required runtime binary exists inside the proot rootfs. */
    fun runtimePresent(context: Context, runtime: String): Boolean {
        val probe = when (runtime) {
            "node" -> "command -v npx"
            "uv" -> "command -v uvx || command -v uv"
            else -> "command -v python3"
        }
        val out = ProotInstaller.execOnce(context, probe, timeoutSeconds = 20)
        return out.isNotBlank() && out.contains("/") && !out.startsWith("Error") && !out.contains("Exit code")
    }

    /** Install a missing runtime through the existing package-manager flow. */
    fun installRuntime(context: Context, runtime: String): String {
        val cmd = when (runtime) {
            "node" -> "apt-get update -qq && apt-get install -y -qq nodejs npm"
            "uv" -> "pip3 install -q uv || pip3 install -q uv --break-system-packages"
            else -> "apt-get update -qq && apt-get install -y -qq python3 python3-pip"
        }
        return ProotInstaller.execOnce(context, cmd, timeoutSeconds = 900, logToOutput = true)
    }

    // ── Session lifecycle (lazy) ────────────────────────────────────────────

    fun isRunning(name: String): Boolean = sessions[name]?.alive == true

    private fun session(context: Context, cfg: McpServerConfig): McpServerSession {
        sessions[cfg.name]?.let { if (it.alive) return it }
        synchronized(this) {
            var s = sessions[cfg.name]
            if (s != null && s.alive) return s
            val env = mutableMapOf<String, String>()
            for ((varName, secretKey) in cfg.envKeys) {
                val v = try { SecureTokenStore(context).aiKey(secretKey) } catch (_: Exception) { null }
                if (!v.isNullOrBlank()) env[varName] = v
            }
            s = McpServerSession(cfg.name, cfg.command, env)
            s.start(context)
            sessions[cfg.name] = s
            return s
        }
    }

    /** First-chat lazy discovery: spawn each enabled server once, tools/list, cache docs. */
    suspend fun ensureDiscovered(context: Context) {
        if (discoveryStarted) return
        discoveryStarted = true
        refreshTools(context)
    }

    /** Force re-discovery (UI Refresh button). Also re-runs the MCP handshake. */
    suspend fun refreshTools(context: Context) {
        withContext(Dispatchers.IO) {
            for (cfg in loadConfig(context)) {
                if (!cfg.enabled) continue
                try {
                    refreshServerTools(context, cfg)
                } catch (e: Exception) {
                    AppOutputLog.log("mcp server '${cfg.name}' discovery failed: ${e.message}", CHANNEL)
                }
            }
        }
    }

    private suspend fun refreshServerTools(context: Context, cfg: McpServerConfig) {
        val s = session(context, cfg)
        if (!s.initialized) s.initialize()
        val result = s.request(
            "tools/list", JSONObject(), INIT_TIMEOUT_MS,
        ).optJSONObject("result") ?: return
        val tools = result.optJSONArray("tools") ?: return
        val prefix = "mcp_${cfg.name}_"
        val cacheIter = toolsCache.entries.iterator()
        while (cacheIter.hasNext()) { if (cacheIter.next().key.startsWith(prefix)) cacheIter.remove() }
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            val toolName = t.getString("name")
            if (toolName.startsWith("mcp_")) continue // avoid mangling on namespaced tools
            toolsCache[prefix + toolName] = ExternalTool(
                cfg.name,
                toolName,
                t.optString("description"),
                t.optJSONObject("inputSchema") ?: JSONObject(),
            )
        }
        discoveryStarted = true
        AppOutputLog.log("mcp server '${cfg.name}': ${tools.length()} tools discovered", CHANNEL)
        // R9-C: best-effort prompts/list — capability is optional in the spec
        try {
            val pr = s.request("prompts/list", JSONObject(), INIT_TIMEOUT_MS)
                .optJSONObject("result")?.optJSONArray("prompts")
            if (pr != null && pr.length() > 0) {
                val list = (0 until pr.length()).mapNotNull { i ->
                    val o = pr.getJSONObject(i)
                    val argArr = o.optJSONArray("arguments")
                    val firstArg = try {
                        if (argArr != null && argArr.length() > 0)
                            argArr.getJSONObject(0).optString("name").ifBlank { null } else null
                    } catch (_: Exception) { null }
                    ExternalPrompt(
                        cfg.name,
                        o.optString("name").ifBlank { return@mapNotNull null },
                        o.optString("description"),
                        firstArg,
                    )
                }
                if (list.isNotEmpty()) promptsCache[cfg.name] = list
                AppOutputLog.log("mcp server '${cfg.name}': ${list.size} prompts discovered", CHANNEL)
            }
        } catch (_: Exception) {
            // No prompts capability (or errored) — normal, keep cache empty.
        }
    }

    fun stopServer(name: String) {
        sessions.remove(name)?.stop()
        val prefix = "mcp_${name}_"
        val cacheIter = toolsCache.entries.iterator()
        while (cacheIter.hasNext()) { if (cacheIter.next().key.startsWith(prefix)) cacheIter.remove() }
        promptsCache.remove(name)
    }

    // ── R9-C — MCP prompts as skills ───────────────────────────────────────

    /** R10-A: names of ENABLED servers from the config (for the status sheet). */
    fun enabledServerNames(context: Context): List<String> =
        try {
            loadConfig(context).filter { it.enabled }.map { it.name }
        } catch (_: Exception) { emptyList() }

    /** Cached prompts from ALREADY-CONNECTED servers (empty until first chat spawns them). */
    fun cachedPrompts(): List<ExternalPrompt> =
        promptsCache.values.flatten().sortedBy { it.server + "/" + it.name }

    /**
     * prompts/get — returns the rendered prompt text (user-message content joined).
     * Throws on failure; caller surfaces the error. argValue maps to the prompt's
     * FIRST declared argument (single-optional-text-field, R9-C mobile-simple).
     */
    suspend fun getPrompt(context: Context, server: String, promptName: String, argValue: String?): String {
        return withContext(Dispatchers.IO) {
            val cfg = loadConfig(context).find { it.name == server }
                ?: throw IllegalStateException("MCP server '$server' is not configured")
            if (!cfg.enabled) throw IllegalStateException("MCP server '$server' is disabled")
            val s = session(context, cfg)
            if (!s.initialized) s.initialize()
            val params = JSONObject().put("name", promptName)
            val firstArg = promptsCache[server]?.find { it.name == promptName }?.firstArgName
            if (firstArg != null && !argValue.isNullOrBlank()) {
                params.put("arguments", JSONObject().put(firstArg, argValue))
            }
            val result = s.request("prompts/get", params, CALL_TIMEOUT_MS)
                .optJSONObject("result")
                ?: throw IllegalStateException("MCP error: unexpected response shape")
            val messages = result.optJSONArray("messages")
                ?: throw IllegalStateException("MCP error: no messages in prompt")
            val sb = StringBuilder()
            for (i in 0 until messages.length()) {
                val m = messages.getJSONObject(i)
                when (val content = m.opt("content")) {
                    is String -> sb.append(content).append("\n\n")
                    is JSONObject -> if (content.optString("type") == "text") {
                        sb.append(content.optString("text")).append("\n\n")
                    }
                }
            }
            sb.toString().trim().ifBlank { "(empty prompt)" }
        }
    }

    // ── Docs exposed to the model (appended to the system prompt) ──────────

    private fun disabledToolsFor(context: Context, server: String): Set<String> =
        loadConfig(context).find { it.name == server }?.disabledTools?.toSet() ?: emptySet()

    fun cachedToolNames(context: Context): List<String> {
        val cfgs = loadConfig(context)
        return toolsCache.entries
            .filter { entry ->
                val cfg = cfgs.find { it.name == entry.value.server }
                cfg != null && cfg.enabled && !cfg.disabledTools.contains(entry.value.name)
            }
            .map { it.key }
            .sorted()
    }

    fun cachedToolsFor(server: String): List<ExternalTool> =
        toolsCache.values.filter { it.server == server }.sortedBy { it.name }

    fun toolDocs(context: Context): String {
        val names = cachedToolNames(context)
        if (names.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\n## EXTERNAL MCP TOOLS (external MCP servers via stdio)\n")
        sb.append("Tools prefixed mcp_ come from external MCP servers running inside the Ubuntu session. Call them with the SAME <TOOLS> JSON mechanism as built-in tools.\n")
        for (fullName in names) {
            val t = toolsCache[fullName] ?: continue
            sb.append("\n### ").append(fullName).append("\n")
            val desc = t.description.trim()
            sb.append(if (desc.length > 300) desc.take(300) + "..." else desc.ifBlank { "(no description)" }).append("\n")
            val props = t.inputSchema.optJSONObject("properties")
            if (props != null && props.length() > 0) {
                sb.append("Arguments (JSON object): ")
                for (k in props.keys()) {
                    val type = props.optJSONObject(k)?.optString("type") ?: "any"
                    sb.append(k).append(": ").append(type).append("; ")
                }
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    // ── Tool dispatch (bridged from AgentTools.executeTool) ─────────────────

    suspend fun callTool(fullName: String, args: JSONObject, context: Context): String {
        return withContext(Dispatchers.IO) {
            val cfgs = loadConfig(context)
            val cfg = cfgs.firstOrNull { fullName.startsWith("mcp_${it.name}_") }
            if (cfg == null) {
                return@withContext "Unknown MCP tool: $fullName (no matching configured server)"
            }
            val tool = fullName.removePrefix("mcp_${cfg.name}_")
            if (!cfg.enabled) return@withContext "MCP server '${cfg.name}' is disabled."
            if (cfg.disabledTools.contains(tool)) {
                return@withContext "Tool '$tool' is disabled on MCP server '${cfg.name}'."
            }
            // Runtime prerequisite — detect and REPORT, never fail silently.
            val runtime = requiredRuntime(cfg.command)
            if (runtime != null && !runtimePresent(context, runtime)) {
                AppOutputLog.log("mcp server '${cfg.name}' blocked: $runtime missing in Ubuntu session", CHANNEL)
                return@withContext "MCP server '${cfg.name}' needs $runtime inside the Ubuntu session, but it is not installed. Open Packages > MCP, tap Install $runtime, then retry."
            }
            try {
                val s = session(context, cfg)
                if (!s.initialized) {
                    // Lazy startup: full MCP handshake on first tool call.
                    s.initialize()
                    try {
                        val result = s.request("tools/list", JSONObject(), INIT_TIMEOUT_MS)
                            .optJSONObject("result")?.optJSONArray("tools")
                        if (result != null) {
                            val prefix = "mcp_${cfg.name}_"
                            for (i in 0 until result.length()) {
                                val t = result.getJSONObject(i)
                                val toolName = t.getString("name")
                                if (toolName.startsWith("mcp_")) continue
                                toolsCache[prefix + toolName] = ExternalTool(
                                    cfg.name, toolName, t.optString("description"),
                                    t.optJSONObject("inputSchema") ?: JSONObject(),
                                )
                            }
                        }
                    } catch (_: Exception) { /* docs cache optional for calling */ }
                }
                val params = JSONObject().put("name", tool).put("arguments", args)
                val resp = s.request("tools/call", params, CALL_TIMEOUT_MS)
                val result = resp.optJSONObject("result")
                    ?: return@withContext "MCP error: unexpected response shape"
                val content = result.optJSONArray("content")
                val sb = StringBuilder()
                if (content != null) {
                    for (i in 0 until content.length()) {
                        val item = content.getJSONObject(i)
                        if (item.optString("type") == "text") sb.append(item.getString("text")).append("\n")
                    }
                }
                val text = sb.toString().trim()
                if (result.optBoolean("isError", false)) {
                    "MCP tool error: ${text.ifBlank { "(empty error body)" }}"
                } else {
                    text.ifBlank { "(no output)" }
                }
            } catch (e: Exception) {
                "MCP call failed: ${e.message}"
            }
        }
    }
}

/**
 * One persistent stdio MCP server session: proot-spawned process with clean
 * pipes, newline-delimited JSON-RPC 2.0, id-matched pending deferreds.
 */
class McpServerSession(
    val name: String,
    private val command: String,
    private val env: Map<String, String>,
) {
    @Volatile var alive = false
    @Volatile var initialized = false
    @Volatile private var nextId = 1
    private var process: Process? = null
    private var writer: PrintWriter? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    fun start(context: Context) {
        // Same proot spawn pattern as ProotInstaller.execOnceWithProcess, but
        // long-lived: keep stdin/stdout pipes clean and stderr separate.
        val (proot, baseArgs, envVars) = ProotInstaller.launchArgs(context)
        val filtered = baseArgs.filter {
            it != "--bind=/proc/self/fd/1:/dev/stdout" &&
            it != "--bind=/proc/self/fd/2:/dev/stderr"
        }
        val headArgs = filtered.dropLast(2).toTypedArray()
        val shellCommand = "source /etc/profile >/dev/null 2>&1; source ~/.bashrc >/dev/null 2>&1; exec $command"
        val fullCommand = arrayOf(*headArgs, "/bin/bash", "-c", shellCommand)
        val pb = ProcessBuilder(proot, *fullCommand)
        val envMap = pb.environment()
        envVars.forEach { kv ->
            val idx = kv.indexOf('=')
            if (idx > 0) envMap[kv.substring(0, idx)] = kv.substring(idx + 1)
        }
        env.forEach { (k, v) -> envMap[k] = v }
        val p = pb.start()
        process = p
        writer = PrintWriter(java.io.OutputStreamWriter(p.outputStream, Charsets.UTF_8), true)
        alive = true

        Thread {
            try {
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line -> handleLine(line) }
            } catch (_: Exception) {
            } finally {
                alive = false
                initialized = false
                val err = java.util.concurrent.TimeoutException("MCP server '$name' closed the connection")
                pending.values.forEach { it.completeExceptionally(err) }
                pending.clear()
                AppOutputLog.log("mcp server '$name' exited", "mcp")
            }
        }.apply { isDaemon = true; name = "mcp-$name-out"; start() }

        Thread {
            try {
                p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { l ->
                    AppOutputLog.log("[$name] $l", "mcp")
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "mcp-$name-err"; start() }

        AppOutputLog.log("mcp server '$name' spawned: $command", "mcp")
    }

    private fun handleLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        val obj = try { JSONObject(trimmed) } catch (_: Exception) { return } // startup noise on stdout — ignore
        val id = obj.opt("id")
        if (id is Number) {
            pending.remove(id.toInt())?.complete(obj)
        } else if (obj.optString("method").startsWith("notifications/")) {
            AppOutputLog.log("[$name] ${obj.optString("method")}", "mcp")
        }
    }

    private fun writeLine(json: String) {
        val w = writer ?: throw IllegalStateException("MCP session '$name' is not running")
        synchronized(this) {
            w.println(json)
            w.flush()
        }
    }

    suspend fun request(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
        val id = nextId++
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        writeLine(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .put("params", params)
                .toString(),
        )
        val resp = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(id)
        if (resp == null) {
            throw java.util.concurrent.TimeoutException(
                "MCP request '$method' timed out after ${timeoutMs / 1000}s (server '$name')",
            )
        }
        if (resp.has("error")) {
            throw IllegalStateException(
                resp.optJSONObject("error")?.optString("message") ?: "MCP protocol error",
            )
        }
        return resp
    }

    fun notify(method: String, params: JSONObject) {
        writeLine(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", method)
                .put("params", params)
                .toString(),
        )
    }

    suspend fun initialize() {
        val params = JSONObject()
            .put("protocolVersion", "2024-11-05")
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "codespace-ide").put("version", "1.0"))
        request("initialize", params, 30_000L)
        notify("notifications/initialized", JSONObject())
        initialized = true
    }

    fun stop() {
        alive = false
        initialized = false
        try { process?.destroyForcibly() } catch (_: Exception) {}
        process = null
        writer = null
        AppOutputLog.log("mcp server '$name' stopped", "mcp")
    }
}
