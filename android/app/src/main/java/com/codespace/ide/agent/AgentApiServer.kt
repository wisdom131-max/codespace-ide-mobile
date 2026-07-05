package com.codespace.ide.agent

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * AgentApiServer — lightweight local HTTP server that exposes ALL AgentTools
 * to ANY AI running in the terminal (Claude Code, Ollama CLI, llama.cpp, etc.)
 *
 * Runs on port 8765 inside the app process. Terminal AI calls it via:
 *   curl -s -X POST http://localhost:8765/tool/run_command -d '{"command":"ls -la"}'
 *   curl -s http://localhost:8765/tools
 *
 * This gives terminal-launched AI the SAME 32 tools as the chat panel:
 *   Shell, Git, Remotion, Secrets, Web, Memory, Connectors, Entities, Scheduler, Media, Packages
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
                serverSocket = ServerSocket(PORT)
                Log.i(TAG, "Agent API server started on port $PORT")

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

    private fun handleRequest(client: java.net.Socket) {
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
                        "render_remotion","save_secret","get_secret","detect_secrets",
                        "web_fetch","web_search","save_memory","read_memory","delete_memory",
                        "list_connectors","connect_service","use_connector",
                        "create_entity","read_entities","update_entity","delete_entity",
                        "schedule_task","list_tasks","cancel_task",
                        "generate_image","upload_file","install_package"
                    )
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
                    val wrapped = JSONObject()
                        .put("name", toolName)
                        .put("arguments", args)

                    val result = AgentTools.executeTool(toolName, args, ctx)
                    httpJson(200, """{"tool":"$toolName","result":${JSONObject.quote(result)}}""")
                }

                // System prompt for CLI AI tools
                method == "GET" && path == "/system-prompt" ->
                    httpJson(200, """{"prompt":${JSONObject.quote(AgentTools.TOOLS_DESCRIPTION)}}""")

                else -> httpJson(404, """{"error":"Not found: $method $path"}""")
            }
        } catch (e: Exception) {
            httpJson(500, """{"error":"${e.message?.replace("\"", "\\\"")}"}""")
        }
    }

    private fun httpJson(code: Int, body: String): String {
        val status = when (code) {
            200 -> "OK"
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
