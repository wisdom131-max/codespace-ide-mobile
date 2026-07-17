package com.codespace.ide.preview

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * LivePreviewServer — lightweight embedded HTTP server for live preview of web projects.
 *
 * Serves static files from the active project's root directory on port 5500.
 * Injects a small SSE-based auto-reload script into HTML responses so that
 * when the user edits and saves a file, the preview WebView refreshes automatically.
 *
 * Architecture:
 * - No external dependencies (no Node, no proot, no npm install)
 * - No file watcher / inotify — the app's onContentChange callback calls reload()
 * - SSE (Server-Sent Events) pushes reload signals to all connected WebViews
 * - Binds to localhost only — never exposed beyond the device
 *
 * Port: 5500 (VS Code Live Server's default, avoids conflicts with 8765/3000/5173/8080)
 */
object LivePreviewServer {
    private const val TAG = "LivePreviewServer"
    private const val PORT = 5500

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    @Volatile private var running = false
    @Volatile private var projectRoot: File? = null

    // Connected SSE clients — each holds the socket so we can push events
    private val sseClients = ConcurrentHashMap.newKeySet<java.net.Socket>()

    // MIME type mapping for common web file types
    private val mimeTypes = mapOf(
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "application/javascript; charset=utf-8",
        "mjs" to "application/javascript; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "ico" to "image/x-icon",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "eot" to "application/vnd.ms-fontobject",
        "otf" to "font/otf",
        "txt" to "text/plain; charset=utf-8",
        "xml" to "application/xml; charset=utf-8",
        "wasm" to "application/wasm",
        "map" to "application/json; charset=utf-8"
    )

    // The SSE reload script injected into HTML pages.
    // Connects to /__live_reload__ and calls location.reload() on message.
    private const val RELOAD_SCRIPT = """
<script>
(function(){
  if(window.__liveReloadConnected) return;
  window.__liveReloadConnected = true;
  function connect(){
    var es = new EventSource('/__live_reload__');
    es.onmessage = function(e){
      if(e.data === 'reload'){
        try{ es.close(); }catch(_){}
        location.reload();
      }
    };
    es.onerror = function(){
      try{ es.close(); }catch(_){}
      // Reconnect after 1s on error (server might be restarting)
      setTimeout(connect, 1000);
    };
  }
  connect();
})();
</script>
</head>"""

    fun start(projectRootPath: String) {
        val root = File(projectRootPath)
        if (!root.exists() || !root.isDirectory) {
            Log.w(TAG, "Cannot start — project root does not exist: $projectRootPath")
            return
        }
        if (running) {
            // Already running — just update the project root
            this.projectRoot = root
            Log.d(TAG, "Updated project root to: $projectRootPath")
            return
        }
        this.projectRoot = root
        executor = Executors.newCachedThreadPool()
        running = true

        Thread {
            try {
                serverSocket = ServerSocket(PORT)
                Log.i(TAG, "Live preview server started on port $PORT, serving: $projectRootPath")

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
        }.also { it.isDaemon = true; it.name = "LivePreviewServer" }.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}

        // Close all SSE clients
        sseClients.forEach { try { it.close() } catch (_: Exception) {} }
        sseClients.clear()

        executor?.shutdownNow()
        serverSocket = null
        projectRoot = null
        Log.i(TAG, "Live preview server stopped")
    }

    fun isRunning(): Boolean = running

    fun getPreviewUrl(): String = "http://localhost:$PORT/"

    fun getPreviewUrl(filePath: String): String {
        // Convert an absolute file path to a URL path relative to the project root
        val root = projectRoot ?: return "http://localhost:$PORT/"
        val relative = try {
            val rootPath = root.canonicalPath
            val targetPath = File(filePath).canonicalPath
            if (targetPath.startsWith(rootPath)) {
                targetPath.substring(rootPath.length).replace(File.separatorChar, '/').trimStart('/')
            } else {
                File(filePath).name
            }
        } catch (_: Exception) {
            File(filePath).name
        }
        return "http://localhost:$PORT/${URLEncoder.encode(relative, "UTF-8").replace("%2F", "/")}"
    }

    /**
     * Push a reload signal to all connected SSE clients (preview WebViews).
     * Called from EditorPane's onContentChange after a file write.
     */
    fun reload() {
        if (sseClients.isEmpty()) return
        Log.d(TAG, "Pushing reload to ${sseClients.size} SSE client(s)")
        sseClients.forEach { client ->
            try {
                val writer = OutputStreamWriter(client.outputStream)
                writer.write("data: reload\n\n")
                writer.flush()
            } catch (e: Exception) {
                // Client disconnected — remove it
                sseClients.remove(client)
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handleRequest(client: java.net.Socket) {
        try {
            client.soTimeout = 60000 // SSE needs long-lived connections
            val reader = BufferedReader(InputStreamReader(client.inputStream))
            val rawOutput = client.outputStream

            // Parse request line
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawPath = parts[1]

            // Read headers (we need some of them)
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim().lowercase()
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
            }

            // Decode the URL path and strip query string
            val pathOnly = rawPath.substringBefore("?")
            val decodedPath = URLDecoder.decode(pathOnly, "UTF-8")

            // Handle SSE endpoint
            if (decodedPath == "/__live_reload__") {
                handleSSE(client, rawOutput)
                return
            }

            // Only handle GET for static files
            if (method != "GET" && method != "HEAD") {
                writeResponse(rawOutput, 405, "text/plain", "Method Not Allowed")
                return
            }

            val root = projectRoot
            if (root == null || !root.exists()) {
                writeResponse(rawOutput, 503, "text/plain", "No project loaded")
                return
            }

            // Resolve the file path safely (prevent directory traversal)
            val requestedFile = resolveSafeFile(root, decodedPath)
            if (requestedFile == null) {
                writeResponse(rawOutput, 403, "text/plain", "Forbidden")
                return
            }

            if (!requestedFile.exists() || requestedFile.isDirectory) {
                // Try index.html for directory requests
                val indexFile = if (requestedFile.isDirectory) File(requestedFile, "index.html") else null
                if (indexFile != null && indexFile.exists()) {
                    serveFile(indexFile, rawOutput, method)
                    return
                }
                // If root path and no index.html, try to find any HTML file
                if (decodedPath == "/" || decodedPath.isEmpty()) {
                    val htmlFile = root.walkTopDown().firstOrNull { it.extension.lowercase() == "html" }
                    if (htmlFile != null) {
                        serveFile(htmlFile, rawOutput, method)
                        return
                    }
                }
                writeResponse(rawOutput, 404, "text/plain", "Not Found: $decodedPath")
                return
            }

            serveFile(requestedFile, rawOutput, method)
        } catch (e: Exception) {
            Log.e(TAG, "Request handling error: ${e.message}")
            try {
                writeResponse(client.outputStream, 500, "text/plain", "Internal Server Error")
            } catch (_: Exception) {}
        } finally {
            // Don't close SSE clients here — they're long-lived
            if (!sseClients.contains(client)) {
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handleSSE(client: java.net.Socket, rawOutput: java.io.OutputStream) {
        try {
            val writer = OutputStreamWriter(rawOutput)
            // Send SSE headers
            writer.write("HTTP/1.1 200 OK\r\n")
            writer.write("Content-Type: text/event-stream\r\n")
            writer.write("Cache-Control: no-cache\r\n")
            writer.write("Connection: keep-alive\r\n")
            writer.write("Access-Control-Allow-Origin: *\r\n")
            writer.write("\r\n")
            writer.flush()

            // Send an initial connection message
            writer.write("data: connected\n\n")
            writer.flush()

            // Register this client for future reload pushes
            sseClients.add(client)
            Log.d(TAG, "SSE client connected (${sseClients.size} total)")

            // Keep the connection open — the client will be notified via reload()
            // We just need to keep reading to detect disconnects
            val reader = BufferedReader(InputStreamReader(client.inputStream))
            while (running && !client.isClosed) {
                try {
                    val line = reader.readLine()
                    if (line == null) break // Client disconnected
                    // SSE clients may send keepalive comments, just ignore
                } catch (e: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "SSE client error: ${e.message}")
        } finally {
            sseClients.remove(client)
            Log.d(TAG, "SSE client disconnected (${sseClients.size} remaining)")
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun resolveSafeFile(root: File, path: String): File? {
        // Strip leading slashes for relative resolution
        val cleanPath = path.trimStart('/')

        // Prevent directory traversal — reject any path containing ..
        if (cleanPath.contains("..")) {
            return null
        }

        val target = File(root, cleanPath)
        try {
            // Canonical path comparison — ensure the resolved file is within the project root
            val rootCanonical = root.canonicalPath
            val targetCanonical = target.canonicalPath
            if (!targetCanonical.startsWith(rootCanonical)) {
                return null
            }
        } catch (_: Exception) {
            return null
        }

        return target
    }

    private fun serveFile(file: File, rawOutput: java.io.OutputStream, method: String) {
        val ext = file.extension.lowercase()
        val mimeType = mimeTypes[ext] ?: "application/octet-stream"

        val content = if (file.length() > 256 * 1024) {
            // Large file — stream directly to avoid OOM on mobile
            file.inputStream().use { it.readBytes() }
        } else {
            file.readBytes()
        }

        // For HTML files, inject the reload script before </head>
        val isHtml = ext == "html" || ext == "htm"
        val responseBytes = if (isHtml) {
            val html = String(content, Charsets.UTF_8)
            if (html.contains("</head>", ignoreCase = true)) {
                html.replaceFirst("</head>".toRegex(RegexOption.IGNORE_CASE), RELOAD_SCRIPT).toByteArray(Charsets.UTF_8)
            } else if (html.contains("<body", ignoreCase = true)) {
                // No </head> — inject before <body>
                html.replaceFirst("<body".toRegex(RegexOption.IGNORE_CASE), "$RELOAD_SCRIPT<body").toByteArray(Charsets.UTF_8)
            } else {
                // No head or body — just append the script
                (html + RELOAD_SCRIPT).toByteArray(Charsets.UTF_8)
            }
        } else {
            content
        }

        try {
            val header = StringBuilder()
            header.append("HTTP/1.1 200 OK\r\n")
            header.append("Content-Type: $mimeType\r\n")
            header.append("Content-Length: ${responseBytes.size}\r\n")
            header.append("Cache-Control: no-cache\r\n")
            header.append("Access-Control-Allow-Origin: *\r\n")
            header.append("Connection: close\r\n")
            header.append("\r\n")

            rawOutput.write(header.toString().toByteArray(Charsets.UTF_8))
            if (method != "HEAD") {
                rawOutput.write(responseBytes)
            }
            rawOutput.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error serving ${file.name}: ${e.message}")
        }
    }

    private fun writeResponse(rawOutput: java.io.OutputStream, code: Int, contentType: String, body: String) {
        try {
            val response = StringBuilder()
            response.append("HTTP/1.1 $code ${statusText(code)}\r\n")
            response.append("Content-Type: $contentType\r\n")
            response.append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            response.append("Connection: close\r\n")
            response.append("\r\n")
            response.append(body)

            rawOutput.write(response.toString().toByteArray(Charsets.UTF_8))
            rawOutput.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing response: ${e.message}")
        }
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Unknown"
    }
}
