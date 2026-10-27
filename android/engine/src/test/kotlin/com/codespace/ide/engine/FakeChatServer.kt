package com.codespace.ide.engine

import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hand-rolled localhost SSE fake server for engine contract tests. Scripts a
 * full HTTP response: status + headers + SSE body written line-by-line with
 * flushes, so chunk boundaries are real. No third-party code, pure JVM.
 */
class FakeChatServer {

    private val server = ServerSocket(0)
    private val thread = Thread { acceptLoop() }
    private val connections = AtomicInteger(0)
    @Volatile private var running = true

    /** Script: full HTTP status line + extra headers + SSE body. */
    data class Script(val status: Int, val headers: List<String>, val sseLines: List<String>)

    /** >0 = accept, read the request, hold the socket silent this long, close without writing (timeout path). */
    @Volatile var stallMs: Long = 0
    @Volatile var lastScript: Script = Script(200, listOf("Content-Type: text/event-stream"), emptyList())
    @Volatile var served = 0
    @Volatile var disconnectedMidStream = 0
    @Volatile var receivedBody = ""

    val port: Int get() = server.localPort

    fun start() { thread.start() }

    fun stop() {
        running = false
        try { server.close() } catch (_: Exception) {}
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try { server.accept() } catch (_: Exception) { return } ?: return
            Thread { serveOne(socket) }.start()
        }
    }

    private fun serveOne(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            // Read the request head + Content-Length body (engine always sets it).
            val head = StringBuilder()
            var bodyLen = -1
            while (true) {
                val b = input.read()
                if (b < 0) return
                head.append(b.toChar())
                if (head.length >= 4 && head.endsWith("\r\n\r\n")) break
                val m = Regex("Content-Length: (\\d+)").find(head)
                if (m != null) bodyLen = m.groupValues[1].toInt()
            }
            val body = ByteArray(bodyLen.coerceAtLeast(0))
            var off = 0
            while (off < body.size) {
                val r = input.read(body, off, body.size - off)
                if (r < 0) break
                off += r
            }
            receivedBody = String(body, Charsets.UTF_8)
            served++
            connections.incrementAndGet()
            if (stallMs > 0) {
                try { Thread.sleep(stallMs) } catch (_: InterruptedException) { return }
                socket.close()
                return
            }
            val script = lastScript
            val w = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            w.write("HTTP/1.1 ${script.status} ${reason(script.status)}\r\n")
            w.write("Content-Type: text/event-stream\r\n")
            for (h in script.headers) w.write(h + "\r\n")
            w.write("\r\n")
            w.flush()
            for (line in script.sseLines) {
                if (!running) { disconnectedMidStream++; break }
                try {
                    w.write(line + "\n")  // SSE lines MUST be newline-terminated
                    w.flush()
                } catch (_: Exception) {
                    disconnectedMidStream++
                    break
                }
                Thread.sleep(2)
            }
            socket.close()
        } catch (_: Exception) {
            disconnectedMidStream++
        }
    }

    private fun reason(s: Int) = when (s) {
        200 -> "OK"; 402 -> "Payment Required"; 429 -> "Too Many Requests"
        401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Status $s"
    }
}
