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
        3000 to "Node dev server",
        8080 to "HTTP alt",
        5000 to "Flask / dev server",
        5173 to "Vite",
        4200 to "Angular",
        8000 to "Django / Python http.server",
    )

    suspend fun scan(extraPorts: List<Int> = emptyList()): List<ForwardedPort> = withContext(Dispatchers.IO) {
        // IG14 (2026-09-27): candidates are no longer ONLY the static well-known list —
        // the kernel's actual LISTEN sockets (from /proc/net/tcp + tcp6; guest dev
        // servers share the kernel network namespace) join the probe, so a server on
        // any port is visible instead of invisible-by-default.
        val candidates = (WELL_KNOWN.keys + listeningPorts() + extraPorts).distinct()
        candidates.map { port ->
            async { if (isOpen(port)) ForwardedPort(port, WELL_KNOWN[port] ?: "Listening") else null }
        }.awaitAll().filterNotNull().sortedBy { it.port }
    }

    /**
     * IG14: real candidate discovery. Parses the kernel's LISTEN sockets from
     * /proc/net/tcp and /proc/net/tcp6 (state 0A; ports in hex). Proot guest servers
     * share the kernel network namespace, so dev servers hosted in the guest appear
     * here. Unreadable /proc degrades to an empty list — the static probe still runs.
     */
    fun listeningPorts(): List<Int> {
        val found = sortedSetOf<Int>()
        for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            try {
                val lines = java.io.File(path).readLines()
                for (i in 1 until lines.size) { // skip the header line
                    val cols = lines[i].trim().split(Regex("\\s+"))
                    if (cols.size > 3 && cols[3].equals("0A", ignoreCase = true)) {
                        val port = cols[1].substringAfterLast(":").toIntOrNull(16) ?: continue
                        found.add(port)
                    }
                }
            } catch (_: Exception) { /* proc unreadable — static list only */ }
        }
        return found.toList()
    }

    internal fun isOpen(port: Int, timeoutMs: Int = 200): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }
}
