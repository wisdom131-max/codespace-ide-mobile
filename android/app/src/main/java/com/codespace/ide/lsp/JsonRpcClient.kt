package com.codespace.ide.lsp

import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * JSON-RPC 2.0 client over stdio using LSP Content-Length framing.
 *
 * LSP messages are framed as:
 *   Content-Length: <N>\r\n
 *   \r\n
 *   <N bytes of UTF-8 JSON>
 *
 * This client runs a background reader thread that:
 *   - Parses incoming messages
 *   - Matches responses to pending requests by id
 *   - Dispatches server notifications to registered handlers
 *
 * P22-F: LSP groundwork.
 */
class JsonRpcClient(private val process: Process) {

    private val dataInput = DataInputStream(process.inputStream)
    private val nextId = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableFuture<Any?>>()
    // Phase X-7: Per-method pending request tracking — prevents cross-method cancellation
    // (e.g. cancelling a completion accidentally cancelling a hover with a higher ID).
    private val pendingRequestsByMethod = ConcurrentHashMap<String, MutableMap<Long, CompletableFuture<Any?>>>()

    /**
     * PHASE-B/B1: hot, stale-discarded, read-only per-position queries. A NEW request
     * for one of these methods auto-cancels the still-in-flight previous one. Mutations
     * and lifecycle methods are excluded - their concurrent results may all be needed.
     */
    private val SUPERSEDED_ON_NEW_REQUEST = setOf(
        "textDocument/completion", "completionItem/resolve",
        "textDocument/hover", "textDocument/signatureHelp",
        "textDocument/codeAction", "codeAction/resolve",
        "textDocument/codeLens", "codeLens/resolve",
        "textDocument/inlayHint",
        "textDocument/semanticTokens/full", "textDocument/foldingRange",
        "textDocument/documentSymbol", "textDocument/documentLink",
        "textDocument/documentColor", "textDocument/documentHighlight",
        "textDocument/definition", "textDocument/declaration",
        "textDocument/implementation", "textDocument/typeDefinition",
        "textDocument/references", "textDocument/selectionRange",
        "textDocument/moniker", "textDocument/linkedEditingRange",
        "textDocument/prepareCallHierarchy", "textDocument/prepareTypeHierarchy",
        "callHierarchy/incomingCalls", "callHierarchy/outgoingCalls",
        "typeHierarchy/subtypes", "typeHierarchy/supertypes",
        "workspace/symbol"
    )    private val notificationHandlers = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    private val writeLock = Any()

    @Volatile private var running = false

    /**
     * Phase V-M: Server generation ID — set by LspManager.
     * Used to detect and ignore stale responses from a dead server instance.
     */
    @Volatile var generation: Int = 0

    /**
     * P38-FIX: Called when the reader thread exits (server crashed, EOF, etc.).
     */
    var onDisconnect: (() -> Unit)? = null
    private var readerThread: Thread? = null

    /**
     * Start the background reader thread.
     */
    fun start() {
        running = true
        readerThread = Thread {
            log("[LSP][rpc] Reader thread started")
            while (running) {
                try {
                    val message = readMessage()
                    if (message == null) {
                        log("[LSP][rpc] Reader: readMessage returned null (EOF or error) — breaking loop")
                        break
                    }
                    log("[LSP][rpc] Reader: received message (method=${message.optString("method", "response")})")
                    handleMessage(message)
                } catch (_: InterruptedException) {
                    log("[LSP][rpc] Reader: interrupted — breaking loop")
                    break
                } catch (e: Exception) {
                    log("[LSP][rpc] Reader: exception: ${e.javaClass.simpleName}: ${e.message}")
                    if (!running) break
                }
            }
            log("[LSP][rpc] Reader thread exiting — completing ${pendingRequests.size} pending requests exceptionally")
            pendingRequests.values.forEach {
                it.completeExceptionally(IOException("LSP connection closed"))
            }
            pendingRequests.clear()
            pendingRequestsByMethod.clear()
            // P38-FIX: Notify LspManager that the connection dropped.
            try { onDisconnect?.invoke() } catch (_: Exception) {}
        }.apply {
            isDaemon = true
            name = "LSP-JsonRpc-Reader"
            start()
        }
    }

    /**
     * Stop the reader thread and clean up.
     */
    fun stop() {
        running = false
        readerThread?.interrupt()
    }

    /**
     * Send a JSON-RPC request and wait for the response (synchronous, with timeout).
     * Returns the raw result value (JSONObject, JSONArray, String, or null), or null on error/timeout.
     */
    fun request(method: String, params: JSONObject? = null, timeoutSeconds: Long = 30): Any? {
        val id = nextId.getAndIncrement()
        val message = JSONObject()
        message.put("jsonrpc", "2.0")
        message.put("id", id)
        message.put("method", method)
        if (params != null) message.put("params", params)

        val future = CompletableFuture<Any?>()
        pendingRequests[id] = future
        // Phase X-7: Track by method for per-method cancellation
        pendingRequestsByMethod.getOrPut(method) { mutableMapOf() }[id] = future

        // PHASE-B/B1 (2026-09-06): supersede in-flight requests for the SAME hot
        // method before sending the new one. A previous request for the same feature
        // (older completion/hover/codeLens/etc.) is stale by definition - its result
        // is discarded by the caller's gen/version checks - so letting it keep running
        // only wastes server CPU and delays the fresh request behind it. Mirrors VS
        // Code: a new request for the same feature cancels the in-flight one.
        // Scoped to read-only per-position queries; mutations (rename, formatting,
        // executeCommand, willRenameFiles) and lifecycle are deliberately NOT auto-
        // superseded - every concurrent result there may still be needed.
        if (method in SUPERSEDED_ON_NEW_REQUEST) {
            val stale = pendingRequestsByMethod[method]?.keys?.toList().orEmpty()
            for (oldId in stale) {
                if (oldId != id) {
                    notify("$/cancelRequest", JSONObject().put("id", oldId))
                    log("[LSP][rpc] B1 supersede: $/cancelRequest for method=$method id=$oldId (superseded by id=$id)")
                }
            }
        }

        try {
            writeMessage(message)
        } catch (e: Exception) {
            log("[LSP][rpc] request('$method'): writeMessage FAILED: ${e.javaClass.simpleName}: ${e.message}")
            pendingRequests.remove(id)
            pendingRequestsByMethod.values.forEach { it.remove(id) }
            return null
        }

        return try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            val reason = when (e) {
                is java.util.concurrent.TimeoutException -> "TIMEOUT after ${timeoutSeconds}s"
                is java.util.concurrent.ExecutionException -> "CONNECTION ERROR: ${e.cause?.message ?: e.message}"
                is InterruptedException -> "INTERRUPTED"
                else -> "ERROR: ${e.javaClass.simpleName}: ${e.message}"
            }
            log("[LSP][rpc] request('$method') FAILED: $reason")
            pendingRequests.remove(id)
            null
        }
    }

    /**
     * Send a JSON-RPC notification (no response expected).
     */
    fun notify(method: String, params: JSONObject? = null) {
        val message = JSONObject()
        message.put("jsonrpc", "2.0")
        message.put("method", method)
        if (params != null) message.put("params", params)
        try {
            writeMessage(message)
        } catch (_: Exception) {}
    }

    /**
     * Register a handler for a server-initiated notification.
     */
    fun onNotification(method: String, handler: (JSONObject) -> Unit) {
        notificationHandlers[method] = handler
    }


    /**
     * P41-K: Cancel a pending request by sending $/cancelRequest notification.
     * Per LSP spec, the server should stop processing the request and return an error response.
     */
    fun cancelRequest(requestId: Long) {
        val params = JSONObject().apply {
            put("id", requestId)
        }
        notify("$/cancelRequest", params)
        log("[LSP][rpc] Sent $/cancelRequest for id=$requestId")
    }

    /**
     * P41-K: Get the current pending request ID for a given method (for cancellation).
     * Returns -1 if no pending request matches.
     */
    fun getPendingRequestId(): Long {
        return pendingRequests.keys.maxOrNull() ?: -1L
    }

    /**
     * Phase X-7: Get the pending request ID for a specific method (for per-method cancellation).
     * Returns -1 if no pending request matches the given method.
     */
    fun getPendingRequestId(method: String): Long {
        return pendingRequestsByMethod[method]?.keys?.maxOrNull() ?: -1L
    }

    /**
     * Phase X-7: Cancel a pending request for a specific method only.
     */
    fun cancelRequest(method: String, requestId: Long) {
        val params = JSONObject().apply { put("id", requestId) }
        notify("$/cancelRequest", params)
        pendingRequestsByMethod[method]?.remove(requestId)
        log("[LSP][rpc] Sent $/cancelRequest for method=$method id=$requestId")
    }

    // ── Internal ──────────────────────────────────────────────────

    private fun handleMessage(message: JSONObject) {
        val hasId = message.has("id")
        val hasMethod = message.has("method")

        if (hasId && !hasMethod) {
            // Response to our request
            val id = message.optLong("id", -1)
            val future = pendingRequests.remove(id)
            pendingRequestsByMethod.values.forEach { it.remove(id) }
            if (future != null) {
                if (message.has("error")) {
                    val errorObj = message.optJSONObject("error")
                    val errorMsg = errorObj?.optString("message", "LSP error") ?: "LSP error"
                    val errorCode = errorObj?.optInt("code", -1) ?: -1
                    // PHASE-B/B4 (2026-09-06): RequestCancelled (-32800) and ContentModified
                    // (-32801) are the LSP spec's BENIGN cancellation signals — the server
                    // stopped because a newer request superseded this one (B1 auto-supersede)
                    // or the document content changed mid-request. These are NOT failures:
                    // complete silently with null. Callers already discard stale results via
                    // their gen/version checks, so behavior is unchanged — the requests were
                    // superseded anyway; we only stop the old ERROR log noise + exceptional path.
                    if (errorCode == -32800 || errorCode == -32801) {
                        log("[LSP][rpc] id=$id cancelled by server (code=$errorCode) - silent no-op, result superseded")
                        future.complete(null)
                    } else {
                        log("[LSP][rpc] ERROR response for id=$id: code=$errorCode msg=$errorMsg")
                        future.completeExceptionally(RuntimeException(errorMsg))
                    }
                } else {
                    future.complete(message.opt("result"))
                }
            }
        } else if (!hasId && hasMethod) {
            // Notification from server
            val method = message.optString("method", "")
            val params = message.optJSONObject("params") ?: JSONObject()
            notificationHandlers[method]?.invoke(params)
        }
        // Server-initiated requests (hasId && hasMethod) are ignored for now
    }

    private var firstRead = true  // P32-DIAG: capture raw first bytes for corruption detection

    private fun readMessage(): JSONObject? {
        try {
            var contentLength = 0
            var rawHeaderLines = mutableListOf<String>()  // P32-DIAG: capture raw header lines
            while (true) {
                val line = readLine()
                if (line == null) {
                    log("[LSP][rpc] readMessage: readLine returned null (EOF) during header read")
                    return null
                }
                if (firstRead) rawHeaderLines.add(line)  // P32-DIAG: capture first read
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:")) {
                    contentLength = line.removePrefix("Content-Length:").trim().toIntOrNull() ?: 0
                }
            }
            // P32-DIAG: On the first read, log the raw header lines to detect profile
            // banner text corruption (e.g., "[Agent] 30 tools ready..." instead of
            // "Content-Length: N"). This is the definitive evidence for the theory.
            if (firstRead) {
                firstRead = false
                if (rawHeaderLines.isNotEmpty()) {
                    val rawPreview = rawHeaderLines.take(5).joinToString(" | ")
                    log("[LSP][rpc] RAW first read (header lines): $rawPreview")
                }
                if (contentLength <= 0) {
                    log("[LSP][rpc] WARNING: first read has no valid Content-Length — likely profile banner text corruption")
                }
            }
            if (contentLength <= 0) {
                log("[LSP][rpc] readMessage: contentLength=$contentLength (invalid)")
                return null
            }
            val bytes = ByteArray(contentLength)
            dataInput.readFully(bytes)
            return JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: EOFException) {
            log("[LSP][rpc] readMessage: EOFException (stream closed)")
            return null
        } catch (e: IOException) {
            log("[LSP][rpc] readMessage: IOException: ${e.message}")
            return null
        }
    }

    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val b = dataInput.read()
            if (b == -1) return null
            if (b == 0x0D) {
                val next = dataInput.read()
                if (next == 0x0A) return sb.toString()
                sb.append(b.toChar())
                if (next != -1) sb.append(next.toChar())
            } else {
                sb.append(b.toChar())
            }
        }
    }

    private fun writeMessage(json: JSONObject) {
        val bodyBytes = json.toString().toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${bodyBytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        synchronized(writeLock) {
            try {
                process.outputStream.write(header)
                process.outputStream.write(bodyBytes)
                process.outputStream.flush()
                log("[LSP][rpc] writeMessage: wrote ${header.size + bodyBytes.size} bytes (method=${json.optString("method", "?")})")
            } catch (e: Exception) {
                log("[LSP][rpc] writeMessage FAILED: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }
    }

    /** Log to both AppOutputLog (in-app Output tab, visible without adb) and Log.d (logcat). */
    private fun log(msg: String) {
        android.util.Log.d("JsonRpcClient", msg)
        com.codespace.ide.diagnostics.AppOutputLog.log(msg, "lsp")
    }
}
