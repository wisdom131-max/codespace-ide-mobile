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
    private val notificationHandlers = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    private val writeLock = Any()

    @Volatile private var running = false
    private var readerThread: Thread? = null

    /**
     * Start the background reader thread.
     */
    fun start() {
        running = true
        readerThread = Thread {
            while (running) {
                try {
                    val message = readMessage() ?: break
                    handleMessage(message)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    if (!running) break
                }
            }
            pendingRequests.values.forEach {
                it.completeExceptionally(IOException("LSP connection closed"))
            }
            pendingRequests.clear()
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

        try {
            writeMessage(message)
        } catch (e: Exception) {
            pendingRequests.remove(id)
            return null
        }

        return try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: Exception) {
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

    // ── Internal ──────────────────────────────────────────────────

    private fun handleMessage(message: JSONObject) {
        val hasId = message.has("id")
        val hasMethod = message.has("method")

        if (hasId && !hasMethod) {
            // Response to our request
            val id = message.optLong("id", -1)
            val future = pendingRequests.remove(id)
            if (future != null) {
                if (message.has("error")) {
                    val errorObj = message.optJSONObject("error")
                    val errorMsg = errorObj?.optString("message", "LSP error") ?: "LSP error"
                    future.completeExceptionally(RuntimeException(errorMsg))
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

    private fun readMessage(): JSONObject? {
        try {
            var contentLength = 0
            while (true) {
                val line = readLine() ?: return null
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:")) {
                    contentLength = line.removePrefix("Content-Length:").trim().toIntOrNull() ?: 0
                }
            }
            if (contentLength <= 0) return null
            val bytes = ByteArray(contentLength)
            dataInput.readFully(bytes)
            return JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: EOFException) {
            return null
        } catch (_: IOException) {
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
            process.outputStream.write(header)
            process.outputStream.write(bodyBytes)
            process.outputStream.flush()
        }
    }
}
