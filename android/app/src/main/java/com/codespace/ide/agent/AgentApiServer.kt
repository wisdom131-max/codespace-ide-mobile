package com.codespace.ide.agent

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * AgentApiServer — lightweight local HTTP server that exposes ALL AgentTools
 * to ANY AI running in the terminal (Claude Code, llama.cpp, etc.)
 *
 * Runs on port 8765 inside the app process, bound to LOOPBACK ONLY (TP02 hotfix:
 * never all-interfaces). Every route except /health requires the per-process
 * bearer token (Authorization: Bearer), exported into the guest shell profile
 * alongside AGENT_API_URL so legitimate CLI/agent use keeps working. Terminal AI:
 *   agent run_command '{"command":"ls -la"}'
 *   agent_tools
 *
 * This gives terminal-launched AI the SAME 31 tools as the chat panel:
 *   Shell, Git, Secrets, Web, Memory, Connectors, Entities, Scheduler, Media, Packages
 *
 * The server starts when the terminal/proot session begins and stops when it ends.
 */
object AgentApiServer {
    private const val TAG = "AgentApiServer"
    private const val PORT = 8765

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    @Volatile private var running = false
    private var serverContext: Context? = null

    // TP02 hotfix: per-process session token (generated once per app start, kept across
    // start/stop cycles so a long-lived guest shell is not broken by a server restart).
    // Required on every route except /health. Guest processes CAN read it from the shell
    // profile by design (they are this API's intended clients); what it denies is any
    // OTHER app on the device — Android loopback is shared between apps, so loopback bind
    // alone would still expose the socket to every installed app — plus anything on the LAN.
    @Volatile private var sessionToken: String? = null

    // P-MCP-INDICATOR-FIX: `running` only means "the socket is listening" — it stays true
    // for the whole terminal session even when no AI agent is actually talking to it. The
    // status bar needs to know when an agent is ACTIVELY connected, so we track the last
    // time any request came in and treat "active" as "a request landed recently."
    @Volatile private var lastRequestAtMs: Long = 0L
    private const val ACTIVE_WINDOW_MS = 12_000L

    /** Per-process bearer token; null only if the server has never started. */
    fun currentToken(): String? = sessionToken

    /** Generates the session token once per app process (TP02 hotfix spec: at app start). */
    private fun ensureSessionToken() {
        if (sessionToken != null) return
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        sessionToken = bytes.joinToString("") { "%02x".format(it) }
    }

    fun start(context: Context) {
        if (running) {
            Log.d(TAG, "Server already running on port $PORT")
            return
        }
        serverContext = context.applicationContext
        executor = Executors.newCachedThreadPool()
        running = true

        Thread {
            try {
                // TP02: bind loopback ONLY. ServerSocket(PORT) with no bind address binds 0.0.0.0
                // (all interfaces) — that exposed the full tool set to the LAN. Loopback keeps it
                // reachable from the app process and the proot guest (shared network namespace).
                serverSocket = ServerSocket(PORT, 50, InetAddress.getLoopbackAddress())
                ensureSessionToken()
                Log.i(TAG, "Agent API server started on port $PORT (loopback only, token-auth)")

                while (running) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        executor?.execute { handleRequest(client) }
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start failed: ${e.message}")
                running = false
            }
        }.also { it.isDaemon = true; it.name = "AgentApiServer" }.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        executor?.shutdown()
        serverSocket = null
        Log.i(TAG, "Agent API server stopped")
    }

    fun isRunning(): Boolean = running

    /** True only while an AI agent is actively making requests (not just "server listening"). */
    fun isAgentActive(): Boolean =
        running && (System.currentTimeMillis() - lastRequestAtMs) < ACTIVE_WINDOW_MS

    private fun handleRequest(client: java.net.Socket) {
        // Any inbound request (tool call, health check, system-prompt fetch, etc.) means
        // an agent is actively using this API right now — refresh the activity timestamp.
        lastRequestAtMs = System.currentTimeMillis()
        try {
            client.soTimeout = 30000
            val reader = BufferedReader(InputStreamReader(client.inputStream))
            val writer = OutputStreamWriter(client.outputStream)

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            // Read headers
            val headers = mutableMapOf<String, String>()
            var headerLine: String?
            while (reader.readLine().also { headerLine = it } != null) {
                if (headerLine.isNullOrEmpty()) break
                val colonIdx = headerLine!!.indexOf(":")
                if (colonIdx > 0) {
                    headers[headerLine!!.substring(0, colonIdx).trim().lowercase()] =
                        headerLine!!.substring(colonIdx + 1).trim()
                }
            }

            // Read body
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                reader.read(buf, 0, contentLength)
                String(buf)
            } else ""

            // TP02: auth gate — ONE choke point before routing. Everything except the
            // unauthenticated /health probe requires the per-process bearer token.
            // (PackageManagerPane health pings and agent_health() stay token-free on purpose.)
            if (path != "/health" && headers["authorization"] != "Bearer ${sessionToken ?: ""}") {
                val denied = httpJson(401, "{\"error\":\"unauthorized: missing or invalid agent token\"}")
                writer.write(denied)
                writer.flush()
                writer.close()
                reader.close()
                client.close()
                return
            }

            // Route
            val response = route(method, path, body)
            writer.write(response)
            writer.flush()
            writer.close()
            reader.close()
            client.close()
        } catch (e: Exception) {
            Log.e(TAG, "Request handling error: ${e.message}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun route(method: String, path: String, body: String): String {
        val ctx = serverContext ?: return httpJson(500, "Server context not initialized")

        return try {
            when {
                // Health check
                method == "GET" && path == "/health" ->
                    httpJson(200, """{"status":"ok","port":$PORT,"tools":32}""")

                // List all tools
                method == "GET" && path == "/tools" -> {
                    val tools = listOf(
                        "run_command","read_file","write_file","list_files","search_files",
                        "git_commit_push","git_pull_rebase","git_branch","git_status","git_diff",
                        "save_secret","get_secret","detect_secrets",
                        "web_fetch","web_search","save_memory","read_memory","delete_memory",
                        "list_connectors","connect_service","use_connector",
                        "create_entity","read_entities","update_entity","delete_entity",
                        "schedule_task","list_tasks","cancel_task",
                        "upload_file","install_package"
                    ) + McpClientManager.cachedToolNames(ctx)
                    val toolsJson = tools.joinToString(",") { """"$it"""" }
                    httpJson(200, """{"tools":[$toolsJson],"count":${tools.size}}""")
                }

                // Execute a tool: POST /tool/{toolName}
                method == "POST" && path.startsWith("/tool/") -> {
                    val toolName = path.removePrefix("/tool/").substringBefore("?")
                    val args = if (body.isNotBlank()) {
                        try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    } else JSONObject()

                    // Wrap args in the expected format: {"name":"...", "arguments":{...}}
                    val _wrapped = JSONObject()
                        .put("name", toolName)
                        .put("arguments", args)

                    // P2c TrustState choke point (headless, fail closed — an HTTP
                    // caller cannot be shown the interactive prompt). Unattended
                    // tool execution rides PROJECT TRUST of the active project:
                    // migrated (pre-P2c) projects are grandfathered trusted, so
                    // existing terminal-AI workflows continue unchanged; new
                    // projects run only after the user trusts them once.
                    // use_connector additionally requires PER-CALL consent (IG15),
                    // which no headless route can collect — refused outright.
                    if (toolName == "use_connector") {
                        httpJson(403, """{"error":"use_connector requires per-call consent - run it from the chat panel"}""")
                    } else if (!com.codespace.ide.security.TrustState.isActiveProjectTrusted(ctx)) {
                        httpJson(403, """{"error":"project not trusted yet - open the project and approve the trust prompt (any gated action in the chat panel), then retry"}""")
                    } else {
                        // CH11 (P4h): executeTool is suspend now (MCP bridge cancellable)
                        // — this handler runs on a plain socket thread, so bridge here.
                        val result = kotlinx.coroutines.runBlocking { AgentTools.executeTool(toolName, args, ctx) }
                        httpJson(200, """{"tool":"$toolName","result":${JSONObject.quote(result)}}""")
                    }
                }

                // System prompt for CLI AI tools
                // R2-AUTOINSTR: appends the ACTIVE project's instruction files
                // (AGENTS.md / copilot-instructions.md / CLAUDE.md) so terminal
                // AI tools obey the same per-project instructions as the panel.
                method == "GET" && path == "/system-prompt" -> {
                    val autoBlock = try {
                        val pid = com.codespace.ide.data.SessionStateStore(ctx).lastProjectId()
                        val root = if (pid != null)
                            com.codespace.ide.util.ProjectPathResolver.resolveProjectRoot(ctx, pid) else null
                        if (root != null && !root.isBlank() && AutoInstructionsProvider.isEnabled(ctx, root))
                            AutoInstructionsProvider.buildBlock(root) else ""
                    } catch (_: Exception) { "" }
                    httpJson(200, """{"prompt":${JSONObject.quote(AgentTools.TOOLS_DESCRIPTION + McpClientManager.toolDocs(ctx) + autoBlock)}}""")
                }

                
                // Save a terminal AI session snapshot into the Copilot chat SharedPreferences.
                // Called by the shell agent_session_save() function defined in McpShellProfile.
                // body JSON: {"title":"...","content":"...","mode":"TERMINAL"}
                method == "POST" && path == "/tool/save_terminal_session" -> {
                    val args = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    val title   = args.optString("title",   "Terminal session")
                    val content = args.optString("content", "")
                    val mode    = args.optString("mode",    "TERMINAL")
                    val id      = java.util.UUID.randomUUID().toString()
                    val now     = System.currentTimeMillis()

                    // Build a ChatSession-compatible JSON object and append it to the
                    // copilot_chat SharedPreferences list. Structure mirrors ChatSession
                    // data class in CopilotChatPanelOverlay.kt.
                    val msgJson = org.json.JSONObject()
                        .put("role", "terminal")
                        .put("content", content)
                        .put("timestamp", now)

                    val sessionJson = org.json.JSONObject()
                        .put("id",        id)
                        .put("title",     title)
                        .put("mode",      mode)
                        .put("messages",  org.json.JSONArray().put(msgJson))
                        .put("updatedAt", now)

                    val prefs = ctx.getSharedPreferences("copilot_chat", android.content.Context.MODE_PRIVATE)
                    val existing = try {
                        org.json.JSONArray(prefs.getString("sessions", "[]") ?: "[]")
                    } catch (_: Exception) { org.json.JSONArray() }
                    existing.put(sessionJson)
                    // Keep max 50 terminal sessions + copilot sessions combined.
                    val trimmed = org.json.JSONArray()
                    val start = maxOf(0, existing.length() - 50)
                    for (i in start until existing.length()) trimmed.put(existing.get(i))
                    prefs.edit().putString("sessions", trimmed.toString()).apply()

                    httpJson(200, """{"saved":true,"id":"$id"}""")
                }

                else -> httpJson(404, """{"error":"Not found: $method $path"}""")
            }
        } catch (e: Exception) {
            httpJson(500, """{"error":"${e.message?.replace("\"", "\\\"")}"}""")
        }
    }

    private fun httpJson(code: Int, body: String): String {
        val status = when (code) {
            200 -> "OK"
            401 -> "Unauthorized"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        return "HTTP/1.1 $code $status\r\n" +
               "Content-Type: application/json\r\n" +
               "Content-Length: ${body.toByteArray().size}\r\n" +
               "Access-Control-Allow-Origin: *\r\n" +
               "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
               "Access-Control-Allow-Headers: Content-Type\r\n" +
               "Connection: close\r\n\r\n$body"
    }
}
