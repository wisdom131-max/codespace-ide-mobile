package com.codespace.ide.debug

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * P26-2a: DAPClient — Debug Adapter Protocol client over stdin/stdout.
 *
 * Implements the DAP wire protocol (Content-Length framing, JSON body) mirroring
 * JsonRpcClient's approach for LSP but adapted for DAP's request/response/event model.
 *
 * Usage:
 *   val client = DAPClient(process)
 *   client.onEvent("stopped") { body -> handlePause(body) }
 *   client.start()
 *   val initResp = client.request("initialize", initArgs)
 *   client.sendRequest("launch", launchArgs)
 */
class DAPClient(private val process: Process) {

    private val TAG = "DAPClient"

    private val seq = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, LinkedBlockingQueue<JSONObject?>>()
    private val eventHandlers = ConcurrentHashMap<String, (JSONObject) -> Unit>()

    private lateinit var writer: PrintWriter
    private lateinit var readerThread: Thread
    private lateinit var stderrThread: Thread

    @Volatile var running = false

    // ── Start / Stop ───────────────────────────────────────────────────────────

    fun start() {
        running = true
        writer = PrintWriter(process.outputStream.bufferedWriter())

        readerThread = Thread({
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                while (running) {
                    val msg = readMessage(reader) ?: break
                    dispatch(msg)
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Reader thread error: ${e.message}")
            }
        }, "dap-reader").also { it.isDaemon = true; it.start() }

        stderrThread = Thread({
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    Log.w(TAG, "DAP-STDERR: $line")
                }
            } catch (_: Exception) {}
        }, "dap-stderr").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { process.destroyForcibly() } catch (_: Exception) {}
    }

    // ── Wire protocol ──────────────────────────────────────────────────────────

    private fun readMessage(reader: BufferedReader): JSONObject? {
        var contentLength = -1
        // Read headers
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.isBlank()) break
            if (line.startsWith("Content-Length:")) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength <= 0) return null
        // Read body
        val buf = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = reader.read(buf, read, contentLength - read)
            if (n < 0) return null
            read += n
        }
        return try { JSONObject(String(buf)) } catch (e: Exception) {
            Log.e(TAG, "Failed to parse DAP message: ${e.message}")
            null
        }
    }

    private fun writeMessage(obj: JSONObject) {
        val body = obj.toString()
        val header = "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n"
        synchronized(writer) {
            writer.print(header)
            writer.print(body)
            writer.flush()
        }
        Log.d(TAG, "DAP → ${obj.optString("command","?")} seq=${obj.optInt("seq",-1)}")
    }

    private fun dispatch(msg: JSONObject) {
        val type = msg.optString("type", "")
        Log.d(TAG, "DAP ← type=$type command/event=${msg.optString("command","") + msg.optString("event","")}")
        when (type) {
            "response" -> {
                val reqSeq = msg.optInt("request_seq", -1)
                pending[reqSeq]?.offer(msg)
            }
            "event" -> {
                val event = msg.optString("event", "")
                val body = msg.optJSONObject("body") ?: JSONObject()
                eventHandlers[event]?.invoke(body)
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Register a handler for a DAP event (e.g. "stopped", "output", "terminated"). */
    fun onEvent(event: String, handler: (JSONObject) -> Unit) {
        eventHandlers[event] = handler
    }

    /** Send a DAP request and wait for a response. Returns the response body or null on timeout/error. */
    fun request(command: String, args: JSONObject? = null, timeoutSeconds: Long = 10): JSONObject? {
        val s = seq.getAndIncrement()
        val msg = JSONObject()
        msg.put("seq", s)
        msg.put("type", "request")
        msg.put("command", command)
        if (args != null) msg.put("arguments", args)

        val queue = LinkedBlockingQueue<JSONObject?>(1)
        pending[s] = queue

        return try {
            writeMessage(msg)
            val resp = queue.poll(timeoutSeconds, TimeUnit.SECONDS)
            if (resp == null) {
                Log.e(TAG, "DAP request '$command' timed out after ${timeoutSeconds}s")
                return null
            }
            if (!resp.optBoolean("success", false)) {
                Log.e(TAG, "DAP request '$command' failed: ${resp.optString("message","")}")
                return null
            }
            resp.optJSONObject("body")
        } finally {
            pending.remove(s)
        }
    }

    /** Fire-and-forget DAP request (no response expected, e.g. configurationDone). */
    fun sendRequest(command: String, args: JSONObject? = null) {
        val s = seq.getAndIncrement()
        val msg = JSONObject()
        msg.put("seq", s)
        msg.put("type", "request")
        msg.put("command", command)
        if (args != null) msg.put("arguments", args)
        writeMessage(msg)
    }
}

// ── DAP response data classes ────────────────────────────────────────────────

data class DAPCapabilities(
    val supportsConfigurationDoneRequest: Boolean = false,
    val supportsFunctionBreakpoints: Boolean = false,
    val supportsConditionalBreakpoints: Boolean = false,
    val supportsLogPoints: Boolean = false,
    val supportsSetVariable: Boolean = false,
    val supportsTerminateRequest: Boolean = false,
    val supportsRestartRequest: Boolean = false,
    val supportsEvaluateForHovers: Boolean = false,
)

fun JSONObject.toDAPCapabilities() = DAPCapabilities(
    supportsConfigurationDoneRequest = optBoolean("supportsConfigurationDoneRequest"),
    supportsFunctionBreakpoints      = optBoolean("supportsFunctionBreakpoints"),
    supportsConditionalBreakpoints   = optBoolean("supportsConditionalBreakpoints"),
    supportsLogPoints                = optBoolean("supportsLogPoints"),
    supportsSetVariable              = optBoolean("supportsSetVariable"),
    supportsTerminateRequest         = optBoolean("supportsTerminateRequest"),
    supportsRestartRequest           = optBoolean("supportsRestartRequest"),
    supportsEvaluateForHovers        = optBoolean("supportsEvaluateForHovers"),
)
