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
 * P26-2a: DAPClient — Debug Adapter Protocol client over stdin/stdout OR a raw
 * TCP socket (ROUND-3, 2026-10-03): vscode-js-debug's dapDebugServer.js has NO
 * stdio mode at all — verified from its own bundled CLI parsing (process.argv
 * is read positionally as [,script,port="8123",host="localhost"], with no
 * "--stdio" branch), so a "--stdio" argument is misread as a non-numeric PORT
 * value and the server opens a Unix-socket-path listener named "--stdio"
 * instead — explaining the silent 15s initialize timeout exactly (no crash,
 * no error, just nothing ever answering on stdin/stdout). jdap (our own
 * driver) and debugpy both genuinely support stdio, so this class now backs
 * either transport.
 *
 * Implements the DAP wire protocol (Content-Length framing, JSON body) mirroring
 * JsonRpcClient's approach for LSP but adapted for DAP's request/response/event model.
 *
 * Usage:
 *   val client = DAPClient(process)              // stdio transport (jdap, debugpy)
 *   val client = DAPClient(socket)                // TCP transport (vscode-js-debug)
 *   client.onEvent("stopped") { body -> handlePause(body) }
 *   client.start()
 *   val initResp = client.request("initialize", initArgs)
 *   client.sendRequest("launch", launchArgs)
 */
class DAPClient private constructor(
    private val input: java.io.InputStream,
    private val output: java.io.OutputStream,
    private val errorStream: java.io.InputStream?,
    private val onStop: () -> Unit,
) {
    /** Stdio transport — the process's own stdin/stdout CARRY the DAP stream (jdap, debugpy). */
    constructor(process: Process) : this(
        process.inputStream, process.outputStream, process.errorStream,
        { try { process.destroyForcibly() } catch (_: Exception) {} },
    )

    /** TCP transport — the DAP stream rides a socket; the launched process's
     *  own stdout/stderr are plain console logs, drained separately by the
     *  caller (see NodeDAPAdapter), not read by this client. */
    constructor(socket: java.net.Socket) : this(
        socket.getInputStream(), socket.getOutputStream(), null,
        { try { socket.close() } catch (_: Exception) {} },
    )

    private val TAG = "DAPClient"

    private val seq = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, LinkedBlockingQueue<JSONObject?>>()
    private val eventHandlers = ConcurrentHashMap<String, (JSONObject) -> Unit>()

    private lateinit var writer: PrintWriter
    private lateinit var readerThread: Thread
    private var stderrThread: Thread? = null

    @Volatile var running = false

    // ── Start / Stop ───────────────────────────────────────────────────────────

    fun start() {
        running = true
        writer = PrintWriter(output.bufferedWriter())

        readerThread = Thread({
            try {
                val reader = BufferedReader(InputStreamReader(input))
                while (running) {
                    val msg = readMessage(reader) ?: break
                    dispatch(msg)
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Reader thread error: ${e.message}")
            }
        }, "dap-reader").also { it.isDaemon = true; it.start() }

        if (errorStream != null) {
            stderrThread = Thread({
                try {
                    errorStream.bufferedReader().forEachLine { line ->
                        Log.w(TAG, "DAP-STDERR: $line")
                    }
                } catch (_: Exception) {}
            }, "dap-stderr").also { it.isDaemon = true; it.start() }
        }
    }

    fun stop() {
        running = false
        try { onStop() } catch (_: Exception) {}
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
            // DG07 (P4b): server->client REVERSE requests (runInTerminal,
            // launchBrowser, ...). dispatch previously had no "request" branch
            // at all — any reverse request was silently ignored with no
            // response, hanging the server side. We do not implement them
            // (initialize advertises supportsRunInTerminalRequest=false),
            // but per DAP spec the client MUST answer a request with a
            // response — we now reply honestly: not supported.
            "request" -> {
                val command = msg.optString("command", "")
                val reqSeq = msg.optInt("seq", -1)
                Log.w(TAG, "DAP reverse request '$command' received — replying notSupported")
                com.codespace.ide.diagnostics.AppOutputLog.log(
                    "[DAP] Server requested '$command' (reverse request) — not supported, replied to server", "debug")
                writeMessage(JSONObject().apply {
                    put("seq", seq.getAndIncrement())
                    put("type", "response")
                    put("request_seq", reqSeq)
                    put("success", false)
                    put("command", command)
                    put("message", "Reverse request not supported by this client")
                })
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Register a handler for a DAP event (e.g. "stopped", "output", "terminated"). */
    fun onEvent(event: String, handler: (JSONObject) -> Unit) {
        eventHandlers[event] = handler
    }

    /** Send a DAP request and wait for a response. Returns the response body or null on timeout/error. */
    fun request(command: String, args: JSONObject? = null, timeoutSeconds: Long = 10): JSONObject? =
        requestDetailed(command, args, timeoutSeconds).first

    /**
     * Like [request] but carries WHY it failed: error is non-null iff body is
     * null. F6-d: "attach failed, debuggee may not be listening" hid the real
     * reason (refused vs timeout vs adapter error) — adapters now print it.
     */
    fun requestDetailed(command: String, args: JSONObject? = null, timeoutSeconds: Long = 10): Pair<JSONObject?, String?> {
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
                return Pair(null, "no response from the debug adapter within ${timeoutSeconds}s")
            }
            if (!resp.optBoolean("success", false)) {
                val detail = resp.optString("message", "")
                Log.e(TAG, "DAP request '$command' failed: $detail")
                return Pair(null, "adapter error: ${if (detail.isBlank()) "unknown" else detail}")
            }
            Pair(resp.optJSONObject("body"), null)
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

data class DAPExceptionFilter(
    val filter: String,
    val label: String,
    val description: String = "",
    val defaultOn: Boolean = false,
)

data class DAPCapabilities(
    val supportsConfigurationDoneRequest: Boolean = false,
    val supportsFunctionBreakpoints: Boolean = false,
    val supportsConditionalBreakpoints: Boolean = false,
    val supportsLogPoints: Boolean = false,
    val supportsSetVariable: Boolean = false,
    val supportsTerminateRequest: Boolean = false,
    val supportsRestartRequest: Boolean = false,
    val supportsEvaluateForHovers: Boolean = false,
    // P2: restartFrame support (js-debug/debugpy both advertise it)
    val supportsRestartFrame: Boolean = false,
    // P1-D4: exception breakpoint filters advertised by the adapter (e.g. caught/uncaught)
    val exceptionFilters: List<DAPExceptionFilter> = emptyList(),
)

fun JSONObject.toDAPCapabilities(): DAPCapabilities {
    val filters = mutableListOf<DAPExceptionFilter>()
    val filtersArr = optJSONArray("exceptionBreakpointFilters")
    if (filtersArr != null) {
        for (i in 0 until filtersArr.length()) {
            val f = filtersArr.optJSONObject(i) ?: continue
            filters.add(
                DAPExceptionFilter(
                    filter = f.optString("filter", ""),
                    label = f.optString("label", f.optString("filter", "")),
                    description = f.optString("description", ""),
                    defaultOn = f.optBoolean("default", false),
                )
            )
        }
    }
    return DAPCapabilities(
        supportsConfigurationDoneRequest = optBoolean("supportsConfigurationDoneRequest"),
        supportsFunctionBreakpoints      = optBoolean("supportsFunctionBreakpoints"),
        supportsConditionalBreakpoints   = optBoolean("supportsConditionalBreakpoints"),
        supportsLogPoints                = optBoolean("supportsLogPoints"),
        supportsSetVariable              = optBoolean("supportsSetVariable"),
        supportsTerminateRequest         = optBoolean("supportsTerminateRequest"),
        supportsRestartRequest           = optBoolean("supportsRestartRequest"),
        supportsEvaluateForHovers        = optBoolean("supportsEvaluateForHovers"),
        supportsRestartFrame            = optBoolean("supportsRestartFrame"),
        exceptionFilters                 = filters.toList(),
    )
}
