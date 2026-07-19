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
            android.util.Log.d("JsonRpcClient", "Reader thread started")
            while (running) {
                try {
                    val message = readMessage()
                    if (message == null) {
                        android.util.Log.w("JsonRpcClient", "Reader: readMessage returned null (EOF or error) — breaking loop")
                        break
                    }
                    android.util.Log.d("JsonRpcClient", "Reader: received message (method=${message.optString("method", "response")})")
                    handleMessage(message)
                } catch (_: InterruptedException) {
                    android.util.Log.d("JsonRpcClient", "Reader: interrupted — breaking loop")
                    break
                } catch (e: Exception) {
                    android.util.Log.e("JsonRpcClient", "Reader: exception: ${e.javaClass.simpleName}: ${e.message}")
                    if (!running) break
                }
            }
            android.util.Log.w("JsonRpcClient", "Reader thread exiting — completing ${pendingRequests.size} pending requests exceptionally")
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
        } catch (e: Exception) {
            val reason = when (e) {
                is java.util.concurrent.TimeoutException -> "TIMEOUT after ${timeoutSeconds}s"
                is java.util.concurrent.ExecutionException -> "CONNECTION ERROR: ${e.cause?.message ?: e.message}"
                is InterruptedException -> "INTERRUPTED"
                else -> "ERROR: ${e.javaClass.simpleName}: ${e.message}"
            }
            android.util.Log.e("JsonRpcClient", "request('$method') FAILED: $reason")
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
                val line = readLine()
                if (line == null) {
                    android.util.Log.d("JsonRpcClient", "readMessage: readLine returned null (EOF) during header read")
                    return null
                }
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:")) {
                    contentLength = line.removePrefix("Content-Length:").trim().toIntOrNull() ?: 0
                }
            }
            if (contentLength <= 0) {
                android.util.Log.w("JsonRpcClient", "readMessage: contentLength=$contentLength (invalid)")
                return null
            }
            val bytes = ByteArray(contentLength)
            dataInput.readFully(bytes)
            return JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: EOFException) {
            android.util.Log.d("JsonRpcClient", "readMessage: EOFException (stream closed)")
            return null
        } catch (e: IOException) {
            android.util.Log.d("JsonRpcClient", "readMessage: IOException: ${e.message}")
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
                android.util.Log.d("JsonRpcClient", "writeMessage: wrote ${header.size + bodyBytes.size} bytes (method=${json.optString("method", "?")})")
            } catch (e: Exception) {
                android.util.Log.e("JsonRpcClient", "writeMessage FAILED: ${e.message}")
                throw e
            }
        }
    }
}
