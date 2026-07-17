package com.codespace.ide.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class RemoteTerminalSession(private val backendUrl: String) {
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null

    fun start(): Flow<String> = callbackFlow {
        val wsUrl = buildTerminalWebSocketUrl(backendUrl)
            ?: run {
                close(IllegalArgumentException("Invalid backend URL: $backendUrl"))
                return@callbackFlow
            }

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket = ws
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                trySend(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        }

        webSocket = client.newWebSocket(request, listener)

        awaitClose {
            webSocket?.close(1000, "Client closed")
            client.dispatcher.executorService.shutdown()
        }
    }.flowOn(Dispatchers.IO)

    fun send(input: String) {
        webSocket?.send(input)
    }

    fun resize(cols: Int, rows: Int) {
        webSocket?.send("{\"type\":\"resize\",\"cols\":$cols,\"rows\":$rows}")
    }

    fun close() {
        webSocket?.close(1000, "Client closed")
    }

    companion object {
        fun isReachable(backendUrl: String): Boolean {
            val healthUrl = buildHttpHealthUrl(backendUrl) ?: return false
            val client = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .build()

            return try {
                client.newCall(Request.Builder().url(healthUrl).get().build()).execute().use { it.isSuccessful }
            } catch (_: Throwable) {
                false
            }
        }

        private fun parseBackendUrl(backendUrl: String, defaultScheme: String): HttpUrl? {
            val candidate = if (backendUrl.startsWith("http://") || backendUrl.startsWith("https://")) {
                backendUrl
            } else {
                "$defaultScheme://$backendUrl"
            }
            return candidate.toHttpUrlOrNull()
        }

        private fun buildHttpHealthUrl(backendUrl: String): HttpUrl? {
            val base = parseBackendUrl(backendUrl, "http") ?: return null
            return base.newBuilder()
                .encodedPath("/health")
                .build()
        }

        private fun buildTerminalWebSocketUrl(backendUrl: String): HttpUrl? {
            val base = parseBackendUrl(backendUrl, "http") ?: return null
            val wsScheme = if (base.scheme == "https") "wss" else "ws"
            return base.newBuilder()
                .scheme(wsScheme)
                .encodedPath("/ws/terminal")
                .build()
        }
    }
}
