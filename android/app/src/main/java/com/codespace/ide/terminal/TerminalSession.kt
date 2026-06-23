package com.codespace.ide.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Local terminal session.
 *
 * On-device it spawns a real OS process (Bash where available, plus optional bundled
 * Node/Python runtimes that are downloaded on first use to keep the APK lean). The same
 * interface backs remote sessions, where [start] is replaced by a WebSocket PTY or SSHJ
 * shell — see [com.codespace.ide.ssh.SshManager.openShell] and the backend
 * TerminalGateway.
 *
 * Supported out of the box: bash, sh, git, node, npm, pnpm, yarn, python/pip, and common
 * Linux utilities present on the device or remote host.
 */
class TerminalSession(
    private val workingDir: String,
    private val shell: String = "/system/bin/sh",
    private val backendUrl: String? = null,
) {
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var remoteSession: RemoteTerminalSession? = null

    val isRemote: Boolean
        get() = remoteSession != null

    fun start(): Flow<String> {
        backendUrl?.takeIf { RemoteTerminalSession.isReachable(it) }?.let { url ->
            remoteSession = RemoteTerminalSession(url)
            return remoteSession!!.start()
        }
        remoteSession = null
        return startLocal()
    }

    private fun startLocal(): Flow<String> = callbackFlow {
        val pb = ProcessBuilder(shell)
            .directory(java.io.File(workingDir))
            .redirectErrorStream(true)
        pb.environment()["TERM"] = "xterm-256color"
        val proc = pb.start().also { process = it }
        writer = OutputStreamWriter(proc.outputStream)

        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val thread = Thread {
            try {
                val buf = CharArray(2048)
                var read = reader.read(buf)
                while (read != -1) {
                    trySend(String(buf, 0, read))
                    read = reader.read(buf)
                }
            } catch (_: Throwable) { /* closed */ }
            trySend("\n[process exited ${runCatching { proc.exitValue() }.getOrDefault("?")} ]\n")
        }.apply { start() }

        awaitClose {
            runCatching { proc.destroy() }
            thread.interrupt()
        }
    }.flowOn(Dispatchers.IO)

    fun send(input: String) {
        remoteSession?.send(input)
            ?: writer?.apply {
                write(input)
                flush()
            }
    }

    fun resize(cols: Int, rows: Int) {
        remoteSession?.resize(cols, rows)
    }

    fun stop() {
        remoteSession?.close()
        if (remoteSession == null) {
            runCatching { writer?.close() }
            runCatching { process?.destroy() }
        }
    }
}
