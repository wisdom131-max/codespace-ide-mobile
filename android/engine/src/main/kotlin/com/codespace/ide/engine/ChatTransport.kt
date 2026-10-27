package com.codespace.ide.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Provider transport abstraction. The engine only depends on this interface;
 * tests swap in the fake server, the app wires the real endpoint registry
 * (per-endpoint key slots, provider id custom_<slug> — the locked MK plan).
 * Pure JVM: HttpURLConnection, no OkHttp/Android dependency.
 */
interface ChatTransport {
    /**
     * POST [payloadJson] to [url] with [headers]. Emits raw response-body
     * chunks; fails with TransportHttpException (status/headers/body) for
     * non-2xx so the taxonomy can classify, IOException for network-layer
     * failures. Cold and cancellable: cancelling collection disconnects.
     */
    fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String>
}

class TransportHttpException(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) : Exception("HTTP $status")

/**
 * Simple JVM transport: HttpURLConnection streaming the response body.
 * Timeouts are constructor-injectable so contract tests can exercise the
 * timeout path quickly. readTimeout is the MAX IDLE between bytes (each
 * blocking read uses it), not a total-duration cap.
 */
class HttpTransport(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 120_000,
) : ChatTransport {

    override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> =
        callbackFlow {
            val conn = URL(url).openConnection() as HttpURLConnection
            // Producer runs off the collector's thread so a cancelled collection
            // reaches awaitClose immediately; disconnect() unblocks the reader.
            launch(Dispatchers.IO) {
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = connectTimeoutMs
                    conn.readTimeout = readTimeoutMs
                    for ((k, v) in headers) conn.setRequestProperty(k, v)
                    conn.setRequestProperty("Content-Type", "application/json")
                    val bytes = payloadJson.toByteArray(Charsets.UTF_8)
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                    val code = conn.responseCode
                    if (code < 200 || code >= 300) {
                        val errBody = try {
                            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        } catch (_: Exception) { "" }
                        val hdrs = HashMap<String, String>()
                        // Header index 0 is the status line (null key) — start at 1
                        // or the loop breaks immediately and NO headers reach
                        // the taxonomy (429 Retry-After would be lost).
                        var i = 1
                        while (true) {
                            val key = conn.getHeaderFieldKey(i) ?: break
                            val h = conn.getHeaderField(i)
                            if (h != null) hdrs[key] = h
                            i++
                        }
                        close(TransportHttpException(code, hdrs, errBody))
                        return@launch
                    }
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                    val chunk = CharArray(8192)
                    while (true) {
                        val n = reader.read(chunk)
                        if (n < 0) break
                        send(String(chunk, 0, n))
                    }
                    close()
                } catch (t: Throwable) {
                    close(t)
                }
            }
            awaitClose { conn.disconnect() }
        }
}
