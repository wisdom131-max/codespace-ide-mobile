package com.codespace.ide.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Real localhost port probing for the Ports panel — VS Code's Ports panel
 * shows forwarded/listening ports for dev servers (e.g. a webpack dev server).
 * We don't have a container/devcontainer port-forwarding layer here, but
 * proot shares the host's network namespace, so anything a server inside the
 * Ubuntu proot binds to localhost:PORT is directly reachable from the
 * Android process's own localhost too. A short-timeout TCP connect attempt
 * is a reliable, dependency-free way to detect "is something actually
 * listening here" instead of showing a static empty list.
 */
data class ForwardedPort(val port: Int, val label: String)

object PortsScanner {

    // Common dev-server ports used by this project + typical web frameworks.
    val WELL_KNOWN = linkedMapOf(
        3000 to "Remotion Studio / Node dev server",
        11434 to "Ollama",
        8080 to "HTTP alt",
        5000 to "Flask / dev server",
        5173 to "Vite",
        4200 to "Angular",
        8000 to "Django / Python http.server",
    )

    suspend fun scan(extraPorts: List<Int> = emptyList()): List<ForwardedPort> = withContext(Dispatchers.IO) {
        val candidates = (WELL_KNOWN.keys + extraPorts).distinct()
        candidates.map { port ->
            async { if (isOpen(port)) ForwardedPort(port, WELL_KNOWN[port] ?: "Custom") else null }
        }.awaitAll().filterNotNull().sortedBy { it.port }
    }

    private fun isOpen(port: Int, timeoutMs: Int = 200): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }
}
